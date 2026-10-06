package com.photocompress.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photocompress.app.data.ledger.CompressedItemEntity
import com.photocompress.app.BuildConfig
import com.photocompress.app.data.media.MediaKind
import com.photocompress.app.data.media.QualityTier
import com.photocompress.app.ui.UiState
import com.photocompress.app.ui.components.AppBar
import com.photocompress.app.ui.components.AppMotion
import com.photocompress.app.ui.components.LocalMotionEnabled
import com.photocompress.app.ui.components.motionFloat
import com.photocompress.app.ui.components.CardSurface
import com.photocompress.app.ui.components.EmptyState
import com.photocompress.app.ui.components.SectionTitle
import com.photocompress.app.ui.components.SegmentedControl
import com.photocompress.app.ui.daysLeft
import com.photocompress.app.ui.formatCount
import com.photocompress.app.ui.formatSize
import com.photocompress.app.ui.theme.appColors

@Composable
fun SettingsScreen(
    state: UiState,
    onOpenTrash: () -> Unit,
    onOpenAlbumFilter: () -> Unit,
    onOpenCompressRatio: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        AppBar(title = "设置")
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            SectionTitle("压缩", modifier = Modifier.padding(horizontal = 0.dp))
            CardSurface(modifier = Modifier.fillMaxWidth()) {
                SettingRow(
                    icon = Icons.Filled.Tune,
                    title = "压缩比例",
                    onClick = onOpenCompressRatio,
                )
            }

            SectionTitle("显示", modifier = Modifier.padding(horizontal = 0.dp))
            CardSurface(modifier = Modifier.fillMaxWidth()) {
                SettingRow(
                    icon = Icons.Filled.FilterAlt,
                    title = "图集过滤",
                    subtitle = if (state.excludedAlbums.isEmpty()) {
                        "关闭的图集不会出现在未压缩 / 已压缩页"
                    } else {
                        "已隐藏 ${formatCount(state.excludedAlbums.size)} 个图集"
                    },
                    onClick = onOpenAlbumFilter,
                )
            }

            SectionTitle("存储与权限", modifier = Modifier.padding(horizontal = 0.dp))
            CardSurface(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingRow(
                        icon = Icons.Filled.Delete,
                        title = "回收站",
                        subtitle = "${formatCount(state.restorableCount())} 份备份可还原",
                        onClick = onOpenTrash,
                    )
                    SettingRow(
                        icon = Icons.Filled.Lock,
                        title = "文件访问权限",
                        subtitle = if (state.hasAllFilesAccess) "已授予「所有文件访问」" else "未授予，压缩将无法写入原文件",
                        trailText = if (state.hasAllFilesAccess) "正常" else "需授权",
                        trailOk = state.hasAllFilesAccess,
                    )
                    SettingRow(
                        icon = Icons.Filled.Info,
                        title = "关于",
                        subtitle = "版本 ${BuildConfig.VERSION_NAME}",
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun UiState.restorableCount(): Int =
    ledger.count { it.status == CompressedItemEntity.STATUS_DONE && it.backupRelPath != null }

/**
 * 压缩比例（二级页面）：普通照片、视频各一个档位；
 * 实况照片拆成「图片段」「视频段」两个档位，分开控制。
 */
@Composable
fun CompressRatioScreen(
    state: UiState,
    onBack: () -> Unit,
    onSetTier: (MediaKind, QualityTier) -> Unit,
    onSetLiveVideoTier: (QualityTier) -> Unit,
) {
    val settings = state.settings
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        AppBar(
            title = "压缩比例",
            subtitle = "按媒体类型选择压缩档位",
            navigation = {
                com.photocompress.app.ui.components.GlassIconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置")
                }
            },
        )
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            TierCard(
                title = "普通照片",
                hint = "JPEG / HEIF",
                options = listOf(
                    TierOption(
                        selected = QualityTier.fromName(settings.photoTier),
                        onSelect = { onSetTier(MediaKind.PHOTO, it) },
                    ),
                ),
            )
            TierCard(
                title = "实况照片",
                hint = "主图 + 增益图 + 内嵌视频",
                options = listOf(
                    TierOption(
                        label = "图片段 · 主图",
                        selected = QualityTier.fromName(settings.liveTier),
                        onSelect = { onSetTier(MediaKind.LIVE_PHOTO, it) },
                    ),
                    TierOption(
                        label = "视频段 · 内嵌视频",
                        selected = QualityTier.fromName(settings.liveVideoTier),
                        onSelect = onSetLiveVideoTier,
                    ),
                ),
            )
            TierCard(
                title = "视频",
                hint = "HEVC / H.264 / AV1 / VP9",
                options = listOf(
                    TierOption(
                        selected = QualityTier.fromName(settings.videoTier),
                        onSelect = { onSetTier(MediaKind.VIDEO, it) },
                    ),
                ),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 卡片内的一个档位控制项；[label] 为空表示该卡片只有一个档位，无需分组标题。 */
private data class TierOption(
    val selected: QualityTier,
    val onSelect: (QualityTier) -> Unit,
    val label: String? = null,
)

@Composable
private fun TierCard(
    title: String,
    hint: String,
    options: List<TierOption>,
) {
    CardSurface(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.appColors.onSurfaceMuted)
            }
            options.forEach { option ->
                Spacer(Modifier.height(12.dp))
                option.label?.let { label ->
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.appColors.onSurfaceMuted)
                    Spacer(Modifier.height(6.dp))
                }
                SegmentedControl(
                    options = QualityTier.entries.map { it.label },
                    selectedIndex = QualityTier.entries.indexOf(option.selected),
                    onSelect = { option.onSelect(QualityTier.entries[it]) },
                )
            }
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailText: String? = null,
    trailOk: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale = motionFloat(if (pressed && onClick != null && LocalMotionEnabled.current) 0.97f else 1f, "settingPress", AppMotion.Press)
    val indication = LocalIndication.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .let { if (onClick != null) it.clickable(interactionSource = interactions, indication = indication, onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                )))
                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.appColors.onSurfaceMuted)
            }
        }
        if (trailText == null && onClick != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.appColors.onSurfaceMuted)
        } else if (trailText != null) Text(
            trailText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = when {
                trailOk -> MaterialTheme.appColors.success
                else -> MaterialTheme.appColors.danger
            },
        )
    }
}

