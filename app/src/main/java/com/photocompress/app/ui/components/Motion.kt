package com.photocompress.app.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** 高频状态短反馈，页面稍长；不循环、不延迟业务回调。 */
object AppMotion {
    const val Press = 120
    const val State = 150
    const val Page = 220
    const val Exit = 120
    const val Progress = 160
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
}

val LocalMotionEnabled = staticCompositionLocalOf { false }

private fun Context.allowMotion(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f &&
        getSystemService(PowerManager::class.java)?.isPowerSaveMode != true

/** 单一观察点；系统动画关闭、节电和键盘输入即时回退，不在每张照片上注册监听。 */
@Composable
fun MotionProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val input = LocalInputModeManager.current
    var allowed by remember(context) { mutableStateOf(context.allowMotion()) }
    DisposableEffect(context, lifecycle) {
        fun refresh() { allowed = context.allowMotion() }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { refresh() }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { refresh() }
        }
        lifecycle.addObserver(lifecycleObserver)
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, settingsObserver)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
            context.contentResolver.unregisterContentObserver(settingsObserver)
            context.unregisterReceiver(receiver)
        }
    }
    CompositionLocalProvider(LocalMotionEnabled provides (allowed && input.inputMode != InputMode.Keyboard), content = content)
}

@Composable
fun <T> motionTween(duration: Int = AppMotion.State): TweenSpec<T> =
    tween(durationMillis = if (LocalMotionEnabled.current) duration else 0, easing = AppMotion.EaseOut)

@Composable
fun motionFloat(target: Float, label: String, duration: Int = AppMotion.State): Float {
    val value by animateFloatAsState(targetValue = target, animationSpec = motionTween(duration), label = label)
    return value
}

@Composable
fun motionColor(target: Color, label: String): Color {
    // 主题变化直接采用新色，避免整页颜色过渡；同一主题下才过渡选中/禁用状态。
    val scheme = MaterialTheme.colorScheme
    val value by key(scheme.background, scheme.primary, scheme.onSurface) {
        animateColorAsState(targetValue = target, animationSpec = motionTween(), label = label)
    }
    return value
}

/** 只呈现当前页面，避免旧页在退出动画中仍响应点击或持有旧媒体快照。 */
@Composable
fun MotionPage(route: Any, family: Any, depth: Int, content: @Composable () -> Unit) {
    var previousRoute by remember { mutableStateOf(route) }
    var previousFamily by remember { mutableStateOf(family) }
    var previousDepth by remember { mutableStateOf(depth) }
    val enabled = LocalMotionEnabled.current
    val direction = remember(route) { if (family == previousFamily) depth.compareTo(previousDepth) else 0 }
    val progress = remember(route) { Animatable(if (enabled && route != previousRoute) 0f else 1f) }
    val animation = motionTween<Float>(AppMotion.Page)
    SideEffect {
        previousRoute = route
        previousFamily = family
        previousDepth = depth
    }
    LaunchedEffect(route, enabled) {
        if (enabled) progress.animateTo(1f, animation) else progress.snapTo(1f)
    }
    Box(Modifier.fillMaxSize().graphicsLayer {
        // 当前页面从第一帧就可读，避免切页时先出现一块空白背景。
        alpha = if (enabled) 0.72f + 0.28f * progress.value else 1f
        translationX = if (enabled) direction * 24.dp.toPx() * (1f - progress.value) else 0f
        translationY = if (enabled && direction == 0) 8.dp.toPx() * (1f - progress.value) else 0f
    }) {
        key(route) { content() }
    }
}
