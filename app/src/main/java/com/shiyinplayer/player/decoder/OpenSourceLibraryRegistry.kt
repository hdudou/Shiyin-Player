package com.shiyinplayer.player.decoder

/**
 * 开源库注册表（单一来源）。登记所有引入的开源库元数据（库名/版本/许可证/来源 URL/哈希/覆盖格式/合规要求），
 * 派生哈希校验、「关于」页声明清单、构建一致性检查。详见 specs/audio_decoder_opensource/design.md §2.1.3.1。
 *
 * 2026-08-22（R-B1）libffmpeg n6.1.1 自编译静态库（LGPL-2.1，11 格式，ffmpeg_jni 软解）。
 * 2026-08-24：模块音乐（libxmp/xmp_jni）与 MIDI（libfluidsynth/fluidsynth_jni/DefaultGM.sf2）支持项
 * 已移除，仅保留 FFmpeg 解码库；详见 AudioFormatRegistry 收口说明。
 */
object OpenSourceLibraryRegistry {

    /** 开源许可证类型。 */
    enum class License {
        BSD3_CLAUSE, BSD2_CLAUSE, MIT, APACHE_2_0, LGPL_2_1, LGPL_3_0, PUBLIC_DOMAIN, CC_BY;

        val isLgpl: Boolean get() = this == LGPL_2_1 || this == LGPL_3_0
        val isGpl: Boolean get() = false
        val requiresDynamicLink: Boolean get() = isLgpl
        val requiresSourceDisclosure: Boolean get() = isLgpl
    }

    /** 库集成方式。 */
    enum class IntegrationMode { NDK_STATIC, NDK_DYNAMIC, SOURCE_EMBEDDED, ASSET }

    /** 开源库规格声明。 */
    data class OpenSourceLibrarySpec(
        val name: String,
        val version: String,
        val license: License,
        val sourceUrl: String,
        val codeUrl: String,
        val expectedHashArm64: String,
        val expectedHashArmv7: String,
        val coveredFormats: List<String>,
        val requiresComplianceDeclaration: Boolean,
        val integrationMode: IntegrationMode,
        val copyrightNotice: String
    )

    /** 内置资源规格声明。 */
    data class OpenSourceAssetSpec(
        val name: String,
        val version: String,
        val license: License,
        val sourceUrl: String,
        val assetPath: String,
        val sizeLimitMb: Int
    )

    /** 「关于」页声明条目。 */
    data class OpenSourceDeclaration(
        val name: String,
        val version: String,
        val license: License,
        val sourceUrl: String,
        val codeUrl: String,
        val copyrightNotice: String,
        val isLgpl: Boolean,
        val dynamicLinkReplaceable: Boolean
    )

    /** 全部开源库。2026-08-24：模块音乐(MOD)/MIDI/DSD 支持项已移除，删除 libopenmpt/xmp_jni/libfluidsynth。 */
    val allLibraries: List<OpenSourceLibrarySpec> = listOf(
        OpenSourceLibrarySpec(
            name = "libffmpeg_all",
            version = "n6.1.1",
            license = License.LGPL_2_1,
            sourceUrl = "https://ffmpeg.org/",
            codeUrl = "https://github.com/FFmpeg/FFmpeg",
            expectedHashArm64 = "DAD2CFA36E8C4A384E26AD5A6EBF711BFDE013D83E9455B75CF51566774A0522",
            expectedHashArmv7 = "985CF1D23AFA124D4A4AD96016A9ADFBDFA38FB16CDD0DB6EDA570D68FE9D8E1",
            coveredFormats = listOf("ape", "wv", "tta", "mpc", "spx", "aa3", "at3", "oma", "wma", "tak", "ofr", "dsf", "dff", "caf", "shn", "ac4"),
            requiresComplianceDeclaration = true,
            integrationMode = IntegrationMode.NDK_STATIC,
            copyrightNotice = "Copyright (c) 2000-2024 FFmpeg contributors. LGPL-2.1."
        )
    )

    /** 全部内置资源。2026-08-24：DefaultGM.sf2（MIDI 音源）已随 MIDI 支持移除。 */
    val allAssets: List<OpenSourceAssetSpec> = listOf()

    /** 按扩展名查询所属库。 */
    fun libraryOf(ext: String): OpenSourceLibrarySpec? =
        allLibraries.firstOrNull { ext in it.coveredFormats }

    /** 按库名查询。 */
    fun libraryByName(name: String): OpenSourceLibrarySpec? =
        allLibraries.firstOrNull { it.name == name }

    /** 查询指定库指定 ABI 的预期哈希。 */
    fun expectedHashOf(libName: String, abi: String): String? {
        val lib = libraryByName(libName) ?: return null
        return when (abi) {
            "arm64-v8a" -> lib.expectedHashArm64
            "armeabi-v7a" -> lib.expectedHashArmv7
            else -> null
        }
    }

