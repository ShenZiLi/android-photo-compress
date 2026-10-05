package com.photocompress.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Material 3 之外的语义令牌（原型中与 M3 色板不重合的部分）。
 * 数据可视化色固定为蓝/绿，不随动态取色变化，保证对比条含义稳定。
 */
@Immutable
data class AppColors(
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val onSurfaceMuted: Color,
    val divider: Color,
    val success: Color,
    val onSuccess: Color,
    val danger: Color,
    val onDanger: Color,
    val dataTodo: Color,
    val dataDone: Color,
    /**
     * 媒体大类占比色（首页占用饼图）。
     * 与 [dataTodo] / [dataDone]（未压缩 / 已压缩）是**不同维度**的语义，勿混用：
     * 那对是「处理状态」，这三个是「媒体类型」。
     */
    val kindPhoto: Color,
    val kindLive: Color,
    val kindVideo: Color,
)

val LightAppColors = AppColors(
    surfaceRaised = Color(0xFFFDFFFE),
    surfaceSunken = Color(0xFFE3EDE7),
    onSurfaceMuted = Color(0xFF435C52),
    divider = Color(0xFFD6E4DC),
    success = Color(0xFF1F6B4A),
    onSuccess = Color(0xFFFFFFFF),
    danger = Color(0xFFA8271F),
    onDanger = Color(0xFFFFFFFF),
    dataTodo = Color(0xFF2F6BD8),
    dataDone = Color(0xFF1F9E6B),
    kindPhoto = Color(0xFF2F6BD8),
    kindLive = Color(0xFF8B5CF6),
    kindVideo = Color(0xFFE07B39),
)

val DarkAppColors = AppColors(
    surfaceRaised = Color(0xFF1D2E2A),
    surfaceSunken = Color(0xFF22352F),
    onSurfaceMuted = Color(0xFFB9CFC4),
    divider = Color(0xFF3B514B),
    success = Color(0xFF7FD3AC),
    onSuccess = Color(0xFF04301E),
    danger = Color(0xFFFFB4AB),
    onDanger = Color(0xFF690005),
    dataTodo = Color(0xFF7FA8FF),
    dataDone = Color(0xFF5FD0A0),
    kindPhoto = Color(0xFF7FA8FF),
    kindLive = Color(0xFFBE9CFF),
    kindVideo = Color(0xFFF0A868),
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }
