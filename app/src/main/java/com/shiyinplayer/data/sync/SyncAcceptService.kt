package com.shiyinplayer.data.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.shiyinplayer.R
import com.shiyinplayer.data.sync.model.SyncBatchStatus
import com.shiyinplayer.data.sync.model.SyncContract
import com.shiyinplayer.data.sync.model.SyncOp
import com.shiyinplayer.ui.MainActivity
import com.shiyinplayer.ui.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.NetworkInterface
import javax.inject.Inject

/**
 * 局域网同步接收端（PC 主控 / 安卓仅接收，契约 §2/§4）。
 *
 * - 端口固定 **23541**，TLS（`makeSecure`，优先 TLS 1.3 降级 1.2）
 * - **只绑私有网段 IP**：NanoHTTPD 的 `NanoHTTPD(hostname, port)` 会 `bind(InetSocketAddress(hostname, port))`，
 *   因此传入具体私有 IP 即可，不会监听 `0.0.0.0`（契约硬要求）
 * - 常态请求校验 `X-Device-Token`；`/sync/hello` 与 `/sync/pair` 免 token
 * - **PC 推来的变更直接落地（无确认）**：`/sync/push` 暂存后立刻返回 `needsConfirm:false`，
 *   由 [SyncAutoApplier] 在后台写库；PC 轮询 `/sync/confirm` 读到 `allowed` 即已落地（契约 §6）
 * - **服务随「允许局域网同步」开关启停（B 方案）**：开关关 = 服务停、23541 不再监听，PC 侧表现为「设备离线」。
 *   因此连得上就说明开关是开的，正常路径下 `/sync/hello` 的 `syncEnabled` 恒为 true。
 *   `syncEnabled:false` 与 `/sync/push` 的 403 `sync_disabled` 降级为**防御性兜底**，
 *   仅覆盖「开关刚被关、服务尚未退干净」的竞态窗口（不暂存、不弹窗）。
 * - **音频文件接收**：`POST /sync/push-file`（body 为裸二进制分块，元数据在请求头），
 *   落盘与续传语义全在 [SyncFileStore]；收齐一个文件立刻回写曲目播放地址。
 * - **mDNS 广播**：[SyncBroadcast] 与本服务同生共死，PC 侧「扫描设备」免手输 IP。
 *
 * 服务由设置页开关启停（前台交互场景启动，规避 Android 12+ 后台启动前台服务限制）。
 */
@AndroidEntryPoint
class SyncAcceptService : Service() {

    @Inject lateinit var auth: SyncAuth
    @Inject lateinit var ticketStore: SyncTicketStore
    @Inject lateinit var autoApplier: SyncAutoApplier
    @Inject lateinit var libraryReader: SyncLibraryReader
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var pairingStore: SyncPairingStore
    @Inject lateinit var fileStore: SyncFileStore
    @Inject lateinit var broadcast: SyncBroadcast
    @Inject lateinit var applyEngine: SyncApplyEngine

    private var server: SyncHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠️ Android 硬契约：只要系统是以 startForegroundService() 拉起本服务，就必须在 5s 内调用
        // startForeground()，**stopSelf() 不能豁免**——不调用就抛
        // ForegroundServiceDidNotStartInTimeException 把整个 App 进程打崩并重启。
        // 真机实测（2026-09-15 ACE7V）：开关打开后因证书解析失败直接 stopSelf，App 当场崩。
        // 因此这里先行无条件前台化（文案先写「正在启动监听…」），再按开关/启动结果决定去留。
        val foregrounded = try {
            ServiceCompat.startForeground(
                this, NOTIF_ID_FOREGROUND,
                buildForegroundNotification(getString(R.string.lan_sync_listen_starting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            true
        } catch (e: Exception) {
            // Android 12+ 后台启动 FGS 受限（例如系统按 START_STICKY 重启本服务）→ 放弃本次启动
            Log.w(TAG, "前台化失败，放弃本次启动：${e.message}")
            false
        }

        if (!settings.lanSyncEnabledSync()) {
            Log.i(TAG, "同步开关已关闭，服务不启动")
            stopSelf()
            return START_NOT_STICKY
        }

        startServer()
        if (server == null || !foregrounded) {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }

        // 监听地址已知 → 把常驻通知文案从「正在启动」换成实际地址（同 ID 更新，仍保持前台态）
        runCatching {
            NotificationManagerCompat.from(this).notify(
                NOTIF_ID_FOREGROUND,
                buildForegroundNotification(
                    getString(R.string.lan_sync_notify_running_text, boundAddress ?: "", SyncContract.PORT)
                )
            )
        }

        // 恢复落盘批次并在后台补落地（含文件 I/O 与写库，不占主线程；无用户介入）
        autoApplier.recoverAndApply()

        // mDNS 广播（契约 §8）：与 23541 监听同生共死，PC 侧「扫描设备」靠它免手输 IP。
        // 任何失败都只记日志，绝不影响接收主功能（PC 仍可手输 IP 直连）。
        broadcast.start(SyncContract.PORT, auth.deviceId, auth.deviceName())

        // 常驻：开关是唯一启停依据；被系统回收后重启时会在上面重新校验开关
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        broadcast.stop()
        stopServer()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIF_ID_FOREGROUND) }
        super.onDestroy()
    }

