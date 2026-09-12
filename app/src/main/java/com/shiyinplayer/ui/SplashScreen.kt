package com.shiyinplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiyinplayer.util.AppVersion
import com.shiyinplayer.R

/** 首屏暖米底色（与 icon 配套，铺满全屏幕）。 */
val SplashBackground = Color(0xFFFFF6EC)

/**
 * 启动首屏：暖米底铺满 + 居中透明 Logo（无白框）+ 品牌名「拾音」渐变 + 右下角动态版本号。
 * 展示版本号读取 [AppVersion.displayName]，随每次构建自动变化（debug 追加「 debug」）。
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    val brandBrush = Brush.linearGradient(
        listOf(Color(0xFFFF9F43), Color(0xFFFF6B6B), Color(0xFFFD79A8), Color(0xFF8B5CF6))
    )
    Box(
        modifier = modifier.fillMaxSize().background(SplashBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.offset(y = (-36).dp)
        ) {
            Image(
                painter = painterResource(R.drawable.splash_logo),
                contentDescription = stringResource(R.string.splash_logo_desc),
                modifier = Modifier.height(172.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 12.sp,
                    brush = brandBrush
                )
            )
        }
        Text(
            text = "V${AppVersion.displayName}",
            fontSize = 13.sp,
            color = Color(0xFF6C6C78),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 16.dp)
        )
    }
}