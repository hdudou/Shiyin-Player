# Shiyin – Multi-featured music player (Open Source Edition)

> **[中文说明（README 中文版）](./README.md)**

Shiyin is an Android music player for local + network music, with built-in ZeroTier virtual-network access and an internet radio. It organizes your library across three source types — local storage, WebDAV and SMB — and embeds ZeroTier's libzt user-space stack, so you can join a virtual LAN right inside the app (no system VPN permission needed) and reach music on a home NAS across networks.

This repository is a redacted open-source copy of the author's private build: it ships **no private network IDs, default music sources, or update servers**, and has neither auto-update nor over-the-air radio-station sync — every connection is configured by you. The UI is currently Chinese (with an English language pack).

---

## Feature overview

- **Full-featured library**: bottom navigation with Songs / Albums / Artists / Playlists / More; fixed tag order is Songs → Folders → Albums → Artists. Songs / albums / artists support multi-select, batch play, batch add-to-playlist, plus global search (Pinyin / initial-letter matching), and search results can be batch-selected to play or add to a playlist.
- **Multiple music sources**: local storage (media store + full-file access), WebDAV and SMB sources can be scanned into the library, with incremental / full update support; SMB supports "Browse LAN" auto-discovery (with manual entry fallback), asks for credentials (or anonymous access) when you enter a host, and shows an up-level navigation path.
- **ZeroTier virtual network**: join a ZeroTier network directly inside the app (libzt user-space, not a system VPN) to access WebDAV / SMB servers in the virtual LAN across networks. The network ID is entered by you.
- **Metadata scraping**: auto / manual sync of music and lyric metadata (album art, artist info, online lyrics) with configurable sources and priorities (NetEase Cloud / QQ Music / KuWo / Migu / Kugou). See the "Metadata Scraping" section.
- **Internet radio**: HLS / network-stream stations; a built-in station list categorized into Mainland / Hong Kong–Macau–Taiwan / Overseas (with multiple lines per station), auto line-switching, manual line selection, station search and custom stations.
- **Broad audio format support**: built on Media3 (ExoPlayer), covering mp3 / aac / ogg / wav / flac / opus / ac3 / dts / aiff / alac and more; FFmpeg soft-decoding (NDK) additionally supports lossless and rare formats such as ape / wma / wv / tta / tak / mpc / ofr / oma / dsf / dff.
- **Lyrics & visualization**: embedded-lyric reading, online lyric matching, desktop floating lyrics, car Bluetooth lyrics, and a lock-screen dynamic visualization overlay.
- **Playback control**: foreground service, media notification controls, wired / Bluetooth headset buttons, auto-pause on wired unplug, auto-pause on Bluetooth disconnect, audio-focus management, sleep timer and scheduled playback (a single unified "Timer" entry for both music and radio modes).
- **Sound engine**: equalizer with genre-based auto-EQ, volume normalization, ReplayGain, and gapless playback.
- **Data backup**: export / import player data (settings, library, playlists can be selected), optionally encrypted with AES-256-GCM.

> Screen casting (Cast / AirPlay / DLNA), version auto-update, built-in radio remote sync, and HTTP direct-link sources have been removed in the open-source edition and are no longer provided.

---

## Tech stack

| Category | Choice |
|---|---|
| Language / UI | Kotlin + Jetpack Compose (Material3) |
| DI | Hilt |
| DB / storage | Room, DataStore Preferences |
| Playback core | Media3 (ExoPlayer 1.4.1), incl. HLS |
| Soft decoding | FFmpeg 6.1 (NDK cross-compiled) + custom AVIO JNI, vendored media3 decoder_ffmpeg |
| Network sources | jcifs-ng (SMB), OkHttp hand-written PROPFIND (WebDAV) |
| Credential encryption | security-crypto (EncryptedSharedPreferences) |
| Virtual network | libzt (ZeroTier user-space) |
| Image loading | Coil |

