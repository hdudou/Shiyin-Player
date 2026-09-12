/*
 * ffmpeg_jni.c —— libffmpeg 的 JNI 壳（R-B1）。
 *
 * 模型：流式喂入 + 独立解码线程。
 *   - Java 侧 FfmpegJni.nativeFeed 逐块追加到输入缓冲（AVIO 读侧消费）。
 *   - worker 线程跑完整 FFmpeg 管线：avformat_open_input（自定义 AVIO read 回调从输入缓冲取数，
 *     数据不足时阻塞于条件变量，等 nativeFeed signal 或 nativeFeedEos）→ find_stream_info →
 *     avcodec_open2 → swr 转 s16/2/44100 → 产出的 PCM 进输出缓冲。
 *   - nativeRender 从输出缓冲取 PCM；>0 产出字节，0=EOF，<0=暂无输出（输入不足，调用方稍后重试）。
 *
 * 输入为一次性流式（AVIO 已消费字节不保留），故不支持反向 seek；seek 由 Java 侧
 * nativeRelease+recreate 重建（ExoPlayer 会让源从 seek 位置重新喂入）。
 *
 * 固定输出：s16 / 2 声道 / 44100（与 Media3 AudioSink 标准对齐）。
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <android/log.h>

#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
#include <libavutil/samplefmt.h>

#define TAG "ffmpeg_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define JNI_FUNC(ret, name) \
    JNIEXPORT ret JNICALL Java_com_shiyinplayer_player_decoder_ndk_ffmpeg_FfmpegJni_##name

/* 固定输出：s16 / 2 声道 / 44100 Hz */
#define OUT_SAMPLE_RATE 44100
#define OUT_CHANNELS    2
#define OUT_FMT         AV_SAMPLE_FMT_S16
#define AVIO_BUFFER     32768
#define PCM_INIT_CAP    (32768)
/* 防御上限：单帧样本数异常（损坏 frame header）时丢弃该帧，避免 swr_convert 越界写 → SIGSEGV */
#define MAX_SAMPLES_PER_FRAME (OUT_SAMPLE_RATE / 4)   /* 250ms 帧上限，正常音频帧远小于此 */
/* swr 转 s16/2/44100 单帧输出缓冲上限（覆盖 MAX_SAMPLES_PER_FRAME 采样） */
#define SWR_OUT_MAX (MAX_SAMPLES_PER_FRAME * OUT_CHANNELS * 2 + 64)

/* --- 流式背压（2026-08-22 R-B2）---
 * 原实现 worker 把整首文件瞬间解进无上限 pcmBuf（>44MB，超大文件 OOM），
 * 而渲染端每次只抽 8KB，时钟按抽走字节推进导致 position 虚高竞态 → 播放器 PAUSED 后回退 BUFFERING。
 * 现改为真流式：
 *  - pcmBuf 达到高水位后 worker 阻塞等 render 抽走（解码按真实消费节奏推进），有界；
 *  - 输入缓冲达到上限后 nativeFeed 返回 1（忙），Java 侧暂停喂块（OpaqueAudioRenderer.pendingChunk）；
 *  - nativeRelease 置 stop 并 signal pcmCond，确保 worker 能从水位等待中退出，join 不挂起。 */
#define PCM_HIGH_WATERMARK  ((1 << 20))             /* 1MB 解码-ahead 高水位（约 6s） */
#define INPUT_MAX_BYTES     ((1 << 20))             /* 未消费输入上限 1MB */

typedef struct FfmpegCtx {
    /* 输入缓冲：nativeFeed 写（主线程）/ AVIO read 读（worker） */
    uint8_t      *inBuf;
    size_t        inLen, inCap;
    pthread_mutex_t inMutex;
    pthread_cond_t  inCond;
    int           inEos;

    /* PCM 输出缓冲：worker 写 / nativeRender 读（主线程） */
    uint8_t      *pcmBuf;
    size_t        pcmLen, pcmCap;
    pthread_mutex_t pcmMutex;
    pthread_cond_t  pcmCond;
    int           decodeDone;
    int           decodeError;
    int           stop;              /* release 请求停止：唤醒水位等待/解码循环，join 不挂起 */

    /* FFmpeg 管线（仅 worker 访问，后仅供 render 读标量） */
    AVFormatContext *fmt;
    AVCodecContext  *codec;
    const AVCodec   *dec;
    SwrContext      *swr;
    AVPacket        *pkt;
    AVFrame         *frame;
    int              streamIdx;
    int              opened;
    int64_t          lastPtsUs;
    int64_t          durationUs;

    /* swr 输出缓冲区（栈数组承载，见 render_frame_to_pcm） */
    uint8_t          swrOut[SWR_OUT_MAX];

    pthread_t thread;
    int       threadStarted;

    int64_t  totalOutSamples; /* 用于 fallback 时间推进 */
} FfmpegCtx;

/* ---------------- 输入 AVIO ---------------- */

static int read_input(void *opaque, uint8_t *buf, int buf_size) {
    FfmpegCtx *f = (FfmpegCtx *)opaque;
    pthread_mutex_lock(&f->inMutex);
    while (f->inLen == 0 && !f->inEos)
        pthread_cond_wait(&f->inCond, &f->inMutex);
    if (f->inLen == 0) {          /* inEos：EOF */
        pthread_mutex_unlock(&f->inMutex);
        return 0;
    }
    int n = (buf_size < (int)f->inLen) ? buf_size : (int)f->inLen;
    memcpy(buf, f->inBuf, (size_t)n);
    if ((size_t)n < f->inLen)
        memmove(f->inBuf, f->inBuf + n, f->inLen - (size_t)n);
    f->inLen -= (size_t)n;
    pthread_mutex_unlock(&f->inMutex);
    return n;
}

/* ---------------- PCM 输出缓冲 ---------------- */

static void push_pcm(FfmpegCtx *f, const uint8_t *data, size_t n) {
    if (n == 0) return;
    pthread_mutex_lock(&f->pcmMutex);
    if (f->pcmLen + n > f->pcmCap) {
        size_t nc = f->pcmCap ? f->pcmCap : PCM_INIT_CAP;
        while (nc < f->pcmLen + n) nc *= 2;
        f->pcmBuf = (uint8_t *)realloc(f->pcmBuf, nc);
        f->pcmCap = nc;
    }
    memcpy(f->pcmBuf + f->pcmLen, data, n);
    f->pcmLen += n;
    pthread_cond_signal(&f->pcmCond);
    pthread_mutex_unlock(&f->pcmMutex);
}

static void mark_done(FfmpegCtx *f) {
    pthread_mutex_lock(&f->pcmMutex);
    f->decodeDone = 1;
    pthread_cond_signal(&f->pcmCond);
    pthread_mutex_unlock(&f->pcmMutex);
}

/* swr 转 s16/2/44100 单帧。返回 0=该帧已产出并压入 PCM，<0=应跳过（参数非法/无输出）。
 * 用栈上固定数组承载输出（SWR_OUT_MAX 覆盖 250ms 上限采样数），杜绝越界写。 */
static int render_frame_to_pcm(FfmpegCtx *f) {
    if (!f->frame || f->frame->nb_samples <= 0) return -1;
    /* 损坏帧头防御：采样数超上限即丢弃该帧（FFmpeg 解码损坏 flac metadata 时防越界） */
    if (f->frame->nb_samples > MAX_SAMPLES_PER_FRAME) return -1;
    if (!f->frame->extended_data || !f->frame->extended_data[0]) return -1;

    int oc = swr_get_out_samples(f->swr, f->frame->nb_samples);
    if (oc <= 0 || oc > MAX_SAMPLES_PER_FRAME) return -1;

    uint8_t *outbuf[] = { f->swrOut + 0, f->swrOut + oc * 2 };
    oc = swr_convert(f->swr, outbuf, oc,
                     (const uint8_t **)f->frame->extended_data, f->frame->nb_samples);
    if (oc <= 0) return -1;
    int bytes = av_samples_get_buffer_size(NULL, OUT_CHANNELS, oc, OUT_FMT, 1);
    if (bytes <= 0 || bytes > (int)sizeof(f->swrOut)) return -1;
    push_pcm(f, f->swrOut, (size_t)bytes);
    f->totalOutSamples += oc;
    if (f->frame->pts != AV_NOPTS_VALUE)
        f->lastPtsUs = av_rescale_q(f->frame->pts, f->codec->time_base, (AVRational){1, 1000000});
    else
        f->lastPtsUs = f->totalOutSamples * 1000000LL / OUT_SAMPLE_RATE;
    return 0;
}

