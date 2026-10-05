package com.photocompress.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.photocompress.app.ui.components.GlassScene
import com.photocompress.app.ui.components.MotionProvider

private val LightScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    background = LightSurface,
    onBackground = LightOnSurface,
)

private val DarkScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    background = DarkSurface,
    onBackground = DarkOnSurface,
)

/**
 * 主题入口（F13）：Material You 动态取色（API 31+），深浅色跟随系统。
 * 数据可视化色与表面层级通过 LocalAppColors 提供。
 */
@Composable
fun PhotoCompressTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    // 动态主题色继续参与控件；玻璃承载层与正文使用固定语义对比度。
    val colorScheme = baseScheme.copy(
        background = if (darkTheme) DarkSurface else LightSurface,
        surface = if (darkTheme) DarkSurfaceVariant else LightSurfaceVariant,
        onSurface = if (darkTheme) DarkOnSurface else LightOnSurface,
        onBackground = if (darkTheme) DarkOnSurface else LightOnSurface,
        onSurfaceVariant = if (darkTheme) DarkOnSurfaceVariant else LightOnSurfaceVariant,
    )
    val appColors = if (darkTheme) DarkAppColors else LightAppColors

    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = { MotionProvider { GlassScene(content = content) } },
        )
    }
}

/** 语义令牌快捷访问：MaterialTheme.appColors.dataTodo 等。 */
val MaterialTheme.appColors: AppColors
    @Composable get() = LocalAppColors.current
