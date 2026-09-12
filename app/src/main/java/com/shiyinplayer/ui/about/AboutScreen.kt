package com.shiyinplayer.ui.about

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import com.shiyinplayer.util.AppVersion
import com.shiyinplayer.player.decoder.OpenSourceLibraryRegistry
import org.json.JSONObject

/** 当前实际使用的第三方依赖库（与 app/build.gradle.kts 对齐），用于「关于」页开源使用情况展示。
 * 许可证与版本以对应 Maven 中央仓库 / 官方发布为准。 */
private data class ThirdPartyLibrary(val name: String, val version: String, val license: String, val purpose: String)

private val thirdPartyLibraries = listOf(
    ThirdPartyLibrary("Media3 (ExoPlayer)", "1.4.1", "Apache-2.0", "播放内核 / MediaSession"),
    ThirdPartyLibrary("Jetpack Compose (Material3)", "BOM 2024.08", "Apache-2.0", "UI"),
    ThirdPartyLibrary("Kotlinx Coroutines", "1.8.1", "Apache-2.0", "协程"),
    ThirdPartyLibrary("Room", "2.6.1", "Apache-2.0", "数据库"),
    ThirdPartyLibrary("DataStore", "1.1.1", "Apache-2.0", "设置 / 续播持久化"),
    ThirdPartyLibrary("Coil", "2.7.0", "Apache-2.0", "专辑封面加载"),
    ThirdPartyLibrary("Hilt (Dagger)", "2.51.1", "Apache-2.0", "依赖注入"),
    ThirdPartyLibrary("Navigation Compose", "2.7.7", "Apache-2.0", "页面导航"),
    ThirdPartyLibrary("Lifecycle", "2.8.4", "Apache-2.0", "生命周期"),
    ThirdPartyLibrary("OkHttp", "4.12.0", "Apache-2.0", "WebDAV / HTTP 直链"),
    ThirdPartyLibrary("jcifs-ng", "2.1.9", "LGPL-2.1", "SMB 网络源"),
    ThirdPartyLibrary("AndroidX Security-Crypto", "1.1.0-alpha06", "Apache-2.0", "凭据加密"),
    ThirdPartyLibrary("AndroidX DocumentFile", "1.0.1", "Apache-2.0", "媒体库 SAF 扫描"),
    ThirdPartyLibrary("AndroidX Media", "1.7.0", "Apache-2.0", "媒体通知"),
    ThirdPartyLibrary("FFmpeg (软解库)", "6.1.1", "LGPL-2.1", "稀有格式解码")
)

/** 一条版本更新记录（对应 assets/changelog.json 中的一个版本）。 */
private data class VersionEntry(
    val version: String,
    val code: Int,
    val date: String,
    val channel: String,
    val notes: List<String>
)

/** 从内置 assets/changelog.json 读取全部版本更新记录（新版本在前）。缺失或解析失败时返回空。 */
@Composable
private fun rememberChangelog(): List<VersionEntry> {
    val context = LocalContext.current
    return remember {
        runCatching {
            val text = context.assets.open("changelog.json").bufferedReader().use { it.readText() }
            val arr = JSONObject(text).getJSONArray("versions")
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val n = o.optJSONArray("notes")
                    val notes = buildList { for (j in 0 until n.length()) add(n.getString(j)) }
                    add(
                        VersionEntry(
                            o.optString("version"), o.optInt("code"),
                            o.optString("date"), o.optString("channel"), notes
                        )
                    )
                }
            }.sortedByDescending { it.code }
        }.getOrDefault(emptyList())
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(navController: NavController) {
    val declarations = OpenSourceLibraryRegistry.allDeclarations()
    val assets = OpenSourceLibraryRegistry.allAssets
    val violations = OpenSourceLibraryRegistry.validateConsistency()
    val complianceNotice = OpenSourceLibraryRegistry.complianceNoticeText()
    val appVersion = AppVersion.displayName
    val buildInfo = "版本 ${appVersion} · 构建 ${AppVersion.code}"
    val changelog = rememberChangelog()
    var showNotes by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "拾音",
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildInfo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { showNotes = true }) {
                    Text("查看版本更新内容")
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "第三方依赖库",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "播放器当前使用的第三方依赖（与构建配置对齐）。详见各库官方许可证。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(thirdPartyLibraries) { lib ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(lib.name, style = MaterialTheme.typography.titleSmall)
                            Text("v${lib.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("用途: ${lib.purpose}", style = MaterialTheme.typography.bodySmall)
                        Text("许可证: ${lib.license}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "开源库许可声明",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "本应用使用了以下开源库。LGPL 库按实际链接方式遵循合规（动态可替换 / 静态可再链接），完整声明见文末。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(declarations) { decl ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                decl.name,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "v${decl.version}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "许可证: ${licenseDisplayName(decl.license)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            decl.copyrightNotice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "源码: ${decl.codeUrl}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (decl.isLgpl) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (decl.dynamicLinkReplaceable)
                                    "⚠ LGPL — 动态链接，可替换库文件。"
                                else
                                    "⚠ LGPL — 静态链接，可再链接替换（见文末完整合规声明）。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            if (assets.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "内置资源",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                items(assets) { asset ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                asset.name,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "许可证: ${licenseDisplayName(asset.license)} | 路径: ${asset.assetPath}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (violations.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠ 一致性校验警告",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    violations.forEach { v ->
                        Text(
                            "• $v",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "完整开源合规声明",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    complianceNotice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showNotes) {
        Dialog(onDismissRequest = { showNotes = false }) {
            Card(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxHeight(0.85f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("版本更新内容", style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { showNotes = false }) { Text("关闭") }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    if (changelog.isEmpty()) {
                        Text(
                            "暂无更新记录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 24.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(changelog) { entry ->
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "v${entry.version}",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                entry.date,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    entry.notes.forEachIndexed { index, note ->
                                        Text(
                                            "${index + 1}. $note",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun licenseDisplayName(license: OpenSourceLibraryRegistry.License): String =
    when (license) {
        OpenSourceLibraryRegistry.License.BSD3_CLAUSE -> "BSD-3-Clause"
        OpenSourceLibraryRegistry.License.BSD2_CLAUSE -> "BSD-2-Clause"
        OpenSourceLibraryRegistry.License.MIT -> "MIT"
        OpenSourceLibraryRegistry.License.APACHE_2_0 -> "Apache-2.0"
        OpenSourceLibraryRegistry.License.LGPL_2_1 -> "LGPL-2.1"
        OpenSourceLibraryRegistry.License.LGPL_3_0 -> "LGPL-3.0"
        OpenSourceLibraryRegistry.License.PUBLIC_DOMAIN -> "Public Domain"
        OpenSourceLibraryRegistry.License.CC_BY -> "CC-BY"
    }