Build config: AGP 8.5.2, Kotlin 1.9.24, Gradle 8.9, JDK 17, CMake 3.22, ABIs `arm64-v8a` & `armeabi-v7a`, `minSdk 24` (Android 7.0) / `targetSdk 34`.

---

## Directory structure

```
app/src/main/
├── java/com/shiyinplayer/
│   ├── player/                 # Playback core: PlaybackController, PlaybackService, PlayerManager
│   │   └── radio/              # Radio core (HLS, multi-line, timer/alarm)
│   ├── data/
│   │   ├── local/              # Room DAOs / entities
│   │   ├── media/              # Music-source scanning, playback-source factory, track parsing
│   │   ├── metadata/           # Metadata scraping: NetEase/QQ/KuWo/Migu/Kugou/Wikipedia and others
│   │   ├── metasync/           # Async metadata (lyrics/cover) matching & background sync service
│   │   ├── network/zerotier/   # ZeroTier integration (libzt node management, route mapping)
│   │   └── remote/webdav/      # WebDAV browsing / data source
│   └── ui/                     # Compose UI: Songs/Albums/Artists/Playlists/More/Radio/Settings/ZeroTier/Lock-screen
├── cpp/                        # FFmpeg JNI (ffmpeg_jni.c / ffmpeg_official_jni.cc) + CMake & prebuilt libs
├── jniLibs/                    # Prebuilt native libs (libzt.so, libav*.so, FLAC/Opus/Oboe and others)
└── assets/                     # Built-in radio station list, changelog
```

