package com.photocompress.app.ui.screens

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import com.photocompress.app.ui.components.GlassIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photocompress.app.data.ledger.CompressedItemEntity
import com.photocompress.app.data.media.MediaItem
import com.photocompress.app.data.media.MediaKind
import com.photocompress.app.ui.AlbumDoneUi
import com.photocompress.app.ui.AlbumTodoUi
import com.photocompress.app.ui.DoneMedia
import com.photocompress.app.ui.LevelState
import com.photocompress.app.ui.UiState
import com.photocompress.app.ui.albumDoneAll
import com.photocompress.app.ui.albumTodoAll
import com.photocompress.app.ui.applyDoneFilter
import com.photocompress.app.ui.applyTodoFilter
import com.photocompress.app.ui.components.AppBar
import com.photocompress.app.ui.components.AppMotion
import com.photocompress.app.ui.components.LocalMotionEnabled
import com.photocompress.app.ui.components.motionColor
import com.photocompress.app.ui.components.motionFloat
import com.photocompress.app.ui.components.motionTween
import com.photocompress.app.ui.components.Badge
import com.photocompress.app.ui.components.EmptyState
import com.photocompress.app.ui.components.GlassSurface
import com.photocompress.app.ui.components.PickCircle
import com.photocompress.app.ui.components.ThumbImage
import com.photocompress.app.ui.daysLeft
import com.photocompress.app.ui.formatCount
import com.photocompress.app.ui.formatSize
import com.photocompress.app.ui.theme.appColors

// ================================================================ 一级：图集列表

@Composable
fun TodoLevel1(
    state: UiState,
    onOpenAlbum: (String) -> Unit,
    onToggleAlbum: (String) -> Unit,
    onSelectAll: () -> Unit,
) {
    val albums = state.albumTodoAll().filter { it.compressibleCount > 0 || it.pendingCount > 0 }
    val totalBytes = albums.sumOf { it.bytes }
    val count = albums.sumOf { it.count }
    val allSelected = albums.isNotEmpty() && albums.all { it.name in state.todo.pickedAlbums }
    Column(modifier = Modifier.fillMaxSize()) {
        AppBar(
            title = "未压缩",
            subtitle = "${formatCount(count)} 项 · 共 ${formatSize(totalBytes)}",
            actions = {
                SelectAllAction(allSelected = allSelected, enabled = albums.isNotEmpty(), onClick = onSelectAll)
            },
        )
        // 无可压缩图集时留空（不再显示占位文案）
        if (albums.isEmpty()) return
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(albums, key = { it.name }) { album ->
                AlbumCard(
                    modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = motionTween()),
                    coverUris = album.items.take(4).map { it.uri },
                    name = album.name,
                    line2 = "${formatCount(album.count)} 项 · ${formatSize(album.bytes)}",
                    line3 = if (album.pendingCount > 0) "待恢复 ${formatCount(album.pendingCount)} 项" else if (album.compressibleCount > 0) "可压缩 ${formatCount(album.compressibleCount)} 项" else "无可压缩项",
                    line3Warn = album.compressibleCount == 0,
                    picked = album.name in state.todo.pickedAlbums,
                    onOpen = { onOpenAlbum(album.name) },
                    onTogglePick = { onToggleAlbum(album.name) },
                )
            }
        }
    }
}

@Composable
fun DoneLevel1(
    state: UiState,
    onOpenAlbum: (String) -> Unit,
    onToggleAlbum: (String) -> Unit,
    onSelectAll: () -> Unit,
) {
    val albums = state.albumDoneAll()
    val actionable = albums.filter { it.restorableCount > 0 }
    val allSelected = actionable.isNotEmpty() && actionable.all { it.name in state.done.pickedAlbums }
    val count = albums.sumOf { it.count }
    val before = albums.sumOf { it.before }
    val after = albums.sumOf { it.after }
    val knownBefore = albums.sumOf { it.knownBeforeCount }
    Column(modifier = Modifier.fillMaxSize()) {
        AppBar(
            title = "已压缩",
            subtitle = if (knownBefore == 0) "${formatCount(count)} 项 · ${formatSize(after)}"
            else "${formatCount(count)} 项 · ${formatSize(before)} → ${formatSize(after)}",
            actions = {
                SelectAllAction(allSelected = allSelected, enabled = actionable.isNotEmpty(), onClick = onSelectAll)
            },
        )
        // 暂无可处理图集时留空（不再显示占位文案）
        if (albums.isEmpty()) return
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(albums, key = { it.name }) { album ->
                AlbumCard(
                    modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = motionTween()),
                    coverUris = album.items.take(4).mapNotNull { it.item?.uri },
                    name = album.name,
                    line2 = album.sizeLine,
                    line3 = album.statusLine,
                    line3Warn = album.restorableCount == 0 && album.skippedCount == 0,
                    picked = album.name in state.done.pickedAlbums,
                    onOpen = { onOpenAlbum(album.name) },
                    onTogglePick = { onToggleAlbum(album.name) },
                )
            }
        }
    }
}

