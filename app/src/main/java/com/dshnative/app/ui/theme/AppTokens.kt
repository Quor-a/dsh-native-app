package com.dshnative.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

/**
 * 设计令牌（按 android-native-ai-app-dev 规范：角色命名，禁止即兴值）。
 *
 * - Color 用角色命名（不用颜色名）
 * - 单一强调色 Accent，只用于主操作与关键高亮
 * - 圆角固定档位 4 / 8 / 16 / 999
 * - 间距以 8dp 为基数
 * - 动效只给三档（毫秒）
 */
object AppColor {
    val Background = Color(0xFFFAF9F7)   // 暖石白，非纯白
    val Surface    = Color(0xFFFFFFFF)
    val Ink        = Color(0xFF1A1A18)   // 近黑，非纯黑
    val Muted      = Color(0xFF6B6862)
    val Border     = Color(0xFFE5E2DC)
    val Accent     = Color(0xFFC2410C)   // 单一强调色（暖橙），全站唯一
    val AccentSoft = Color(0xFFFDF0E7)
    val Danger     = Color(0xFFB91C1C)
    val Success    = Color(0xFF2D6A4F)
    val SurfaceVariant = Color(0xFFF1EFEA) // 卡片/次级表面
}

object AppRadius {
    val Sm: Dp = 4.dp
    val Md: Dp = 8.dp
    val Lg: Dp = 16.dp
    val Full: Dp = 999.dp
}

object AppSpace {
    val Xs: Dp = 8.dp
    val Sm: Dp = 8.dp
    val Md: Dp = 16.dp
    val Lg: Dp = 24.dp
    val Xl: Dp = 32.dp
    val Xxl: Dp = 48.dp
}

object AppMotion {
    const val Fast = 120L
    const val Base = 220L
    const val Slow = 360L
}