Native dependencies (FFmpeg libs, libzt and the various codecs' `.so`) are committed in the repo, so you can build directly without compiling NDK artifacts first. The FFmpeg build script lives in `scripts/`.

---

## Building

### Requirements

- JDK 17 (must include `jlink`, used by the `androidJdkImage` transform)
- Android SDK (`compileSdk 34`, `build-tools 34.x`)
- Gradle 8.9 (the repo ships a `gradlew` wrapper that downloads it automatically)

The repo builds via `gradlew` by default. Gradle dependency repositories are switched to the Aliyun mirror (see `settings.gradle.kts`); if you can reach Google Maven directly, feel free to restore `google() / mavenCentral()`.

### Steps

```bash
# Debug build (applicationId=com.shiyinplayer.debug)
./gradlew :app:assembleDebug

# Release build (applicationId=com.shiyinplayer)
./gradlew :app:assembleRelease

# Outputs
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

If `gradlew` fails to locate a JDK, set `JAVA_HOME` to your JDK 17 explicitly, or uncomment `org.gradle.java.home` in `gradle.properties` and fill in a local path.

### Signing note

The `release` build type currently **reuses the debug keystore as a placeholder** signature, only for easy local packaging. Before a real release, replace it with your own keystore in `app/build.gradle.kts` and provide `storeFile / storePassword / keyAlias / keyPassword` via `local.properties` (gitignored) or environment variables. Never commit private key files to the repository.

---

## Getting started

### 1. Install & first launch

Build an APK or install from a release channel. On first launch the app requests the permissions it needs; see "Runtime permissions" below.

### 2. Add music sources

All sources are managed in **More → Music Library → Music Library Sources**. Tap "Add source", choose a source type (SMB / WebDAV / Folder), then fill in the details:

- **Folder (local)**: no credentials. Tap "Browse folders" to pick a local directory via the system picker (grant "All files access" first). You can also type an absolute path such as `/storage/emulated/0/Music`.
- **WebDAV**: enter the server **address** (e.g. `https://dav.example.com/dav`) and, for restricted dirs, an optional **username / password**. Credentials are stored encrypted.
- **SMB**: enter an address like `smb://192.168.1.10/Music` plus credentials; more commonly, tap **"Browse LAN"** to auto-discover SMB hosts on the LAN:
  1. A scanning spinner is shown while the LAN is probed; afterwards discovered hosts are listed (if none found, it prompts you to enter a host IP manually);
  2. Tap a host; if credentials are needed, a **username / password (or anonymous access)** dialog appears;
  3. On success, drill into shares; the panel provides an **up-level** navigation;
  4. Tap **"Use this directory"** and its path plus the credentials you entered are back-filled into the add form.

After saving, run **Scan / Sync** on the source to import it. Scanning supports two modes: **new-only** (default — keeps existing tracks) or **full rescan** (overwrites by dedupe key, can repair entries whose address/credentials changed).

> Tip: if your SMB / WebDAV server is on the far side of the public internet, join ZeroTier first and use the virtual-LAN IP as the source address (see next).

### 3. Join ZeroTier (optional)

In **More → ZeroTier Virtual Network**, enter your Network ID and join, then approve the node in the ZeroTier console. After joining, you can use virtual-LAN IPs to access WebDAV / SMB on your NAS. The open-source build ships no default network — register a ZeroTier account and create one yourself.

### 4. Organize & play your library

- The **Songs / Folders / Albums / Artists** tags browse by dimension; the **Playlists** page manages playlists plus smart lists for Recently-Played / Most-Played / Shuffle.
- Tap a track once to play; long-press or enter select mode for **multi-select**, with batch Play-All / Add-to-Playlist at the bottom; the playlist page's top-right "⋮" creates lists, long-press a playlist to play / rename / delete.
- The top search box supports global search (incl. **Pinyin / initial-letter** matching); results can be selected individually or in a batch to play or add to a playlist.
- The now-playing page supports a queue, drag-to-reorder, gapless playback, speed, loop, and sound-quality settings.
- **Mode switching**: the "Play / Switch" button at the center of the bottom navigation enters the now-playing page on single tap (or starts playback), and on **double tap** toggles between **Music player ↔ Radio** modes; the play button in landscape left navigation behaves the same.

### 5. Listen to internet radio

Switch the app to radio mode: pick a built-in station (categorized into Mainland / Hong Kong–Macau–Taiwan / Overseas, with multiple lines), or search and customize stations / enter a stream URL manually. If a line stalls during playback, the app auto-switches to the next line; you can also change lines manually on the now-playing page.

### 6. Lyrics & visualization

- Lyrics prefer **embedded tags** in the file (UTF-8 / UTF-16 / GBK auto-detected); if missing, they are matched online and cached.
- Desktop floating lyrics (needs "display over other apps" permission) and car Bluetooth lyrics (the current line is shown on car devices) are supported.
- The lock screen provides a dynamic visualization overlay (needs audio-record permission); swipe up to exit.

### 7. Sound engine

In **More → Sound Engine Settings** you can enable the equalizer with genre-based auto-EQ, volume normalization, ReplayGain, silence removal, etc.; gapless playback is toggled under its own setting.

### 8. Data backup

In **More → Export / Import Player Data** you can export system settings (incl. network-source settings and stored credentials), library entries (incl. metadata) and playlists into a single data file; choose the scope to export, and optionally encrypt it (AES-256-GCM). Import requires confirming the types to overwrite; encrypted files need the password used at export time.

### Runtime permissions

All permissions are requested on first use, including:

| Permission | Purpose |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | Access local audio not in the media store (Android 11+ via the "All files access" special permission) |
| `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` | Read local media-store audio |
| `POST_NOTIFICATIONS` | Playback-control notification (Android 13+) |
| `BLUETOOTH_CONNECT` | Bluetooth headset media controls and car lyrics |
| `SYSTEM_ALERT_WINDOW` | Desktop floating lyrics |
| `RECORD_AUDIO` | Lock-screen visualization layer audio capture |

---

## Metadata scraping

The app enriches tracks with **title / artist / album / year / cover / lyrics** metadata through two paths:

### Automatic scraping

Configure and enable it in **More → Metadata Settings**:

- **Read embedded lyrics**: prefer lyrics embedded in the file tags (online lookup only fills the gaps).
- **Online metadata**: controls fetching album art, artist avatar and biography over the network.
- **Metadata sources**: choose which online sources to enable and their priority (NetEase Cloud / QQ Music / KuWo / Migu / Kugou and more). Multiple sources are tried in priority order, falling back automatically.
- **Auto-sync metadata**: when enabled, the app automatically batch-syncs missing metadata / lyrics for the library in the background, writing results to a local cache (with expiry, lazy-cleaned on read), with no manual work needed.
- **Data saver**: optionally skip network metadata / lyrics on cellular and only auto-sync on WiFi.

### Manual scraping

For tracks the auto-sync missed, or that need correcting:

- **Per-track**: from the track action menu (in the list or now-playing page), choose **"Find metadata online"** to match and fill it immediately from the enabled sources, or **"Edit metadata"** to hand-enter title / artist / album / year, etc. The list refreshes immediately after editing.
- **Whole library**: in **More → Metadata Settings → Batch-sync metadata**, trigger an immediate pass over all tracks with missing metadata across the whole library, using multi-threaded, rate-limited multi-source collection — handy right after importing a large batch.

> Note: metadata comes from embedded tags and public third-party APIs, whose copyright belongs to the respective providers; use it for personal library management only. Some sources are third-party services whose availability depends on their endpoints; the app does not guarantee continuous availability.

---

## Release history

### 2.1.4 (2026-09-15)

- **Fixed**: occasional random auto-pause while playing over Bluetooth earphones (flicker recovery — if the device is in fact still connected after a brief BT dropout, playback resumes automatically instead of stopping permanently when the reconnect event never fires).
- **Fixed**: when playing over Bluetooth, singling out USB/wired audio devices as they are momentarily enumerated then removed no longer causes a spurious "unplugged" pause.
- **Changed**: release builds are now shipped **un-minified** (R8 disabled). The APK is roughly 1.5× larger but runs more reliably and makes crash reports easier to trace — a good fit for current distribution.

### 2.1.3 (2026-09-12)

- First independent open-source release: detached from the private edition history, while keeping all open capabilities (multi-source library / full-format decoding / built-in radio / metadata scraping).

---

## Open-source license & compliance

This project is released under the **GNU General Public License v3.0 (GPL-3.0)**. The full text is in the **`LICENSE`** file at the repository root, and at <https://www.gnu.org/licenses/>.

> Choosing GPL-3.0 means: you may freely use, modify and redistribute the project as long as it stays under GPL-3.0; any modification and derivative work must **also be F/OSS under GPL-3.0 in full**, with the copyright notice preserved; this project comes with **no warranty**.

**Third-party component licenses** (each component copyright belongs to its respective owners):

| Component | Use | License |
|---|---|---|
| AndroidX Compose / Room / Hilt / Coroutines / DataStore | UI, persistence, DI, concurrency | Apache-2.0 |
| Media3 (ExoPlayer) | Playback core | Apache-2.0 |
| OkHttp / Retrofit / Coil | Networking & images | Apache-2.0 |
| jcifs-ng | SMB access | LGPL-2.1 |
| FFmpeg | Audio soft decoding | LGPL or GPL depending on build flags (this repo statically links `libav*.a`; the exact choice is up to your NDK build) |
| libzt (ZeroTier) | Virtual LAN | BSL-1.0 |

**Other notes**:

- **Open by construction**: this copy contains no private keys, plaintext credentials, intranet IPs or private update URLs. `local.properties` (local SDK path) and all `*.log` / build artifacts are excluded by `.gitignore` and never committed.
- **ZeroTier use**: you must register a ZeroTier account and create a network to obtain a Network ID; the open-source build ships no default network.
- **Data sources**: the built-in radio station list is sourced from the public internet, copyright belongs to the respective stations; metadata / lyrics come from embedded tags and public third-party APIs, owned by the respective providers. The project is provided for technical demonstration and personal use — please respect applicable licensing when redistributing.
- To connect the app to your own OTA distribution, you are welcome to extend it yourself (no update code ships in the open-source build).

---

## Acknowledgments

Thanks to FFmpeg, Media3, ZeroTier, jcifs-ng and all the open-source component authors, and to the public services / content providers that supply metadata and lyrics.