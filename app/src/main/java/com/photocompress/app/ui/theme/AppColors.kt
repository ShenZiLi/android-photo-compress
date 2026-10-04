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
)

val LightAppColors = AppColors(
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFECEEF5),
    onSurfaceMuted = Color(0xFF50535F),
    divider = Color(0xFFE6E8F0),
    success = Color(0xFF1F6B4A),
    onSuccess = Color(0xFFFFFFFF),
    danger = Color(0xFFA8271F),
    onDanger = Color(0xFFFFFFFF),
    dataTodo = Color(0xFF2F6BD8),
    dataDone = Color(0xFF1F9E6B),
)

val DarkAppColors = AppColors(
    surfaceRaised = Color(0xFF1B1E24),
    surfaceSunken = Color(0xFF23262E),
    onSurfaceMuted = Color(0xFFB6B9C5),
    divider = Color(0xFF33363E),
    success = Color(0xFF7FD3AC),
    onSuccess = Color(0xFF04301E),
    danger = Color(0xFFFFB4AB),
    onDanger = Color(0xFF690005),
    dataTodo = Color(0xFF7FA8FF),
    dataDone = Color(0xFF5FD0A0),
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }
