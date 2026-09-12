# 拾音 · 多功能音乐播放器（开源版）

拾音是一款面向安卓的本地 + 网络音乐播放器，内置 ZeroTier 虚拟网接入与网络收音机。应用通过本地、WebDAV、SMB 三类音乐源组织曲库，并把 ZeroTier 的 libzt 用户态栈打包进应用，无需系统 VPN 权限即可在播放器内加入虚拟局域网，跨网络访问家中 NAS 的音乐。

本仓库是从作者自用版脱敏后的开源副本：**不内嵌任何私有网络 ID、默认音乐源或更新服务器**，也没有版本自动更新与内置电台远程同步，所有接入均由使用者自助配置。当前界面为中文。

---

## 功能概览

- **完整曲库**：底部导航包含 歌曲 / 专辑 / 艺术家 / 播放列表 / 更多，支持歌曲多选、批量播放、批量加入歌单、全局搜索（含拼音 / 首字母匹配）。
- **多音乐源**：本地存储（媒体库 + 全盘文件访问）、WebDAV、SMB、HTTP 直链均可作为音乐源，扫描入库并支持增量 / 全量更新。
- **ZeroTier 虚拟网**：应用内直接加入 ZeroTier 网络（libzt 用户态，非系统 VPN），跨公网访问虚拟网内 WebDAV / SMB 服务器。网络 ID 需自行填写。
- **网络收音机**：HLS / 网络流电台，内置电台清单（含大陆 / 港澳台 / 海外分类与多线路），支持线路自动切换与手动选择、电台搜索与自定义电台。
- **音频格式广**：基于 Media3（ExoPlayer），覆盖 mp3 / aac / ogg / wav / flac / opus / ac3 / dts / aiff / alac 等常见格式；FFmpeg 软解（NDK）进一步支持 ape / wma / wv / tta / tak / mpc / ofr / oma 等无损与罕见格式。
- **歌词与可视化**：内嵌歌词读取、在线歌词匹配（网易云 / QQ 音乐 / 酷我 / 咪咕 / 酷狗）、桌面歌词、车载蓝牙歌词、锁屏动态可视化叠加层。
- **播放控制**：后台前台服务播放、通知栏媒体控制、有线 / 蓝牙耳机按键、蓝牙断开自动暂停、音频焦点管理、睡眠定时与定时播放（音乐与收音机统一入口）。
- **声音引擎**：均衡器与按流派自动 EQ、音量归一化、回放增益（ReplayGain）、无缝播放。
- **数据备份**：播放器数据导出 / 导入（系统设置、曲库、歌单可选），可选用 AES-256-GCM 加密保护。

> 投屏（Cast / AirPlay / DLNA）、版本更新、内置电台远程同步等功能在开源版中已移除，不再提供。

---

## 技术栈

| 类别 | 选型 |
|---|---|
| 语言 / UI | Kotlin + Jetpack Compose（Material3） |
| 依赖注入 | Hilt |
| 数据库 / 存储 | Room、DataStore Preferences |
| 播放内核 | Media3（ExoPlayer 1.4.1），含 HLS |
| 软解 | FFmpeg 6.1（NDK 交叉编译）+ 自研 AVIO JNI、vendored media3 decoder_ffmpeg |
| 网络源 | jcifs-ng（SMB）、OkHttp 手写 PROPFIND（WebDAV） |
| 凭据加密 | security-crypto（EncryptedSharedPreferences） |
| 虚拟网 | libzt（ZeroTier 用户态） |
| 图片加载 | Coil |

构建配置：AGP 8.5.2、Kotlin 1.9.24、Gradle 8.9、JDK 17、CMake 3.22、支持 ABI `arm64-v8a` 与 `armeabi-v7a`，`minSdk 24`（Android 7.0）/ `targetSdk 34`。

---

## 目录结构

```
app/src/main/
├── java/com/shiyinplayer/
│   ├── player/                 # 播放内核：PlaybackController、PlaybackService、PlayerManager
│   │   └── radio/              # 收音机内核（HLS、多线路、定时/闹钟）
│   ├── data/
│   │   ├── local/              # Room DAO / 实体
│   │   ├── media/              # 音乐源扫描、播放源工厂、曲目解析
│   │   ├── network/zerotier/   # ZeroTier 接入（libzt 节点管理、路由映射）
│   │   ├── remote/webdav/      # WebDAV 浏览 / 数据源
│   │   └── metasync/           # 元数据（歌词/封面）异步匹配服务
│   └── ui/                     # Compose 界面：歌曲/专辑/艺术家/歌单/更多/收音机/设置/ZeroTier/锁屏
├── cpp/                        # FFmpeg JNI（ffmpeg_jni.c / ffmpeg_official_jni.cc）+ CMake 与预编译库
├── jniLibs/                    # 预编译原生库（libzt.so、libav*.so、FLAC/Opus/Oboe 等）
└── assets/                     # 内置电台清单、更新日志
```

原生依赖（FFmpeg 库、libzt 与各编解码 `.so`）已内置在仓库中，可直接构建，无需预先编译 NDK 产物。FFmpeg 自构建脚本见 `scripts/`。

---

## 构建

### 环境要求

- JDK 17（需含 `jlink`，供构建插件的 `androidJdkImage` 变换使用）
- Android SDK（`compileSdk 34`，`build-tools 34.x`）
- Gradle 8.9（仓库已带 `gradlew` wrapper，自动下载）

仓库默认通过 `gradlew` 构建。Gradle 依赖仓库已切为阿里云镜像（见 `settings.gradle.kts`），如你的网络可直连 Google Maven，可自行改回 `google() / mavenCentral()`。

### 步骤

```bash
# 调试包（applicationId=com.shiyinplayer.debug）
./gradlew :app:assembleDebug

# 发布包（applicationId=com.shiyinplayer）
./gradlew :app:assembleRelease

# 产物
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

若 `gradlew` 因 JDK 定位失败，显式指定 `JAVA_HOME` 指向你的 JDK 17，或在 `gradle.properties` 中取消 `org.gradle.java.home` 注释并填入本地路径。

### 签名注意

`release` 构建类型当前**占位复用 debug keystore** 签名，仅便于本地直接打包调试。正式发布前请在 `app/build.gradle.kts` 中替换为自有 keystore，并在 `local.properties`（已 gitignore）或环境变量中配置 `storeFile / storePassword / keyAlias / keyPassword`。严禁将私钥文件提交进仓库。

---

## 使用入门

1. **安装**：构建或在发布渠道下载 APK 安装。首次启动会请求媒体访问权限。
2. **添加音乐源**：进入「更多 → 音乐库 → 音乐库来源管理」，可添加
   - 本地：授权后扫描设备存储中的音频；
   - WebDAV / SMB：填写服务器地址、共享目录与账号密码（凭据经加密存储）；
   - 若服务器在公网后端，可通过 ZeroTier 虚拟网访问。
3. **接入 ZeroTier（可选）**：在「更多 → ZeroTier 虚拟网络」填入你的 Network ID 并加入，再到 ZeroTier 控制台批准该节点。这是跨网络访问 NAS 音乐的前提。
4. **开始播放**：在曲库选择单曲 / 多选批量播放，或加入歌单；收听电台可在收音机模式选择内置或自定义电台。

### 运行时权限

应用所需的权限均按其用途在首次用到时申请，主要包括：

| 权限 | 用途 |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | 访问未入媒体库的本地音频（Android 11+ 走「所有文件访问」特殊权限） |
| `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` | 读取本地媒体库音频 |
| `POST_NOTIFICATIONS` | 播放控制通知（Android 13+） |
| `BLUETOOTH_CONNECT` | 蓝牙耳机媒体控制与车载歌词 |
| `SYSTEM_ALERT_WINDOW` | 桌面歌词悬浮窗 |
| `RECORD_AUDIO` | 锁屏可视化层音频捕获 |

---

## 开源说明

- **导入即属公开**：本副本不含任何私钥、明文凭据、内网 IP 或私有更新地址。`local.properties`（本地 SDK 路径）与所有 `*.log`、构建产物均已通过 `.gitignore` 排除，不会被提交。
- **ZeroTier 使用**：需自行注册 ZeroTier 账号并创建网络获取 Network ID，开源版不提供默认网络。
- **合规**：FFmpeg 以独立 `.so` 动态链接方式分发以最小化 LGPL 义务；内置电台清单数据来源于公开网络，版权归各电台所有。
- 若需将应用接入自建更新的分发链路，可在本基础上自行扩展（开源版未内置更新代码）。