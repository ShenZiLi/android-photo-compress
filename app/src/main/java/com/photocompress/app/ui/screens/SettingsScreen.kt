package com.photocompress.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photocompress.app.data.ledger.CompressedItemEntity
import com.photocompress.app.data.media.MediaKind
import com.photocompress.app.data.media.QualityTier
import com.photocompress.app.ui.UiState
import com.photocompress.app.ui.allAlbumNames
import com.photocompress.app.ui.components.AppBar
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
    onSetTier: (MediaKind, QualityTier) -> Unit,
    onToggleAlbumExcluded: (String, Boolean) -> Unit,
) {
    val settings = state.settings
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        AppBar(title = "设置")
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            TierCard(
                title = "普通照片",
                hint = "JPEG / HEIF",
                selected = QualityTier.fromName(settings.photoTier),
                onSelect = { onSetTier(MediaKind.PHOTO, it) },
            )
            TierCard(
                title = "实况照片",
                hint = "主图 + 增益图 + 内嵌视频",
                selected = QualityTier.fromName(settings.liveTier),
                onSelect = { onSetTier(MediaKind.LIVE_PHOTO, it) },
            )
            TierCard(
                title = "视频",
                hint = "HEVC / H.264 / AV1 / VP9",
                selected = QualityTier.fromName(settings.videoTier),
                onSelect = { onSetTier(MediaKind.VIDEO, it) },
            )

            SectionTitle("图集过滤", modifier = Modifier.padding(horizontal = 0.dp))
            AlbumFilterCard(state, onToggleAlbumExcluded)

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
                        subtitle = "版本 0.1.0",
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun UiState.restorableCount(): Int =
    ledger.count { it.status == CompressedItemEntity.STATUS_DONE && it.backupRelPath != null }

/** 图集过滤：被排除的图集不出现在未压缩 / 已压缩页。 */
@Composable
private fun AlbumFilterCard(state: UiState, onToggle: (String, Boolean) -> Unit) {
    val albums = state.allAlbumNames()
    CardSurface(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                "被排除的图集不会出现在「未压缩」和「已压缩」页面。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.appColors.onSurfaceMuted,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            if (albums.isEmpty()) {
                EmptyState("暂无可配置的图集", "扫描完成后这里会列出本机图集")
            } else {
                albums.forEach { (name, count) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "$count 项",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.appColors.onSurfaceMuted,
                            )
                        }
                        Switch(
                            checked = name in state.excludedAlbums,
                            onCheckedChange = { onToggle(name, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TierCard(
    title: String,
    hint: String,
    selected: QualityTier,
    onSelect: (QualityTier) -> Unit,
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
            Spacer(Modifier.height(12.dp))
            SegmentedControl(
                options = QualityTier.entries.map { it.label },
                selectedIndex = QualityTier.entries.indexOf(selected),
                onSelect = { onSelect(QualityTier.entries[it]) },
            )
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailText: String? = null,
    trailOk: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.appColors.onSurfaceMuted)
        }
        Text(
            trailText ?: "›",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (trailText != null) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                trailText == null -> MaterialTheme.appColors.onSurfaceMuted
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
) {
    val now = System.currentTimeMillis()
    val backups = state.ledger.filter { it.backupRelPath != null }
    val restorable = backups.filter { it.restoreDeadlineMs > now }
    val totalBefore = backups.sumOf { it.originalSize }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        AppBar(
            title = "回收站",
            subtitle = "${formatCount(restorable.size)} 份备份可还原",
            navigation = {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
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
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Button(
                onClick = onPurgeAll,
                enabled = backups.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.appColors.danger,
                    contentColor = MaterialTheme.appColors.onDanger,
                ),
            ) {
                Text(if (backups.isEmpty()) "暂无可清理备份" else "清理全部备份（释放 ${formatSize(totalBefore)}）")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
