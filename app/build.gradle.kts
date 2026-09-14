plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

import java.util.Properties

// ===== 版本号 =====
// buildCode 仅由发版脚本 publish.ps1 在「用户明确指定发版」时递增并写回本文件；
// 普通编译/调试构建（assemble / bundle / installDebug 等）一律不得更新版本号
// （此前的「每次构建自增」会使 buildCode 被普通调试构建推高，导致发版跳号）。
val versionPropsFile = file("version.properties")
val versionProps = Properties()
try { versionPropsFile.inputStream().use { versionProps.load(it) } } catch (_: Exception) {}
val buildCode = (versionProps.getProperty("buildCode")?.toIntOrNull() ?: 0)
val appVersionCode = buildCode.coerceAtLeast(1)
// 发版脚本 publish.ps1 可写入可选 versionName；缺省时代码回退为 1.0.<buildCode>
val propsVersionName = versionProps.getProperty("versionName")?.trim()
val appVersionName = if (propsVersionName.isNullOrEmpty()) "1.0.$buildCode" else propsVersionName

android {
    namespace = "com.shiyinplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shiyinplayer"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // v2.1：libzt 仅打包两 ABI，控体积
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs") // 放置 libzt.so（v2.1）

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // 占位签名：开发期复用 debug keystore 以便直接打包；正式发布前请替换为自有 keystore
            // （在 local.properties / 环境变量中配置 storeFile / storePassword / keyAlias / keyPassword）。
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // jcifs-ng 在 minSdk 24 上使用 java.nio，开启核心库脱糖
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // ffmpeg 可执行文件以 libffmpeg.so 打包，需真实解压到 nativeLibraryDir 供子进程执行
            // （Android W^X：untrusted_app 不能执行私有可写目录文件，nativeLibraryDir 只读可执行）
            useLegacyPackaging = true
        }
    }
}

// P0-2：Room schema 导出目录（exportSchema=true 的 schema JSON 写入 app/schemas），
// 供 CI 校验迁移链与实体一致性（防止 schema 漂移后静默 destructive 清库）。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // ===== 核心 KTX =====
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.2")

    // ===== Compose =====
    implementation(platform("androidx.compose:compose-bom:2024.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ===== Navigation =====
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // ===== Media3（ExoPlayer 播放内核） =====
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-decoder:$media3")       // 官方 FfmpegAudioRenderer/Decoder 的接口模块（vendored 源码依赖）
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")  // HLS 流播放支持（收音机）
    // 官方 soft-codec 模块（media3-decoder-{ffmpeg,flac,...}）在 Maven/官方仓库均无可下载 AAR，
    // 需将 media3 源码中的 decoder_ffmpeg 类 vendored 进本工程 + 用预编译 FFmpeg 静态库自编 libffmpegJNI.so，
    // 配合 EXTENSION_RENDERER_MODE_PREFER 让软解优先接管常见无损格式（FLAC/ALAC/mp3/opus/vorbis/ac3...）。
    // 详见 docs/REWORK_PLAN.md；不要使用不存在的 "media3-exoplayer-ffmpeg" 坐标。
    // compileOnly 提供给 vendored FfmpegLibrary 的 checker 标注（@MonotonicNonNull）。
    compileOnly("org.checkerframework:checker-qual:3.42.0")
    implementation("androidx.media3:media3-session:$media3")            // MediaSession / MediaSessionService
    implementation("androidx.media3:media3-ui:$media3")


    // ===== Room =====
    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    // ===== DataStore（设置 / 续播 / 均衡器预设） =====
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ===== Coil（专辑封面） =====
    implementation("io.coil-kt:coil-compose:2.7.0")

    // ===== Hilt（DI） =====
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-compiler:2.51.1")

    // ===== 协程 =====
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // ===== SMB（jcifs-ng） =====
    // 注：org.codelibs:jcifs-ng:2.1.9 在本机镜像（aliyun/public）404，
    // 改用 eu.agno3.jcifs:jcifs-ng:2.1.9（包名同为 jcifs.*，源码 import 不变，编译兼容）。
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.9")

    // ===== WebDAV 浏览：OkHttp 手写 PROPFIND（不引入专用 WebDAV 库） =====
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ===== AndroidX Security（凭据加密持久化：SMB / WebDAV） =====
    implementation("androidx.security:security-crypto:1.1.0")

    // ===== 核心库脱糖（jcifs-ng 需要 java.nio） =====
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // ===== Hilt Navigation Compose（hiltViewModel） =====
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    // ===== Lifecycle Compose（collectAsStateWithLifecycle） =====
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    // ===== DocumentFile（媒体库 SAF 扫描） =====
    implementation("androidx.documentfile:documentfile:1.0.1")
    // ===== Media（通知 MediaStyle / MediaMetadataCompat） =====
    implementation("androidx.media:media:1.7.0")


    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ==== v2.1 ZeroTier 内嵌：libzt 用户态（ZeroTier core + lwIP） ====
    // 官方预编译是首选；不可用或 API 不匹配时自行 NDK 编译 libzt 源码，
    // 产物 libzt.so 置于 src/main/jniLibs/<abi>/（首版仅 arm64-v8a + armeabi-v7a）。
    // 注意：libzt ≠ libZeroTierOneJNI（后者面向 VpnService/TUN，本方案不使用）。
    // 当前以 compileOnly + 运行时降级方式接入（见 data/network/zerotier/ZtNode），
    // 待引入官方 aar/jniLibs 后改为 implementation。
    // implementation("com.zerotier:libzt-android:<官方可用版本>")
}