    // ------------------------------------------------------------ 服务端

    private fun startServer() {
        if (server != null) return
        val bindAddress = pickPrivateAddress()
        if (bindAddress == null) {
            Log.w(TAG, "找不到可用私有网段地址（未连 WLAN / 虚拟网？），同步服务不启动")
            lastError = "no_private_address"
            return
        }
        try {
            server = SyncHttpServer(bindAddress).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            boundAddress = bindAddress
            lastError = null
            Log.i(TAG, "局域网同步已监听 https://$bindAddress:${SyncContract.PORT}")
        } catch (e: Exception) {
            lastError = e.message ?: "start_failed"
            boundAddress = null
            server = null
            Log.w(TAG, "同步服务启动失败：${e.message}")
        }
    }

    private fun stopServer() {
        runCatching { server?.stop() }
        server = null
        boundAddress = null
    }

    /**
     * 选取绑定地址：优先 WLAN，其次以太网/热点，最后 ZeroTier 等其它私有网段；
     * 只接受「站点本地地址」（192.168/10./172.16-31），公网地址一律不绑（契约 §2）。
     */
    private fun pickPrivateAddress(): String? {
        val candidates = ArrayList<Pair<Int, String>>() // 优先级 to 地址
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces() ?: return@runCatching) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name?.lowercase() ?: ""
                val priority = when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth") || name.startsWith("ap") || name.startsWith("swlan") -> 1
                    name.startsWith("tun") || name.startsWith("zt") -> 3
                    else -> 2
                }
                for (addr in nif.inetAddresses) {
                    val ip = addr.hostAddress ?: continue
                    if (addr is java.net.Inet4Address && (addr.isSiteLocalAddress || addr.isLinkLocalAddress)) {
                        candidates.add(priority to ip)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "枚举网卡失败：${it.message}") }
        return candidates.minByOrNull { it.first }?.second
    }

    // ------------------------------------------------------------ 路由

    private inner class SyncHttpServer(bindAddress: String) :
        NanoHTTPD(bindAddress, SyncContract.PORT) {

        init {
            makeSecure(auth.sslServerSocketFactory(), TLS_PROTOCOLS)
        }

        override fun serve(session: IHTTPSession): Response {
            val remote = session.remoteIpAddress?.removePrefix("/") ?: ""
            if (!isPrivateClient(remote)) {
                Log.w(TAG, "拒绝非私有网段请求：$remote")
                return json(Response.Status.FORBIDDEN, error("not_private_network"))
            }

            val uri = session.uri ?: "/"
            return try {
                route(session, uri, remote)
            } catch (e: Exception) {
                Log.w(TAG, "处理 $uri 失败：${e.message}", e)
                json(Response.Status.INTERNAL_ERROR, error("internal_error"))
            }
        }

        private fun route(session: IHTTPSession, uri: String, remote: String): Response {
            // 免 token 的两个端点（契约 §3）：先握手拿 deviceId，再凭 6 位码配对
            if (uri == PATH_HELLO && session.method == Method.GET) return handleHello()
            if (uri == PATH_PAIR && session.method == Method.POST) return handlePair(session, remote)

            val paired = auth.verifyToken(headerToken(session))
            if (paired == null) return json(Response.Status.FORBIDDEN, error("invalid_token"))
            val deviceName = paired.deviceName.ifBlank { "PC ($remote)" }

            return when {
                uri == PATH_PUSH && session.method == Method.POST ->
                    handlePush(session, deviceName, paired.token)
                uri == PATH_PUSH_FILE && session.method == Method.POST -> handlePushFile(session)
                uri == PATH_CONFIRM && session.method == Method.GET -> handleConfirm(session)
                uri == PATH_RESULT && session.method == Method.GET -> handleResult(session)
                uri == PATH_STATUS && session.method == Method.GET -> handleStatus()
                uri == PATH_LIBRARY && session.method == Method.GET -> handleLibrary(session, paired.token)
                else -> json(Response.Status.NOT_FOUND, error("unknown_endpoint"))
            }
        }

        // -------- /sync/hello：免 token。开关关 = 服务不监听（PC 连不上），能走到这里即说明开关为开；
        //          syncEnabled 仅作防御性兜底（覆盖开关刚被关的竞态），非「同步已关闭」的常规路径 --------

        private fun handleHello(): Response = json(
            Response.Status.OK,
            JSONObject()
                .put("schemaVersion", SyncContract.SCHEMA_VERSION)
                .put("deviceId", auth.deviceId)
                .put("deviceName", auth.deviceName())
                .put("syncEnabled", auth.isSyncEnabled())
                .put("supportsFileOps", true)
        )

        // -------- /sync/pair --------

        private fun handlePair(session: IHTTPSession, remote: String): Response {
            if (!auth.isSyncEnabled()) return json(Response.Status.FORBIDDEN, error("sync_disabled"))

            val body = readJsonBody(session)
            // 用单参 optString（缺键返回 ""）再 takeIf 归一为 null：双参重载的 fallback 是非空 String，
            // 传 null 会让表达式推断成 Nothing? 而丢失可空语义（历史编译告警的来源）。
            val pin = body?.optString("pin")?.takeIf { it.isNotBlank() }
            if (!auth.validatePin(pin)) {
                Log.w(TAG, "配对码校验失败（来自 $remote）")
                return json(Response.Status.FORBIDDEN, error("invalid_pin"))
            }

            val token = auth.issueToken()
            val existing = pairingStore.getAll().firstOrNull { it.deviceId == pairKey(remote) }
            val device = PairedDevice(
                deviceId = pairKey(remote),
                // PC 当前不发送自身名称 → 用来源地址兜底展示；将来若带 deviceName 字段则优先采用
                deviceName = body?.optString("deviceName")?.takeIf { it.isNotBlank() }
                    ?: existing?.deviceName ?: "PC ($remote)",
                token = token,
                pinnedCert = existing?.pinnedCert,
                pairedAt = System.currentTimeMillis()
            )
            pairingStore.savePaired(device)
            // 一次性使用：连同落盘值一起清掉（否则同一 PIN 在时效内可反复换 token）
            runBlocking { auth.consumePin() }

            return json(
                Response.Status.OK,
                JSONObject()
                    .put("deviceToken", token)
                    .put("cert", auth.serverCertPem())
            )
        }

        // -------- /sync/push --------

        private fun handlePush(session: IHTTPSession, deviceName: String, deviceToken: String?): Response {
            // 开关关闭 → 直接 403 sync_disabled：不暂存、不落地（契约 §6）
            if (!auth.isSyncEnabled()) return json(Response.Status.FORBIDDEN, error("sync_disabled"))

            val body = readJsonBody(session) ?: return json(Response.Status.BAD_REQUEST, error("invalid_json"))
            val opsArray: JSONArray = body.optJSONArray("ops")
                ?: return json(Response.Status.BAD_REQUEST, error("missing_ops"))
            if (opsArray.length() == 0) return json(Response.Status.BAD_REQUEST, error("empty_ops"))
            if (opsArray.length() > MAX_OPS_PER_BATCH) {
                return json(Response.Status.BAD_REQUEST, error("too_many_ops"))
            }

            val ops: List<SyncOp> = SyncOp.parseList(opsArray)
            val batch = ticketStore.stash(ops, deviceName)
            // 无需本机确认：立即在后台落地；PC 轮询 /sync/confirm 读到 allowed 即表示已写入曲库
            // deviceToken 用于解 music_source 的凭据信封（口令 = 发起方 token）
            autoApplier.applyAsync(batch.ticketId, deviceToken)
            Log.i(TAG, "收到批次 ${batch.ticketId}（${ops.size} ops，来自 $deviceName），开始落地")

            return json(
                Response.Status.OK,
                JSONObject().put("ticketId", batch.ticketId).put("needsConfirm", false)
            )
        }

        // -------- /sync/push-file（契约 §4 两处非 JSON 例外之一：body 为原始二进制分块） --------

        /**
         * 音频文件分块接收。
         *
         * 与其它端点最大的不同：**body 是裸字节，不能用 `parseBody`**——NanoHTTPD 会把非表单体
         * 按字符集解码成 String（`postData`），二进制内容当场损坏。这里直接从 `inputStream`
         * 按 `Content-Length` 读原始字节。
         *
         * 响应体固定 `{received, completed}`；`received` **必须是服务端累计已落盘字节数**
         * （不是本片长度），PC 依它 seek 续推（`SyncPeerClient.PushFileAsync`）。
         * 因此「offset 与已落盘进度不符」不是错误，而是正常回报进度后 200；只有真正的
         * 路径越权 / 超限 / 写盘失败才返回非 2xx（PC 会据此把该变更记为失败并可重推）。
         */
        private fun handlePushFile(session: IHTTPSession): Response {
            if (!auth.isSyncEnabled()) return json(Response.Status.FORBIDDEN, error("sync_disabled"))

            val headers = session.headers ?: emptyMap()
            fun header(name: String): String? = headers[name.lowercase()]

            val dedupKey = header(SyncContract.HEADER_FILE_DEDUP_KEY)
            val relPath = header(SyncContract.HEADER_FILE_REL_PATH)
            val offset = header(SyncContract.HEADER_FILE_OFFSET)?.toLongOrNull()
            val total = header(SyncContract.HEADER_FILE_TOTAL)?.toLongOrNull()
            if (dedupKey.isNullOrBlank() || relPath.isNullOrBlank() || offset == null || total == null) {
                return json(Response.Status.BAD_REQUEST, error("missing_file_headers"))
            }

            // Content-Length 是不可信输入：先设闸再读，避免一条请求把内存撑爆
            val length = header("content-length")?.trim()?.toLongOrNull()
            if (length == null || length <= 0) {
                return json(Response.Status.BAD_REQUEST, error("missing_content_length"))
            }
            if (length > SyncContract.MAX_UPLOAD_CHUNK_BYTES) {
                Log.w(TAG, "分块超限：$length 字节（上限 ${SyncContract.MAX_UPLOAD_CHUNK_BYTES}）")
                return json(Response.Status.BAD_REQUEST, error("chunk_too_large"))
            }

            val bytes = readRawBody(session, length.toInt())
                ?: return json(Response.Status.BAD_REQUEST, error("body_incomplete"))

            val result = fileStore.receiveChunk(relPath, offset, total, bytes)
            if (result.error != null) {
                return json(
                    Response.Status.BAD_REQUEST,
                    JSONObject().put("received", result.received).put("error", result.error)
                )
            }

            // 收齐一个文件 → 回写曲目播放地址（PC 顺序是 ops 先、文件后，此时曲目行已存在但
            // 指向 PC 的原始 path，安卓读不到；不回写就表现为「曲目在、点播放失败」）
            if (result.completed) {
                val file = fileStore.resolve(relPath)
                if (file != null) {
                    runCatching { runBlocking { applyEngine.relinkSongFile(dedupKey, file) } }
                        .onFailure { Log.w(TAG, "文件落地回写失败：${it.message}") }
                }
            }

            return json(
                Response.Status.OK,
                JSONObject().put("received", result.received).put("completed", result.completed)
            )
        }

        /** 按 `Content-Length` 读满原始字节；提前 EOF 返回 null（宁可失败也不落半截数据）。 */
        private fun readRawBody(session: IHTTPSession, length: Int): ByteArray? {
            val input = session.inputStream ?: return null
            val buffer = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buffer, read, length - read)
                if (n <= 0) {
                    Log.w(TAG, "请求体提前结束：期望 $length 字节，实收 $read")
                    return null
                }
                read += n
            }
            return buffer
        }

        // -------- /sync/confirm /result /status --------

        private fun handleConfirm(session: IHTTPSession): Response {
            // NanoHTTPD 的 parms 是「查询参数名 → 单值」的 Map
            val ticket = session.parms?.get("ticket")
                ?: return json(Response.Status.BAD_REQUEST, error("missing_ticket"))
            val batch = ticketStore.get(ticket)
                ?: return json(Response.Status.NOT_FOUND, error("unknown_ticket"))
            return json(Response.Status.OK, JSONObject().put("status", batch.status))
        }

        private fun handleResult(session: IHTTPSession): Response {
            val ticket = session.parms?.get("ticket")
                ?: return json(Response.Status.BAD_REQUEST, error("missing_ticket"))
            val batch = ticketStore.get(ticket)
                ?: return json(Response.Status.NOT_FOUND, error("unknown_ticket"))
            if (batch.status == SyncBatchStatus.PENDING) {
                // 结果尚未产生：返回空数组，PC 的 resultByIndex 缺失项按 ok=true 处理，不会误判失败
                return json(Response.Status.OK, JSONObject().put("results", JSONArray()))
            }
            val results = JSONArray().also { arr -> batch.results.forEach { arr.put(it.toJson()) } }
            return json(Response.Status.OK, JSONObject().put("results", results))
        }

        private fun handleStatus(): Response = json(
            Response.Status.OK,
            JSONObject()
                .put("connected", true)
                .put("lastSyncAt", settings.lanSyncLastSyncAtSync())
                .put("pendingBatches", ticketStore.pendingCount())
        )

        // -------- /sync/library（只读快照，分页） --------

        /**
         * 只读快照。`credsToken` = 请求方 token（本端点已过 [SyncAuth.verifyToken]），
         * 作为音乐源凭据信封口令下发——**不发凭据 PC 就访问不了源地址**（WebDAV/SMB 需鉴权）。
         */
        private fun handleLibrary(session: IHTTPSession, credsToken: String?): Response {
            // types 参数按契约可忽略（PC 当前不传），此处仅记录以便排查
            val cursor = session.parms?.get("cursor")
            val page = runBlocking { libraryReader.readPage(cursor, credsToken) }
            return json(Response.Status.OK, page.toJson())
        }

        // -------- 工具 --------

        private fun headerToken(session: IHTTPSession): String? {
            // NanoHTTPD 会把请求头名统一转小写
            val headers = session.headers ?: return null
            return headers[SyncContract.HEADER_DEVICE_TOKEN.lowercase()]
                ?: headers.entries.firstOrNull {
                    it.key.equals(SyncContract.HEADER_DEVICE_TOKEN, ignoreCase = true)
                }?.value
        }

        private fun readJsonBody(session: IHTTPSession): JSONObject? {
            val map = HashMap<String, String>()
            return try {
                // application/json 不是表单类型，NanoHTTPD 把原始体放在 "postData"
                session.parseBody(map)
                val raw = map["postData"] ?: return null
                if (raw.isBlank()) null else JSONObject(raw)
            } catch (e: Exception) {
                Log.w(TAG, "解析请求体失败：${e.message}")
                null
            }
        }

        private fun json(status: Response.Status, body: JSONObject): Response =
            NanoHTTPD.newFixedLengthResponse(status, MIME_JSON, body.toString())

        private fun error(code: String): JSONObject = JSONObject().put("error", code)
    }

    // ------------------------------------------------------------ 通知

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.lan_sync_channel_name), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.lan_sync_channel_desc)
            setShowBadge(false)
        }
        runCatching { NotificationManagerCompat.from(this).createNotificationChannel(channel) }
    }

    /** 常驻通知。文案由调用方给：启动中显示「正在启动监听…」，起来后显示真实 `ip:port`。 */
    private fun buildForegroundNotification(text: CharSequence): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.lan_sync_notify_running_title))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val TAG = "SyncAcceptService"
        private const val CHANNEL_ID = "lan_sync_channel"
        private const val NOTIF_ID_FOREGROUND = 2010
        private const val MIME_JSON = "application/json; charset=utf-8"
        private const val MAX_OPS_PER_BATCH = 5000

        private const val PATH_HELLO = "/sync/hello"
        private const val PATH_PAIR = "/sync/pair"
        private const val PATH_PUSH = "/sync/push"
        private const val PATH_PUSH_FILE = "/sync/push-file"
        private const val PATH_CONFIRM = "/sync/confirm"
        private const val PATH_RESULT = "/sync/result"
        private const val PATH_STATUS = "/sync/status"
        private const val PATH_LIBRARY = "/sync/library"

        /** 优先 TLS 1.3；minSdk 24 设备（无 1.3 支持）由 NanoHTTPD 回退到可用协议。 */
        private val TLS_PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")

        /** 最近一次启动的实际监听地址（设置页展示用）。 */
        @Volatile
        var boundAddress: String? = null
            private set

        /** 最近一次启动失败原因（设置页展示用）。 */
        @Volatile
        var lastError: String? = null
            private set

        /** PC 目前不发送自身 deviceId，用来源地址作为配对记录的本地键。 */
        private fun pairKey(remoteIp: String): String = "pc-$remoteIp"

        fun isPrivateClient(ip: String): Boolean = runCatching {
            if (ip.isBlank()) return@runCatching false
            val addr = InetAddress.getByName(ip)
            addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress
        }.getOrDefault(false)

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, SyncAcceptService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SyncAcceptService::class.java)) }
        }
    }
}
