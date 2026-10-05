package com.photocompress.app.ui.components

import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private val LocalGlassBackdrop = staticCompositionLocalOf<HazeState?> { null }

private fun Context.allowGlassBlur(): Boolean {
    val power = getSystemService(PowerManager::class.java)
    val highContrast = Settings.Secure.getInt(contentResolver, "high_text_contrast_enabled", 0) != 0
    return Build.VERSION.SDK_INT >= 32 && !highContrast && power?.isPowerSaveMode != true
}

/** 静态环境光源；只对背景采样，照片、文字与点击层不参与模糊。 */
@Composable
fun GlassScene(modifier: Modifier = Modifier.fillMaxSize(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var blurEnabled by remember(context) { mutableStateOf(context.allowGlassBlur()) }
    DisposableEffect(context, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) blurEnabled = context.allowGlassBlur()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                blurEnabled = context.allowGlassBlur()
            }
        }
        lifecycle.addObserver(observer)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    val backdrop = rememberHazeState(blurEnabled = blurEnabled)
    SideEffect { backdrop.blurEnabled = blurEnabled }
    val background = MaterialTheme.colorScheme.background
    val dark = background.luminance() < 0.5f
    val accent = MaterialTheme.colorScheme.primary
    val mint = if (dark) Color(0xFF48B69B) else Color(0xFF72CBB6)
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
        Box(modifier = modifier) {
            Canvas(Modifier.matchParentSize().hazeSource(backdrop)) {
                drawRect(background)
                val width = size.width
                val height = size.height
                drawRect(Brush.radialGradient(
                    colors = listOf(mint.copy(alpha = if (dark) 0.24f else 0.28f), Color.Transparent),
                    center = Offset(width * 0.08f, height * 0.18f),
                    radius = width * 0.9f,
                ))
                drawRect(Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = if (dark) 0.16f else 0.17f), Color.Transparent),
                    center = Offset(width * 1.03f, height * 0.43f),
                    radius = width * 0.92f,
                ))
                drawRect(Brush.radialGradient(
                    colors = listOf(mint.copy(alpha = if (dark) 0.12f else 0.16f), Color.Transparent),
                    center = Offset(width * 0.64f, height * 0.97f),
                    radius = width * 0.8f,
                ))
            }
            content()
        }
    }
}

/** 统一玻璃边缘与高光。列表可关闭模糊，保持相同材质而降低绘制成本。 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    radius: Dp = 24.dp,
    blur: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val tint = if (color == Color.Unspecified) scheme.surface else color
    val shape = RoundedCornerShape(radius)
    val backdrop = LocalGlassBackdrop.current
    val edge = Brush.linearGradient(listOf(
        Color.White.copy(alpha = if (dark) 0.27f else 0.9f),
        Color.White.copy(alpha = if (dark) 0.04f else 0.3f),
        Color.White.copy(alpha = if (dark) 0.14f else 0.65f),
    ))
    val sheen = Brush.linearGradient(listOf(
        Color.White.copy(alpha = if (dark) 0.075f else 0.28f),
        Color.Transparent,
        scheme.primary.copy(alpha = 0.035f),
    ))
    Box(modifier = modifier
        .shadow(6.dp, shape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.16f))
        .clip(shape)
        .border(BorderStroke(1.dp, edge), shape),
    ) {
        val glassLayer = if (blur && backdrop != null) {
            Modifier.hazeEffect(backdrop, HazeStyle(
                backgroundColor = scheme.background,
                tint = HazeTint(tint.copy(alpha = if (dark) 0.72f else 0.66f)),
                blurRadius = 24.dp,
                noiseFactor = 0f,
                fallbackTint = HazeTint(tint),
            )) { inputScale = HazeInputScale.Auto }
        } else {
            Modifier.background(tint.copy(alpha = if (dark) 0.9f else 0.84f))
        }
        Box(Modifier.matchParentSize().then(glassLayer).background(sheen))
        Surface(color = Color.Transparent, contentColor = scheme.onSurface, content = content)
    }
}

/** 可中断的按压反馈，尺寸不随动效重排；系统动画缩放设置由 Compose 遵循。 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(150, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)), label = "glassPress")
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    val fill = if (enabled) containerColor else scheme.onSurface.copy(alpha = 0.08f)
    val foreground = if (enabled) contentColor else scheme.onSurface.copy(alpha = 0.38f)
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactions,
        shape = shape,
        modifier = modifier.defaultMinSize(minHeight = 48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(Brush.verticalGradient(listOf(fill, if (enabled) fill.copy(alpha = 0.84f) else fill)), shape)
            .border(1.dp, Color.White.copy(alpha = if (enabled) 0.28f else 0.08f), shape),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = foreground,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = foreground,
        ),
        content = content,
    )
}

@Composable
fun GlassIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    GlassSurface(modifier = modifier.size(48.dp), radius = 24.dp, blur = false) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp), content = content)
    }
}