/* ---------------- FFmpeg 管线（worker 线程） ---------------- */

static void *decode_loop(void *arg) {
    FfmpegCtx *f = (FfmpegCtx *)arg;
    int ret;

    f->fmt = avformat_alloc_context();
    if (!f->fmt) { f->decodeError = -1000; mark_done(f); return NULL; }
    uint8_t *avioBuf = (uint8_t *)av_malloc(AVIO_BUFFER);
    AVIOContext *io = avio_alloc_context(avioBuf, AVIO_BUFFER, 0, f, read_input, NULL, NULL);
    if (!io || !avioBuf) { f->decodeError = -1001; mark_done(f); return NULL; }
    f->fmt->pb = io;
    f->fmt->flags |= AVFMT_FLAG_CUSTOM_IO;

    ret = avformat_open_input(&f->fmt, "memory", NULL, NULL);
    LOGI("avformat_open_input ret=%d", ret);
    if (ret < 0) { LOGE("avformat_open_input failed: %d", ret); f->decodeError = ret; avformat_free_context(f->fmt); f->fmt = NULL; mark_done(f); return NULL; }

    ret = avformat_find_stream_info(f->fmt, NULL);
    LOGI("avformat_find_stream_info ret=%d", ret);
    if (ret < 0) { LOGE("find_stream_info failed: %d", ret); f->decodeError = ret; goto fail; }

    f->dec = NULL;
    f->streamIdx = av_find_best_stream(f->fmt, AVMEDIA_TYPE_AUDIO, -1, -1, &f->dec, 0);
    LOGI("av_find_best_stream idx=%d", f->streamIdx);
    if (f->streamIdx < 0 || !f->dec) { LOGE("no audio stream"); f->decodeError = -1002; goto fail; }

    AVStream *st = f->fmt->streams[f->streamIdx];
    f->codec = avcodec_alloc_context3(f->dec);
    if (!f->codec) { f->decodeError = -1003; goto fail; }
    ret = avcodec_parameters_to_context(f->codec, st->codecpar);
    if (ret < 0) { f->decodeError = ret; goto fail; }
    ret = avcodec_open2(f->codec, f->dec, NULL);
    if (ret < 0) { LOGE("avcodec_open2 failed: %d", ret); f->decodeError = ret; goto fail; }

    /* 重采样到固定 s16/2/44100（FFmpeg 6.x：swr_alloc_set_opts2 + AVChannelLayout） */
    f->swr = NULL;
    AVChannelLayout out_ch = AV_CHANNEL_LAYOUT_STEREO;
    AVChannelLayout in_ch;
    av_channel_layout_copy(&in_ch, &f->codec->ch_layout);
    ret = swr_alloc_set_opts2(&f->swr, &out_ch, OUT_FMT, OUT_SAMPLE_RATE,
                              &in_ch, f->codec->sample_fmt, f->codec->sample_rate, 0, NULL);
    av_channel_layout_uninit(&in_ch);
    if (ret < 0 || !f->swr) { f->decodeError = -1004; goto fail; }
    ret = swr_init(f->swr);
    if (ret < 0) { LOGE("swr_init failed: %d", ret); f->decodeError = ret; goto fail; }

    f->opened = 1;
    if (f->fmt->duration > 0) f->durationUs = f->fmt->duration;
    else if (st->duration > 0) f->durationUs = av_rescale_q(st->duration, st->time_base, (AVRational){1, 1000000});

    f->pkt = av_packet_alloc();
    f->frame = av_frame_alloc();

    /* 解码循环 */
    int nFrames = 0;
    for (;;) {
        if (f->stop) break;
        /* R-B2 背压：解码-ahead 达到高水位则阻塞等 render 抽走（pcmCond 由 nativeRender 抽走后 signal），
         * 把"整首瞬间解码"改为按真实消费节奏推进，pcmBuf 有界。 */
        pthread_mutex_lock(&f->pcmMutex);
        while (f->pcmLen > PCM_HIGH_WATERMARK && !f->stop)
            pthread_cond_wait(&f->pcmCond, &f->pcmMutex);
        pthread_mutex_unlock(&f->pcmMutex);
        if (f->stop) break;

        ret = av_read_frame(f->fmt, f->pkt);
        if (ret < 0) break;
        if (f->pkt->stream_index == f->streamIdx) {
            ret = avcodec_send_packet(f->codec, f->pkt);
            while (ret >= 0) {
                ret = avcodec_receive_frame(f->codec, f->frame);
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
                if (ret < 0) break;
                render_frame_to_pcm(f);
            }
        }
        av_packet_unref(f->pkt);
        if ((nFrames++ & 0x3f) == 0)
            LOGI("decoding... frame=%d pcmLen=%d", nFrames, (int)f->pcmLen);
    }

    /* flush 解码器尾部帧 */
    avcodec_send_packet(f->codec, NULL);
    while (avcodec_receive_frame(f->codec, f->frame) == 0)
        render_frame_to_pcm(f);
    f->decodeDone = 1;   /* EOF：有数据时仍可被 render 取完 */
    mark_done(f);
    return NULL;

fail:
    f->decodeError = f->decodeError != 0 ? f->decodeError : (ret < 0 ? ret : -9999);
    mark_done(f);
    return NULL;
}

