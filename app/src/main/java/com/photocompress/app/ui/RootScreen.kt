package com.photocompress.app.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photocompress.app.data.media.MediaItem
import com.photocompress.app.ui.components.KeyValueRow
import com.photocompress.app.ui.components.GlassButton
import com.photocompress.app.ui.components.GlassScene
import com.photocompress.app.ui.components.GlassSurface
import com.photocompress.app.ui.components.ThumbImage
import com.photocompress.app.ui.screens.AlbumFilterScreen
import com.photocompress.app.ui.screens.CompressRatioScreen
import com.photocompress.app.ui.screens.DoneLevel1
import com.photocompress.app.ui.screens.DoneLevel2
import com.photocompress.app.ui.screens.HomeScreen
import com.photocompress.app.ui.screens.SettingsScreen
import com.photocompress.app.ui.screens.TodoLevel1
import com.photocompress.app.ui.screens.TodoLevel2
import com.photocompress.app.ui.screens.TrashScreen
import com.photocompress.app.ui.theme.appColors
import kotlinx.coroutines.delay

private data class SheetData(
    val title: String,
    val uri: Uri?,
    val rows: List<Pair<String, String>>,
)

private data class DialogData(
    val title: String,
    val body: String,
    val okLabel: String,
    val danger: Boolean = false,
    val onConfirm: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AppViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val batch by vm.batch.collectAsStateWithLifecycle()

    var toast by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<SheetData?>(null) }
    var dialog by remember { mutableStateOf<DialogData?>(null) }

    LaunchedEffect(Unit) {
        vm.messages.collect { msg ->
            toast = msg
            delay(3200)
            toast = null
        }
    }

    val todoSummary = state.todoSelection()
    val doneSummary = state.doneSelection()

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        bottomBar = {
            Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
                if (state.page == AppPage.TODO || state.page == AppPage.DONE) {
                    ActionBar(
                        isTodo = state.page == AppPage.TODO,
                        level = if (state.page == AppPage.TODO) state.todo.level else state.done.level,
                        summary = if (state.page == AppPage.TODO) todoSummary else doneSummary,
                        batch = batch,
                        onAction = {
                            if (state.page == AppPage.TODO) {
                                dialog = DialogData(
                                    title = if (state.todo.level == 1) "压缩 ${todoSummary.albumCount()} 个图集" else "压缩所选图片",
                                    body = "压缩${enumerateCounts(todoSummary.kindCounts)}。原文件保留 30 天，可随时还原。",
                                    okLabel = "压缩",
                                    onConfirm = { vm.compress(todoSummary) },
                                )
                            } else {
                                dialog = DialogData(
                                    title = if (state.done.level == 1) "还原 ${doneSummary.albumCount()} 个图集" else "还原所选图片",
                                    body = "还原${enumerateCounts(doneSummary.kindCounts)}。",
                                    okLabel = "还原",
                                    onConfirm = { vm.restore(doneSummary) },
                                )
                            }
                        },
                    )
                }
                GlassSurface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), radius = 32.dp) {
                    Row(modifier = Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val navPage = if (state.page == AppPage.ALBUM_FILTER || state.page == AppPage.COMPRESS_RATIO) AppPage.SETTINGS else state.page
                        NavItem(AppPage.HOME, "首页", navPage) { vm.go(it) }
                        NavItem(AppPage.TODO, "未压缩", navPage) { vm.go(it) }
                        NavItem(AppPage.DONE, "已压缩", navPage) { vm.go(it) }
                        NavItem(AppPage.SETTINGS, "设置", navPage) { vm.go(it) }
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state.page) {
                AppPage.HOME -> HomeScreen(state) { vm.refresh() }

                AppPage.TODO -> if (state.todo.level == 1) {
                    TodoLevel1(
                        state = state,
                        onOpenAlbum = { vm.openAlbum(AppPage.TODO, it) },
                        onToggleAlbum = { vm.toggleAlbum(AppPage.TODO, it) },
                        onSelectAll = { vm.selectAllAlbums(AppPage.TODO) },
                    )
                } else {
                    TodoLevel2(
                        state = state,
                        level = state.todo,
                        onBack = { vm.backToAlbums(AppPage.TODO) },
                        onFilter = { vm.setFilter(AppPage.TODO, it) },
                        onSelectAll = { vm.selectAllItems(AppPage.TODO) },
                        onToggleItem = { vm.toggleItem(AppPage.TODO, it) },
                        onShowInfo = { sheet = todoSheet(it) },
                    )
                }

                AppPage.DONE -> if (state.done.level == 1) {
                    DoneLevel1(
                        state = state,
                        onOpenAlbum = { vm.openAlbum(AppPage.DONE, it) },
                        onToggleAlbum = { vm.toggleAlbum(AppPage.DONE, it) },
                        onOpenTrash = { vm.go(AppPage.TRASH) },
                    )
                } else {
                    DoneLevel2(
                        state = state,
                        level = state.done,
                        onBack = { vm.backToAlbums(AppPage.DONE) },
                        onFilter = { vm.setFilter(AppPage.DONE, it) },
                        onSelectAll = { vm.selectAllItems(AppPage.DONE) },
                        onToggleItem = { vm.toggleItem(AppPage.DONE, it) },
                        onShowInfo = { sheet = it.toSheet() },
                    )
                }

                AppPage.TRASH -> TrashScreen(
                    state = state,
                    onBack = { vm.go(AppPage.DONE) },
                    onPurgeAll = {
                        dialog = DialogData(
                            title = "清理回收站",
                            body = "将永久删除回收站中的原始文件备份。此操作不可撤销；已压缩的照片本身不受影响，但这些照片将无法再还原到压缩前的状态。",
                            okLabel = "永久删除备份",
                            danger = true,
                            onConfirm = { vm.purgeAllBackups() },
                        )
                    },
                )

                AppPage.SETTINGS -> SettingsScreen(
                    state = state,
                    onOpenTrash = { vm.go(AppPage.TRASH) },
                    onOpenAlbumFilter = { vm.go(AppPage.ALBUM_FILTER) },
                    onOpenCompressRatio = { vm.go(AppPage.COMPRESS_RATIO) },
                )

                AppPage.COMPRESS_RATIO -> CompressRatioScreen(
                    state = state,
                    onBack = { vm.go(AppPage.SETTINGS) },
                    onSetTier = { kind, tier -> vm.setTier(kind, tier) },
                    onSetLiveVideoTier = { vm.setLiveVideoTier(it) },
                )

                AppPage.ALBUM_FILTER -> AlbumFilterScreen(
                    state = state,
                    onBack = { vm.go(AppPage.SETTINGS) },
                    onSetShown = { name, shown -> vm.setAlbumShown(name, shown) },
                )
            }

            toast?.let { msg ->
                ToastOverlay(msg, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
            }
        }
    }

    sheet?.let { data ->
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        ) {
            GlassScene(modifier = Modifier.fillMaxWidth()) { InfoSheetContent(data) }
        }
    }

    dialog?.let { data ->
        BasicAlertDialog(
            onDismissRequest = { dialog = null },
        ) {
            GlassScene(modifier = Modifier.fillMaxWidth()) {
                GlassSurface(modifier = Modifier.fillMaxWidth(), radius = 28.dp) {
                    Column(modifier = Modifier
                        .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.8f)
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    ) {
                        Text(data.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                        Spacer(Modifier.height(16.dp))
                        Text(data.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.appColors.onSurfaceMuted)
                        Spacer(Modifier.height(24.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { dialog = null }) { Text("取消") }
                            Spacer(Modifier.width(8.dp))
                            GlassButton(
                                onClick = {
                                    dialog = null
                                    data.onConfirm()
                                },
                                containerColor = if (data.danger) MaterialTheme.appColors.danger else MaterialTheme.colorScheme.primary,
                                contentColor = if (data.danger) MaterialTheme.appColors.onDanger else MaterialTheme.colorScheme.onPrimary,
                            ) { Text(data.okLabel, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
        }
    }
}

private fun SelectionSummary.albumCount(): Int = albumNames.size.coerceAtLeast(1)

@Composable
private fun RowScope.NavItem(page: AppPage, label: String, current: AppPage, onGo: (AppPage) -> Unit) {
    val selected = page == current
    val scheme = MaterialTheme.colorScheme
    val tint = if (selected) scheme.primary else MaterialTheme.appColors.onSurfaceMuted
    Column(
        modifier = Modifier.weight(1f)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.verticalGradient(listOf(
                if (selected) scheme.primary.copy(alpha = 0.23f) else Color.Transparent,
                if (selected) scheme.primary.copy(alpha = 0.10f) else Color.Transparent,
            )))
            .border(1.dp, if (selected) Color.White.copy(alpha = 0.24f) else Color.Transparent, RoundedCornerShape(26.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = { onGo(page) })
            .heightIn(min = 64.dp)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = when (page) {
                AppPage.HOME -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
                AppPage.TODO -> if (selected) Icons.Filled.PhotoLibrary else Icons.Outlined.PhotoLibrary
                AppPage.DONE -> if (selected) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle
                else -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
            },
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun ActionBar(
    isTodo: Boolean,
    level: Int,
    summary: SelectionSummary,
    batch: BatchState?,
    onAction: () -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (batch != null) {
                    Text(batch.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { batch.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${batch.done} / ${batch.total}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.appColors.onSurfaceMuted,
                    )
                } else {
                    Text(
                        if (summary.empty) "未选择" else if (isTodo) "已选 ${summary.count} 项 · ${formatSize(summary.bytes)}"
                        else "已选 ${summary.count} 项可还原",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // 未选择时，一级图集页及未压缩页二级不展示提示：分支输出空值，
                    // 连同 Text 一起不渲染（否则会留下一行空白高度）。
                    // 注意不能直接删分支——删掉 isTodo 那条会让未压缩二级掉进
                    // else 分支、错显「仅 30 天内可还原」。
                    val hint = when {
                        !summary.empty && isTodo ->
                            "预计可节约约 ${formatSize((summary.bytes * 0.37).toLong())}"
                        !summary.empty && !isTodo -> "将恢复原始画质与体积"
                        level == 1 -> ""
                        isTodo -> ""
                        else -> "仅 30 天内可还原"
                    }
                    if (hint.isNotEmpty()) {
                        Text(
                            hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.appColors.onSurfaceMuted,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            GlassButton(
                onClick = onAction,
                enabled = !summary.empty && batch == null,
            ) {
                Text(if (isTodo) "压缩" else "还原", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ToastOverlay(text: String, modifier: Modifier = Modifier) {
    GlassSurface(modifier = modifier.padding(horizontal = 16.dp), radius = 20.dp) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun InfoSheetContent(data: SheetData) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.8f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text(data.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        ThumbImage(
            context = context,
            uri = data.uri,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(14.dp)),
            sizePx = 512,
        )
        Spacer(Modifier.height(12.dp))
        data.rows.forEach { (k, v) -> KeyValueRow(k, v) }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- 信息面板数据

private fun todoSheet(item: MediaItem): SheetData = SheetData(
    title = item.displayName,
    uri = item.uri,
    rows = buildList {
        add("文件名" to item.displayName)
        add("类型" to item.kind.fullLabel)
        add("所在图集" to item.bucketName)
        add("大小" to formatSize(item.size))
        add("拍摄时间" to formatDateTime(item.dateTakenMs))
        add("尺寸" to "${item.width} × ${item.height}")
        add("可压缩" to if (item.compressible) "是（按当前质量档位）" else "否")
        item.skipReason?.let { add("跳过原因" to it) }
    },
)

private fun DoneMedia.toSheet(): SheetData = SheetData(
    title = displayName,
    uri = item?.uri,
    rows = buildList {
        add("文件名" to displayName)
        add("类型" to kind.fullLabel)
        add("所在图集" to bucketName)
        if (adopted) {
            add("大小" to "${formatSize(compressedSize)}（压缩前大小未知）")
            add("识别方式" to "由文件内压缩标记识别（账本已丢失）")
        } else {
            add("大小" to "${formatSize(originalSize)} → ${formatSize(compressedSize)}")
            add("已节约" to formatSize(saved))
        }
        add("拍摄时间" to formatDateTime(dateTakenMs))
        if (!adopted) {
            add("压缩时间" to formatDateTime(record.compressedAtMs))
            record.codecUsed?.let { add("编码/档位" to "$it · ${qualityTier.label}") }
        }
        add(
            "备份状态" to when {
                adopted -> "无备份（由文件标记识别）"
                record.status == com.photocompress.app.data.ledger.CompressedItemEntity.STATUS_PURGED -> "已清理，无法还原"
                !restorable -> "已超期，无法还原"
                else -> "剩余 ${daysLeft(record.restoreDeadlineMs, System.currentTimeMillis())} 天可还原"
            }
        )
    },
)
