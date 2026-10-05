package com.shiyinplayer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shiyinplayer.R
import com.shiyinplayer.data.local.StartupDataGuard

/**
 * 降级安装（库版本高于 APK 能认的版本）时的说明页 —— Batch 0 / B0-3。
 *
 * ## 为什么必须有这一页
 *
 * Room 只能升级、不能降级：库里是 v16 而装回一个只认 v15 的旧包，它会抛
 * `IllegalStateException` 把启动崩掉。用户看到的是"打开就闪退"，
 * 唯一的"解决办法"（清除数据）恰恰会删掉整个曲库 —— 最坏的那一步反而最容易做。
 *
 * ## 这一页做了什么
 *
 * 由 [StartupDataGuard] 在 `AppModule.provideAppDatabase` 里置位后跳到这里：
 *
 * - 明说发生了什么（不要用"发生错误"这种空话）；
 * - 明说**原始数据没有被改过**，并给出它的路径与备份路径，方便手动取回；
 * - 只提供"退出"，不提供"清除数据" —— 绝不引导用户去删自己的曲库。
 *
 * 用户装回新版后，真实库原封不动继续用。
 */
class UnsupportedDataActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                UnsupportedDataScreen(
                    onExit = { finishAffinity() },
                    onCopy = { path ->
                        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        manager?.setPrimaryClip(ClipData.newPlainText("shiyin-db-path", path))
                        Toast.makeText(
                            this@UnsupportedDataActivity,
                            getString(R.string.unsupported_data_copied),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 不给"返回"（返回会回到一个空曲库的界面，反而更让人困惑），直接退干净
        finishAffinity()
    }

    companion object {
        /** 从任意入口跳进来（清掉栈，避免脚底下还压着一个半初始化的主界面）。 */
        fun intent(from: android.content.Context): Intent =
            Intent(from, UnsupportedDataActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}

@Composable
private fun UnsupportedDataScreen(onExit: () -> Unit, onCopy: (String) -> Unit) {
    val reason = StartupDataGuard.reason
    val originalPath = StartupDataGuard.originalDbPath
    val backupPath = StartupDataGuard.backupPath

    // 四种情形共用一个页面：标题/正文各说各的，自救指引一致（别动数据、装新版或腾空间后重启）。
    // 用 when 穷举而不是布尔判断 —— 新增一种原因时，编译器会在这里提醒补文案，
    // 否则会悄悄套用"另一种"的说明，把用户引向错误的操作。
    val titleRes = when (reason) {
        StartupDataGuard.Reason.Downgrade -> R.string.unsupported_data_title
        StartupDataGuard.Reason.RecoveryFailed -> R.string.unsupported_data_recover_title
        StartupDataGuard.Reason.BackupFailed -> R.string.unsupported_data_blocked_backup_title
        StartupDataGuard.Reason.InsufficientSpace -> R.string.unsupported_data_blocked_space_title
    }

    val body = when (reason) {
        StartupDataGuard.Reason.Downgrade -> stringResource(
            R.string.unsupported_data_body,
            StartupDataGuard.actualVersion,
            StartupDataGuard.expectedVersion
        )

        StartupDataGuard.Reason.RecoveryFailed -> stringResource(R.string.unsupported_data_recover_body)

        // E6/E3：这次**什么都没改**，真实库还停在旧版本，说清楚这一点
        // —— 否则用户第一反应是"升级把我数据搞坏了"。
        StartupDataGuard.Reason.BackupFailed -> stringResource(
            R.string.unsupported_data_blocked_backup_body,
            StartupDataGuard.actualVersion,
            StartupDataGuard.expectedVersion
        )

        StartupDataGuard.Reason.InsufficientSpace -> stringResource(
            R.string.unsupported_data_blocked_space_body,
            StartupDataGuard.actualVersion,
            StartupDataGuard.expectedVersion
        )
    }

    // 被拦下的两种情况：数据原封不动留在原处；另两种：数据可能已被改名/是旧版本
    val blocked = reason == StartupDataGuard.Reason.BackupFailed ||
        reason == StartupDataGuard.Reason.InsufficientSpace
    val pathLabelRes = when (reason) {
        StartupDataGuard.Reason.Downgrade -> R.string.unsupported_data_original
        StartupDataGuard.Reason.RecoveryFailed -> R.string.unsupported_data_recover_source
        StartupDataGuard.Reason.BackupFailed,
        StartupDataGuard.Reason.InsufficientSpace -> R.string.unsupported_data_untouched
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )

            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )

            // 排障细节（如"可用空间 120MB 低于所需的 780MB"）：只对有排障价值的两种展示
            if (blocked && StartupDataGuard.detail != null) {
                Text(
                    text = StartupDataGuard.detail ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            if (originalPath != null) {
                Text(
                    text = stringResource(pathLabelRes, originalPath),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.padding(top = 20.dp)
                )
                // 路径很长、手机上没法选中复制，给一个按钮（O3 决策 (a) 的要求）
                TextButton(onClick = { onCopy(originalPath) }) {
                    Text(text = stringResource(R.string.unsupported_data_copy))
                }
            }
            if (backupPath != null) {
                Text(
                    text = stringResource(R.string.unsupported_data_backup, backupPath),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.padding(top = 8.dp)
                )
                TextButton(onClick = { onCopy(backupPath) }) {
                    Text(text = stringResource(R.string.unsupported_data_copy))
                }
            }

            Button(onClick = onExit, modifier = Modifier.padding(top = 28.dp)) {
                Text(text = stringResource(R.string.unsupported_data_exit))
            }
        }
    }
}