/* ---------------- JNI ---------------- */

static FfmpegCtx *ctx_of(jlong j) {
    return (FfmpegCtx *)(intptr_t)j;
}

JNI_FUNC(jlong, nativeCreate)(JNIEnv *env, jclass thiz) {
    FfmpegCtx *f = (FfmpegCtx *)calloc(1, sizeof(FfmpegCtx));
    if (!f) return 0;
    pthread_mutex_init(&f->inMutex, NULL);
    pthread_cond_init(&f->inCond, NULL);
    pthread_mutex_init(&f->pcmMutex, NULL);
    pthread_cond_init(&f->pcmCond, NULL);
    if (pthread_create(&f->thread, NULL, decode_loop, f) != 0) {
        pthread_mutex_destroy(&f->inMutex);
        pthread_cond_destroy(&f->inCond);
        pthread_mutex_destroy(&f->pcmMutex);
        pthread_cond_destroy(&f->pcmCond);
        free(f);
        return 0;
    }
    f->threadStarted = 1;
    return (jlong)(intptr_t)f;
}

JNI_FUNC(jint, nativeFeed)(JNIEnv *env, jclass thiz, jlong ctx, jbyteArray data) {
    FfmpegCtx *f = ctx_of(ctx);
    if (!f || !data) return -1;
    jsize len = (*env)->GetArrayLength(env, data);
    if (len <= 0) return 0;
    jbyte *arr = (*env)->GetByteArrayElements(env, data, NULL);
    if (!arr) return -2;
    pthread_mutex_lock(&f->inMutex);
    /* R-B2 背压：输入缓冲已达上限 → 不追加并返回 1（忙），Java 侧暂停喂块（pendingChunk）。
     * 待 worker 消费（解码）腾出空间后重投。注意必须先释放 arr，避免泄漏。 */
    if (f->inLen + (size_t)len > INPUT_MAX_BYTES) {
        pthread_mutex_unlock(&f->inMutex);
        (*env)->ReleaseByteArrayElements(env, data, arr, JNI_ABORT);
        return 1;
    }
    if (f->inLen + (size_t)len > f->inCap) {
        size_t nc = f->inCap ? f->inCap : (size_t)len * 2;
        while (nc < f->inLen + (size_t)len) nc *= 2;
        f->inBuf = (uint8_t *)realloc(f->inBuf, nc);
        f->inCap = nc;
    }
    memcpy(f->inBuf + f->inLen, arr, (size_t)len);
    f->inLen += (size_t)len;
    pthread_cond_signal(&f->inCond);
    pthread_mutex_unlock(&f->inMutex);
    (*env)->ReleaseByteArrayElements(env, data, arr, JNI_ABORT);
    return 0;
}