    /** 需要合规声明的库（LGPL 库）。 */
    fun librariesRequiringDeclaration(): List<OpenSourceLibrarySpec> =
        allLibraries.filter { it.requiresComplianceDeclaration }

    /** 全部声明条目（供「关于」页渲染）。 */
    fun allDeclarations(): List<OpenSourceDeclaration> =
        allLibraries.map { lib ->
            OpenSourceDeclaration(
                name = lib.name,
                version = lib.version,
                license = lib.license,
                sourceUrl = lib.sourceUrl,
                codeUrl = lib.codeUrl,
                copyrightNotice = lib.copyrightNotice,
                isLgpl = lib.license.isLgpl,
                dynamicLinkReplaceable = lib.integrationMode == IntegrationMode.NDK_DYNAMIC
            )
        }

    /** 许可证显示名（供「关于」页/合规文本）。 */
    fun licenseText(license: License): String = when (license) {
        License.BSD3_CLAUSE -> "BSD-3-Clause"
        License.BSD2_CLAUSE -> "BSD-2-Clause"
        License.MIT -> "MIT"
        License.APACHE_2_0 -> "Apache-2.0"
        License.LGPL_2_1 -> "LGPL-2.1"
        License.LGPL_3_0 -> "LGPL-3.0"
        License.PUBLIC_DOMAIN -> "Public Domain"
        License.CC_BY -> "CC-BY"
    }

    /**
     * 生成完整开源合规声明文本（供「关于」页整体展示 / 随包 NOTICE）。
     * 针对每个 LGPL 库，按实际 [IntegrationMode] 给出对应的合规路径：
     * - NDK_DYNAMIC：动态链接，使用者可直接替换 .so，无需重新编译本应用；
     * - NDK_STATIC：静态链接（链接进应用 .so），依据 LGPL 第 6 节需提供“重新链接”能力，
     *   由本声明 + docs/LICENSES/LGPL_STATIC_RELINK.md（relink/对象文件/源码指引）共同满足。
     * 不得再笼统宣称“静态库为动态链接”（R-B2 合规修正）。
     */
    fun complianceNoticeText(): String = buildString {
        appendLine("开源软件合规声明 (Open-Source Notices)")
        appendLine("本应用（com.shiyinplayer）包含以下第三方组件，按各自许可证遵循合规义务：")
        appendLine()
        for (lib in allLibraries) {
            appendLine("• ${lib.name} v${lib.version}")
            appendLine("  许可证    : ${licenseText(lib.license)}")
            appendLine("  源码地址  : ${lib.codeUrl}")
            appendLine("  版权声明  : ${lib.copyrightNotice}")
            appendLine("  覆盖格式  : ${lib.coveredFormats.joinToString()}")
            if (lib.license.isLgpl) {
                when (lib.integrationMode) {
                    IntegrationMode.NDK_DYNAMIC ->
                        appendLine("  接入方式  : LGPL 动态链接（独立 .so），可直接替换库文件，无需重新编译本应用。")
                    IntegrationMode.NDK_STATIC ->
                        appendLine("  接入方式  : LGPL 静态链接（链接进应用 .so）。按 LGPL 对应源码/再链接义务，")
                            .appendLine("           使用者可用替换版重新链接本应用；对象文件与 relink 步骤随源码一并提供，")
                            .appendLine("           详见 docs/LICENSES/LGPL_STATIC_RELINK.md。可应要求提供。")
                    else ->
                        appendLine("  接入方式  : ${lib.integrationMode}")
                }
            }
            appendLine()
        }
        for (asset in allAssets) {
            appendLine("• ${asset.name} v${asset.version} — ${licenseText(asset.license)} — ${asset.sourceUrl}")
        }
        appendLine()
        appendLine("完整的各库许可证正文见对应源码发行包；如需替代 LGPL 库并重新链接，请联系开发者获取配套 relink 材料。")
    }

    /** 一致性校验：返回违规条目清单（空表示全部通过）。 */
    fun validateConsistency(): List<String> {
        val violations = mutableListOf<String>()
        for (lib in allLibraries) {
            for (ext in lib.coveredFormats) {
                if (AudioFormatRegistry.allFormats.none { it.extension == ext }) {
                    violations.add("${lib.name}: coveredFormat '$ext' not in AudioFormatRegistry")
                }
            }
            if (lib.expectedHashArm64 == "PLACEHOLDER_ARM64" || lib.expectedHashArmv7 == "PLACEHOLDER_ARMV7") {
                violations.add("${lib.name}: expected hash is placeholder")
            }
            if (lib.requiresComplianceDeclaration && !lib.license.isLgpl) {
                violations.add("${lib.name}: requiresComplianceDeclaration but license is not LGPL")
            }
            if (lib.license.isGpl) {
                violations.add("${lib.name}: GPL license is prohibited")
            }
        }
        return violations
    }
}