package com.dshnative.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ===== DSH web UI 真实配色（从 DeepSeek Harness 0.2.1 web 客户端提取的 computed style）=====
// 深色主题（prefers-color-scheme: dark）
val DshDark = darkColorScheme(
    primary = Color(0xFF7AAAFF),           // 主按钮 / 链接 / 活跃图标
    onPrimary = Color(0xFF151517),
    background = Color(0xFF151517),        // body 背景 rgb(21,21,23)
    onBackground = Color(0xFFF9FAFB),      // 正文
    surface = Color(0xFF1B1B1C),           // 侧栏背景 rgb(27,27,28)
    onSurface = Color(0xFFF9FAFB),
    surfaceVariant = Color(0xFF2C2C2E),    // 输入卡 / 用户消息气泡 rgb(44,44,46)
    onSurfaceVariant = Color(0xFFADB2B8),  // 次要文字 / 工具状态行
    outline = Color(0x29FFFFFF),           // 边框 rgba(255,255,255,0.16)
    outlineVariant = Color(0x14FFFFFF),    // 选中行 rgba(255,255,255,0.08)
    secondary = Color(0xFF43454A),         // "新会话"按钮背景 rgb(67,69,74)
    onSecondary = Color(0xFFF9FAFB),
    tertiary = Color(0xFF81858C),          // 节标题 / 弱化文字
    onTertiary = Color(0xFF81858C),
    surfaceContainer = Color(0xFF2C2C2E),
    surfaceContainerHigh = Color(0xFF353638), // 辅助按钮 rgb(53,54,56)
    surfaceContainerHighest = Color(0xFF43454A),
)

// 浅色主题（prefers-color-scheme: light）
val DshLight = lightColorScheme(
    primary = Color(0xFF4176E6),           // 主按钮 rgb(65,118,230)
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),        // body 背景白
    onBackground = Color(0xFF0F1115),      // 正文 rgb(15,17,21)
    surface = Color(0xFFF9FAFB),           // 侧栏 rgb(249,250,251)
    onSurface = Color(0xFF0F1115),
    surfaceVariant = Color(0xFFF5F6F7),    // 输入卡 / 辅助按钮 rgb(245,246,247)
    onSurfaceVariant = Color(0xFF61666B),  // 次要文字 rgb(97,102,107)
    outline = Color(0x1F000000),           // 边框 rgba(0,0,0,0.12)
    outlineVariant = Color(0x0F263148),    // 选中行 rgba(38,49,72,0.06)
    secondary = Color(0xFFFFFFFF),         // "新会话"按钮背景白
    onSecondary = Color(0xFF0F1115),
    tertiary = Color(0xFF81858C),          // 节标题
    onTertiary = Color(0xFF81858C),
    surfaceContainer = Color(0xFFF5F6F7),
    surfaceContainerHigh = Color(0xFFF0F1F3),
    surfaceContainerHighest = Color(0xFFE4EDFD), // 预览 badge 背景 rgb(228,237,253)
)

@Composable
fun DshNativeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DshDark else DshLight,
        content = content
    )
}