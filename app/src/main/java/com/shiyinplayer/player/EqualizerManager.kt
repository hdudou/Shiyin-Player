package com.shiyinplayer.player

import androidx.media3.common.audio.AudioProcessor
import com.shiyinplayer.player.equalizer.BalanceAudioProcessor
import com.shiyinplayer.player.equalizer.GraphicEqualizerAudioProcessor
import com.shiyinplayer.ui.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 均衡器管理（架构 §1.2.6）：持有 10 段 GraphicEqualizerAudioProcessor 与声道平衡，
 * 提供注入 ExoPlayer 的 AudioProcessor 列表、增益设置、预设持久化入口。
 * 主开关 eq_enabled 与增益/平衡由本类观察 DataStore 流即时桥接到处理器（运行时生效，无需重建播放器）。
 */
@Singleton
class EqualizerManager @Inject constructor(
    private val settings: SettingsRepository
) {
    private val processor = GraphicEqualizerAudioProcessor()
    /** F3-1：声道平衡处理器（始终在链上，运行时按 channel_balance 实时更新；居中时自动旁路）。 */
    private val balanceProcessor = BalanceAudioProcessor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // 主开关 eq_enabled：DataStore 任何写入（设置页/其他入口）即时反映到音频链。
        // processor.setEnabled 只翻转 @Volatile 标志，下一音频帧即旁路/恢复。
        scope.launch {
            settings.eqEnabled.collect { processor.setEnabled(it) }
        }
        // 均衡器增益持久化：冷启动 / 外部变更时自动恢复到处理器，避免「只有打开均衡器界面才生效」。
        // （GraphicEqualizerAudioProcessor 为 EqualizerManager 单例持有，热重建共享同一实例，
        //   增益随此流保持；按流派自动预设不写 DataStore，不会与本流干扰。）
        scope.launch {
            settings.eqGains.collect { g ->
                if (g.isNotEmpty()) processor.setGains(g)
            }
        }
        // F3-1：声道平衡实时生效（无需重建播放器；处理器 setBalance 只改 @Volatile 标志）
        scope.launch {
            settings.channelBalance.collect { balanceProcessor.setBalance(it) }
        }
    }

    /** 供 PlayerModule 构建 ExoPlayer 时注入音频链。 */
    fun getAudioProcessors(): Array<AudioProcessor> = arrayOf(processor, balanceProcessor)

    fun setBandGain(band: Int, gainDb: Float) = processor.setBandGain(band, gainDb)
    fun getBandGains(): FloatArray = processor.getGains()
    fun setBandGains(gains: FloatArray) = processor.setGains(gains)

    fun loadPreset(name: String) {
        PRESETS[name]?.let { processor.setGains(it) }
    }

    /** 按流派自动套用预设（auto_eq_by_genre）。
     *  匹配方案参考 AIMP AutoEQ / MusicBee / Discord Player / AutoEQ：
     *  流派标签直接匹配预设名，子流派归类到父类别，未匹配不套用（保持用户当前设置）。 */
    fun applyGenrePreset(genre: String?) {
        val g = genre?.trim()?.lowercase() ?: return
        val preset = when {
            // Rock 家族
            "rock|hard rock|punk|grunge|alternative|indie rock|post.punk|garage|emo|screamo".split("|").any { g.contains(it) } -> "Rock"
            // Metal 家族
            "metal|death|black|doom|thrash|speed|power|symphonic metal|nu metal|metalcore".split("|").any { g.contains(it) } -> "Metal"
            // Pop 家族
            "pop|synthpop|synth.pop|indie pop|dream pop|k.pop|j.pop|c.pop|europop".split("|").any { g.contains(it) } -> "Pop"
            // Hip-Hop 家族
            "hip.hop|rap|trap|drill|grime|boom bap|conscious rap|gangsta|horrorcore".split("|").any { g.contains(it) } -> "Hip-Hop"
            // Electronic 家族
            "electronic|edm|house|techno|trance|dubstep|drum.and.bass|dnb|dub|ambient|idm|deep house|progressive house|future bass|glitch".split("|").any { g.contains(it) } -> "Electronic"
            // Dance 家族
            "dance|disco|eurodance|hi.nrg|baile funk|kuduro".split("|").any { g.contains(it) } -> "Dance"
            // Jazz 家族
            "jazz|swing|bebop|cool jazz|fusion|smooth jazz|free jazz|latin jazz".split("|").any { g.contains(it) } -> "Jazz"
            // Classical 家族
            "classic|classical|orchestra|symphony|opera|chamber|baroque|romantic|concerto|sonata".split("|").any { g.contains(it) } -> "Classical"
            // Blues 家族
            "blues|delta blues|electric blues|Chicago blues|jump blues|rhythm and blues|r&b".split("|").any { g.contains(it) } -> "Blues"
            // Reggae 家族
            "reggae|ska|rocksteady|dancehall|lovers rock".split("|").any { g.contains(it) } -> "Reggae"
            // Latin 家族
            "latin|salsa|bachata|merengue|cumbia|reggaeton|latin pop|tango|bossa nova|samba".split("|").any { g.contains(it) } -> "Latin"
            // Acoustic / Folk 家族
            "acoustic|folk|singer.songwriter|indie folk|celtic|americana|country|bluegrass|western|honky tonk".split("|").any { g.contains(it) } -> "Acoustic"
            // Vocal 家族（人声为主）
            "vocal|a cappella|choir|choral|spoken word|audiobook|poetry".split("|").any { g.contains(it) } -> "Vocal"
            // Live / 现场
            "live|concert|bootleg|unplugged|live album".split("|").any { g.contains(it) } -> "Live"
            else -> return
        }
        loadPreset(preset)
    }

    fun release() = processor.flush()

    companion object {
        val BAND_FREQS: FloatArray = GraphicEqualizerAudioProcessor.BAND_FREQS
        const val NUM_BANDS = GraphicEqualizerAudioProcessor.NUM_BANDS
        /**
         * 10 段（31/62/125/250/500/1k/2k/4k/8k/16k）预设。
         * 增益曲线参考 AutoEQ / Discord Player / AIMP AutoEQ / Androxus EQ Guide 等主流方案。
         * 覆盖 18 个标准流派预设（业界主流播放器通用子集）。
         */
        val PRESETS: Map<String, FloatArray> = mapOf(
            "Flat"      to FloatArray(NUM_BANDS),
            "Rock"      to floatArrayOf( 4f,  3f,  1f,  0f, -1f,  0f,  1f,  3f,  4f,  3f),
            "Pop"       to floatArrayOf( 2f,  1f,  0f,  0f,  2f,  3f,  2f,  3f,  2f,  1f),
            "Jazz"      to floatArrayOf( 2f,  3f,  2f,  1f,  1f,  2f,  1f,  1f,  1f,  0f),
            "Classical" to floatArrayOf( 0f,  0f,  1f,  1f,  2f,  3f,  2f,  3f,  2f,  1f),
            "Blues"     to floatArrayOf( 4f,  3f,  2f,  1f,  0f,  1f,  2f,  3f,  3f,  3f),
            "Hip-Hop"   to floatArrayOf( 6f,  4f,  2f,  1f,  0f,  2f,  3f,  4f,  2f,  1f),
            "Electronic" to floatArrayOf( 6f,  4f,  3f,  2f,  0f,  2f,  4f,  6f,  5f,  3f),
            "Dance"     to floatArrayOf( 5f,  3f,  2f,  1f,  0f,  2f,  4f,  5f,  3f,  2f),
            "Metal"     to floatArrayOf( 5f,  4f,  3f,  1f,  1f,  1f,  0f, -1f, -2f, -3f),
            "Reggae"    to floatArrayOf( 4f,  3f,  2f,  0f, -1f,  0f,  2f,  3f,  3f,  2f),
            "Latin"     to floatArrayOf( 3f,  2f,  1f,  0f,  1f,  2f,  3f,  4f,  3f,  2f),
            "Acoustic"  to floatArrayOf( 1f,  1f,  2f,  2f,  3f,  3f,  2f,  2f,  1f,  0f),
            "Vocal"     to floatArrayOf(-2f, -2f, -1f,  0f,  2f,  4f,  5f,  3f,  1f,  0f),
            "Live"      to floatArrayOf( 5f,  4f,  3f,  1f,  0f,  0f,  0f,  2f,  3f,  4f),
            "Ska"       to floatArrayOf( 1f,  0f, -1f,  0f,  1f,  3f,  4f,  5f,  4f,  3f),
            "Soft Rock" to floatArrayOf( 3f,  2f,  1f,  0f,  1f,  2f,  3f,  3f,  2f,  1f)
        )
    }
}
