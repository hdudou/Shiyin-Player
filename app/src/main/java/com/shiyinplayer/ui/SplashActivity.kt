package com.shiyinplayer.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.shiyinplayer.data.VersionUpgradeCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 启动首屏入口（Launcher）：
 * 正常启动展示暖米底 + 居中 Logo + 品牌名 + 动态版本号，短暂停留后淡入主界面。
 * 升级安装后的首次冷启动若检测到数据结构变化（[VersionUpgradeCoordinator.needsUpgrade]）：
 * 先切到「版本更新数据优化中」等待页并执行一次性数据迁移（如补全文件夹目录树），
 * 完成后再进入正常播放器页面，期间始终显示提醒文案避免误以为卡死。
 */
@AndroidEntryPoint
class SplashActivity : ComponentActivity() {

    @Inject lateinit var upgradeCoordinator: VersionUpgradeCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SplashScreen() }
        lifecycleScope.launch {
            val upgrading = runCatching { upgradeCoordinator.needsUpgrade() }.getOrDefault(false)
            if (upgrading) {
                // 切换为升级等待页，并执行数据迁移（离开主线程）
                setContent { UpgradePendingScreen() }
                withContext(Dispatchers.Default) {
                    runCatching { upgradeCoordinator.runUpgrade() }
                }
                startMain()
            } else {
                delay(1600)
                if (isFinishing || isDestroyed) return@launch
                startMain()
            }
        }
    }

    private fun startMain() {
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}

/** 升级过程中显示的提醒页：提示「版本更新正在重新优化数据」，完成后自动进入主界面。 */
@Composable
private fun UpgradePendingScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(text = "版本更新中…", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "正在重新优化曲库数据，请稍候",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
            )
            CircularProgressIndicator()
        }
    }
}