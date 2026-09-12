package com.shiyinplayer.lockscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 锁屏权限引导对话框：
 * 小米 HyperOS / 华为 EMUI 等国产 ROM 缺 SYSTEM_ALERT_WINDOW 时弹此框。
 * "去设置开启" → 跳转系统设置 → 等待用户授权
 * "取消" → 降级到 MediaStyle 通知（不弹锁屏叠加）
 */
@Composable
fun LockScreenPermissionGuideDialog(
    title: String,
    message: String,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(
                    text = message,
                    fontSize = 14.sp
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onOpenSettings()
                onCancel()
            }) {
                Text("去设置开启")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("取消（降级到通知栏控制）")
            }
        }
    )
}