@Composable
private fun AlbumCard(
    modifier: Modifier = Modifier,
    coverUris: List<android.net.Uri>,
    name: String,
    line2: String,
    line3: String,
    line3Warn: Boolean,
    picked: Boolean,
    onOpen: () -> Unit,
    onTogglePick: () -> Unit,
) {
    val context = LocalContext.current
    val selectionEdge = motionColor(if (picked) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f), "albumSelection")
    GlassSurface(modifier = modifier.fillMaxWidth(), blur = false) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .border(if (picked) 2.dp else 1.dp, selectionEdge, RoundedCornerShape(16.dp))
                        .combinedClickable(onClick = onOpen),
                ) {
                    CollageCover(context, coverUris)
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(48.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .toggleable(value = picked, role = Role.Checkbox, onValueChange = { onTogglePick() })
                        .semantics { contentDescription = "选择图集 $name" },
                    contentAlignment = Alignment.Center,
                ) {
                    PickCircle(checked = picked)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                line2,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.appColors.onSurfaceMuted,
                maxLines = 1,
            )
            Text(
                line3,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (line3Warn) MaterialTheme.appColors.danger else MaterialTheme.appColors.success,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CollageCover(context: Context, uris: List<android.net.Uri>) {
    val filled = uris + List((4 - uris.size).coerceAtLeast(0)) { null }
    Column(modifier = Modifier.fillMaxSize()) {
        repeat(2) { row ->
            Row(modifier = Modifier.weight(1f)) {
                repeat(2) { col ->
                    val index = row * 2 + col
                    ThumbImage(
                        context = context,
                        uri = filled.getOrNull(index),
                        modifier = Modifier.weight(1f).fillMaxSize().padding(0.5.dp),
                        sizePx = 256,
                    )
                }
            }
        }
    }
}

// ================================================================ 二级：图片网格

@Composable
fun TodoLevel2(
    state: UiState,
    level: LevelState,
    onBack: () -> Unit,
    onFilter: (String) -> Unit,
    onSelectAll: () -> Unit,
    onToggleItem: (Long) -> Unit,
    onShowInfo: (MediaItem) -> Unit,
) {
    val album: AlbumTodoUi? = state.albumTodoAll().firstOrNull { it.name == level.album }
    if (album == null) {
        // 图集已被清空（例如整册压缩后隐藏）时自动退回一级，避免卡在空页面
        LaunchedEffect(level.album) { onBack() }
        return
    }
    val filtered = album.items.applyTodoFilter(level.filter)
    val actionable = filtered.filter { it.compressible }
    val allSelected = actionable.isNotEmpty() && actionable.all { it.id in level.pickedItems }

    Column(modifier = Modifier.fillMaxSize()) {
        AppBar(
            title = album.name,
            subtitle = "${formatCount(album.count)} 项 · ${formatSize(album.bytes)}",
            smallTitle = true,
            navigation = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回图集列表") } },
            navigationSpacing = 12.dp,
            actions = {
                SelectAllAction(allSelected = allSelected, enabled = actionable.isNotEmpty(), onClick = onSelectAll, modifier = Modifier.padding(end = 12.dp))
            },
        )
        FilterChips(
            options = listOf("all" to "全部", "photo" to "照片", "live" to "实况", "video" to "视频", "skip" to "不支持"),
            selected = level.filter,
            onSelect = onFilter,
        )
        if (filtered.isEmpty()) {
            EmptyState("该筛选下没有条目", "换一个筛选条件看看")
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filtered, key = { it.id }) { item ->
                val pending = state.recoveryEntries.any { it.path == item.dataPath }
                MediaTile(
                    modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = motionTween()),
                    uri = item.uri,
                    picked = item.id in level.pickedItems,
                    selectable = item.compressible,
                    badge = when {
                        pending -> "处理失败" to Color(0xB8000000)
                        !item.compressible -> "不支持" to Color(0xB8000000)
                        item.kind == MediaKind.LIVE_PHOTO -> "实况" to Color(0x9E000000)
                        item.kind == MediaKind.VIDEO -> "视频" to Color(0x9E000000)
                        else -> null
                    },
                    isVideo = item.kind == MediaKind.VIDEO,
                    grayed = !item.compressible,
                    metaLeft = item.kind.label,
                    metaRight = if (item.compressible) formatSize(item.size) else "—",
                    onClick = {
                        if (item.compressible) onToggleItem(item.id) else onShowInfo(item)
                    },
                    onLongClick = { onShowInfo(item) },
                )
            }
        }
    }
}