JNI_FUNC(jint, nativeFeedEos)(JNIEnv *env, jclass thiz, jlong ctx) {
    FfmpegCtx *f = ctx_of(ctx);
    if (!f) return -1;
    pthread_mutex_lock(&f->inMutex);
    f->inEos = 1;
    pthread_cond_signal(&f->inCond);
    pthread_mutex_unlock(&f->inMutex);
    return 0;
}

JNI_FUNC(jint, nativeRender)(JNIEnv *env, jclass thiz, jlong ctx, jobject outBuf, jint size) {
    FfmpegCtx *f = ctx_of(ctx);
    if (!f) return -1;
    if (size <= 0) return 0;
    void *dst = (*env)->GetDirectBufferAddress(env, outBuf);
    if (!dst) return -2;

    pthread_mutex_lock(&f->pcmMutex);
    /* 若无 PCM 且解码未结束，返回<0 表示"暂无输出，稍后重试"（让调用方继续喂数据） */
    if (f->pcmLen == 0 && !f->decodeDone) {
        pthread_mutex_unlock(&f->pcmMutex);
        return -1;
    }
    if (f->pcmLen == 0 && f->decodeDone) {
        pthread_mutex_unlock(&f->pcmMutex);
        return 0;   /* EOF */
    }
    int n = (size < (int)f->pcmLen) ? size : (int)f->pcmLen;
    memcpy(dst, f->pcmBuf, (size_t)n);
    if ((size_t)n < f->pcmLen)
        memmove(f->pcmBuf, f->pcmBuf + n, f->pcmLen - (size_t)n);
    f->pcmLen -= (size_t)n;
    if (f->pcmLen <= PCM_HIGH_WATERMARK)
        pthread_cond_signal(&f->pcmCond);   /* R-B2：唤醒水位阻塞的 worker（解码按消费节奏推进） */
    pthread_mutex_unlock(&f->pcmMutex);
    return n;   /* 即使已 EOF，本次仍产出 n 字节；缓冲清空后下次调用返回 0 */
}

JNI_FUNC(jlong, nativeGetTimeMs)(JNIEnv *env, jclass thiz, jlong ctx) {
    FfmpegCtx *f = ctx_of(ctx);
    if (!f) return 0;
    return f->lastPtsUs / 1000;
}

JNI_FUNC(jlong, nativeGetDurationMs)(JNIEnv *env, jclass thiz, jlong ctx) {
    FfmpegCtx *f = ctx_of(ctx);
    if (!f) return -1;
    return f->durationUs < 0 ? -1 : f->durationUs / 1000;
}

JNI_FUNC(void, nativeRelease)(JNIEnv *env, jclass thiz, jlong ctx) {
    FfmpegCtx *f = ctx_of(ctx);
    if (!f) return;
    /* 结束 worker：置 inEos 唤醒读；置 stop 并 signal pcmCond 唤醒水位等待的 worker，join 才不挂起 */
    pthread_mutex_lock(&f->inMutex);
    f->inEos = 1;
    pthread_cond_signal(&f->inCond);
    pthread_mutex_unlock(&f->inMutex);
    pthread_mutex_lock(&f->pcmMutex);
    f->stop = 1;
    pthread_cond_signal(&f->pcmCond);
    pthread_mutex_unlock(&f->pcmMutex);
    if (f->threadStarted) {
        pthread_join(f->thread, NULL);
        f->threadStarted = 0;
    }
    if (f->pkt) av_packet_free(&f->pkt);
    if (f->frame) av_frame_free(&f->frame);
    if (f->codec) avcodec_free_context(&f->codec);
    if (f->swr) swr_free(&f->swr);
    if (f->fmt) avformat_free_context(f->fmt);
    free(f->inBuf);
    free(f->pcmBuf);
    pthread_mutex_destroy(&f->inMutex);
    pthread_cond_destroy(&f->inCond);
    pthread_mutex_destroy(&f->pcmMutex);
    pthread_cond_destroy(&f->pcmCond);
    free(f);
}