// ================================================================ 回收站

@Composable
fun TrashScreen(
    state: UiState,
    onBack: () -> Unit,
    onPurgeAll: () -> Unit,
    busy: Boolean = false,
) {
    val now = System.currentTimeMillis()
    val backups = state.ledger.filter { it.backupRelPath != null }
    val restorable = backups.filter { it.restoreDeadlineMs > now }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        AppBar(
            title = "回收站",
            subtitle = "${formatCount(restorable.size)} 份备份可还原",
            navigation = {
                com.photocompress.app.ui.components.GlassIconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回设置",
                    )
                }
            },
            actions = {
                com.photocompress.app.ui.components.GlassIconButton(
                    onClick = onPurgeAll,
                    enabled = backups.isNotEmpty() && !busy,
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "清理全部备份",
                        tint = if (backups.isNotEmpty() && !busy) MaterialTheme.appColors.danger
                            else MaterialTheme.appColors.onSurfaceMuted,
                    )
                }
            },
        )
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            CardSurface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    "压缩成功后原文件会保留在这里 30 天，期间可随时还原。超期或手动清理后，备份不可恢复，已压缩的照片不受影响。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(16.dp),
                )
            }
            SectionTitle("原始文件备份", modifier = Modifier.padding(horizontal = 0.dp))
            CardSurface(modifier = Modifier.fillMaxWidth()) {
                Column {
                    if (backups.isEmpty()) {
                        EmptyState("回收站是空的", "压缩照片后，原文件会保留在这里 30 天")
                    } else {
                        backups.forEach { record ->
                            val days = daysLeft(record.restoreDeadlineMs, now)
                            val expired = days < 0
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        record.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                    Text(
                                        "${record.bucketName} · ${formatSize(record.originalSize)} · " +
                                            if (expired) "已超期，等待自动清理" else "剩余 $days 天可还原",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.appColors.onSurfaceMuted,
                                        maxLines = 1,
                                    )
                                }
                                Text(
                                    if (expired) "超期" else "可还原",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (expired) MaterialTheme.appColors.danger else MaterialTheme.appColors.success,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