@Composable
fun DoneLevel2(
    state: UiState,
    level: LevelState,
    onBack: () -> Unit,
    onFilter: (String) -> Unit,
    onSelectAll: () -> Unit,
    onToggleItem: (Long) -> Unit,
    onShowInfo: (DoneMedia) -> Unit,
) {
    val album: AlbumDoneUi? = state.albumDoneAll().firstOrNull { it.name == level.album }
    if (album == null) {
        LaunchedEffect(level.album) { onBack() }
        return
    }
    val filtered = album.items.applyDoneFilter(level.filter)
    val actionable = filtered.filter { it.restorable }
    val allSelected = actionable.isNotEmpty() && actionable.all { it.record.mediaStoreId in level.pickedItems }
    val now = System.currentTimeMillis()

    Column(modifier = Modifier.fillMaxSize()) {
        AppBar(
            title = album.name,
            subtitle = album.sizeLine,
            smallTitle = true,
            navigation = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回图集列表") } },
            navigationSpacing = 12.dp,
            actions = {
                SelectAllAction(allSelected = allSelected, enabled = actionable.isNotEmpty(), onClick = onSelectAll, modifier = Modifier.padding(end = 12.dp))
            },
        )
        FilterChips(
            options = listOf("all" to "全部", "restorable" to "可还原", "expired" to "已超期", "skipped" to "已跳过"),
            selected = level.filter,
            onSelect = onFilter,
        )
        if (filtered.isEmpty()) {
            EmptyState("该筛选下没有条目", "换一个筛选条件看看")
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filtered, key = { it.record.id }) { dm ->
                val restorable = dm.restorable
                val days = daysLeft(dm.record.restoreDeadlineMs, now)
                MediaTile(
                    modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = motionTween()),
                    uri = dm.item?.uri,
                    picked = dm.record.mediaStoreId in level.pickedItems,
                    selectable = restorable,
                    badge = when {
                        dm.skipped -> "已跳过" to Color(0x9E000000)
                        dm.adopted -> "已压缩" to Color(0x9E000000)
                        dm.record.status == CompressedItemEntity.STATUS_PURGED -> "已清理" to Color(0xB8000000)
                        days < 0 -> "已超期" to Color(0xB8000000)
                        else -> "已压缩" to MaterialTheme.appColors.success
                    },
                    isVideo = dm.kind == MediaKind.VIDEO,
                    grayed = !restorable && !dm.skipped,
                    metaLeft = if (dm.skipped) dm.kind.label else if (dm.adopted) "—" else formatSize(dm.originalSize),
                    metaRight = formatSize(dm.compressedSize),
                    onClick = { if (restorable) onToggleItem(dm.record.mediaStoreId) else onShowInfo(dm) },
                    onLongClick = { onShowInfo(dm) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(
    modifier: Modifier = Modifier,
    uri: android.net.Uri?,
    picked: Boolean,
    selectable: Boolean,
    badge: Pair<String, Color>?,
    isVideo: Boolean,
    grayed: Boolean,
    metaLeft: String,
    metaRight: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val selectionEdge = motionColor(if (picked) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f), "mediaSelection")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .border(if (picked) 2.dp else 1.dp, selectionEdge, RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { selected = picked }
            // 键盘等价路径（AC16）：Enter 打开信息、Space 勾选
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> { onLongClick(); true }
                    Key.Spacebar -> { onClick(); true }
                    else -> false
                }
            },
    ) {
        ThumbImage(
            context = context,
            uri = uri,
            modifier = Modifier.fillMaxSize().let {
                if (grayed) it.background(Color(0x33000000)) else it
            },
            sizePx = 256,
        )
        if (grayed) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0x66000000)))
        }
        if (isVideo && !grayed) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(999.dp))
                        .background(Color(0x85000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp).padding(start = 2.dp))
                }
            }
        }
        badge?.let { (text, color) ->
            Badge(text = text, color = color, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
        }
        if (selectable) {
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                PickCircle(checked = picked, size = 22)
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0x99000000), Color(0xDD000000))))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(metaLeft, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1)
            Text(metaRight, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1)
        }
    }
}

@Composable
private fun FilterChips(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val interactions = remember(value) { MutableInteractionSource() }
            val pressed by interactions.collectIsPressedAsState()
            val scale = motionFloat(if (pressed && LocalMotionEnabled.current) 0.97f else 1f, "filterPress", AppMotion.Press)
            val fill = motionColor(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f), "filterFill")
            val edge = motionColor(Color.White.copy(alpha = if (active) 0.35f else 0.12f), "filterEdge")
            val text = motionColor(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.appColors.onSurfaceMuted, "filterText")
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .selectable(
                        selected = active,
                        role = Role.Tab,
                        interactionSource = interactions,
                        indication = null,
                        onClick = { onSelect(value) },
                    )
                    .heightIn(min = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                // 视觉胶囊 32dp；保留外层 48dp 触摸范围，文字与胶囊共同居中。
                Box(
                    modifier = Modifier
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .clip(RoundedCornerShape(16.dp))
                        .background(fill)
                        .border(1.dp, edge, RoundedCornerShape(16.dp))
                        // 外层保留 48dp 点击范围；中心扩散反馈仅绘制在内层胶囊，避免外层点击坐标造成偏移。
                        .indication(interactionSource = interactions, indication = ripple(bounded = false))
                        .heightIn(min = 32.dp)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = text,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectAllAction(
    allSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.semantics { selected = allSelected }) {
        Icon(Icons.Filled.CheckCircle, contentDescription = if (allSelected) "取消全选" else "全选")
    }
}
