package com.photocompress.app.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import com.photocompress.app.ui.components.GlassIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photocompress.app.ui.UiState
import com.photocompress.app.ui.allAlbumNames
import com.photocompress.app.ui.components.AppBar
import com.photocompress.app.ui.components.EmptyState
import com.photocompress.app.ui.components.GlassSurface
import com.photocompress.app.ui.formatCount
import com.photocompress.app.ui.theme.appColors

/** 开关轨道与圆点的固定尺寸：圆点尺寸不随状态变化，避免切换过程中溢出轨道。 */
private val TrackWidth = 52.dp
private val TrackHeight = 32.dp
private val ThumbSize = 20.dp
private val ThumbInset = 6.dp

/**
 * 图集过滤（二级页面）：逐图集开关，**打开 = 展示图集，关闭 = 隐藏图集**
 * （被隐藏的图集不出现在未压缩 / 已压缩页）。
 */
@Composable
fun AlbumFilterScreen(
    state: UiState,
    onBack: () -> Unit,
    onSetShown: (String, Boolean) -> Unit,
) {
    val albums = state.allAlbumNames()
    val hidden = state.excludedAlbums

    Column(modifier = Modifier.fillMaxWidth()) {
        AppBar(
            title = "图集过滤",
            subtitle = "已隐藏 ${formatCount(hidden.size)} 个 · 共 ${formatCount(albums.size)} 个图集",
            navigation = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置")
                }
            },
        )
        if (albums.isEmpty()) {
            EmptyState("暂无可配置的图集", "扫描完成后这里会列出本机图集")
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(albums, key = { it.first }) { (name, count) ->
                val shown = name !in hidden
                GlassSurface(
                    blur = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = shown,
                                role = Role.Switch,
                                onValueChange = { onSetShown(name, it) },
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${formatCount(count)} 项 · ${if (shown) "展示中" else "已隐藏"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.appColors.onSurfaceMuted,
                            )
                        }
                        ShownSwitch(shown = shown)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * 只做状态展示的开关指示器：点击由整行 [toggleable] 承担，自身不接收触摸，
 * 因此既没有按压缩放，也不会出现随状态变化的圆点尺寸（原 Material 开关会溢出轨道）。
 */
@Composable
private fun ShownSwitch(shown: Boolean) {
    val thumbOffset by animateDpAsState(
        targetValue = if (shown) TrackWidth - ThumbSize - ThumbInset else ThumbInset,
        label = "thumbOffset",
    )
    val shape = RoundedCornerShape(TrackHeight / 2)

    Box(
        modifier = Modifier
            .size(width = TrackWidth, height = TrackHeight)
            .clip(shape)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(
                if (shown) MaterialTheme.colorScheme.primary else MaterialTheme.appColors.surfaceSunken,
                if (shown) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else MaterialTheme.appColors.surfaceSunken,
            )))
            .border(
                width = 1.dp,
                color = if (shown) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            )
            .clearAndSetSemantics {},
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(ThumbSize)
                .clip(CircleShape)
                .background(
                    if (shown) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline,
                ),
        )
    }
}
