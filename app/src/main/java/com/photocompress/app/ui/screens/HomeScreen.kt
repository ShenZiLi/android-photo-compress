package com.photocompress.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import com.photocompress.app.ui.components.GlassIconButton as IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photocompress.app.R
import com.photocompress.app.data.media.MediaKind
import com.photocompress.app.ui.Totals
import com.photocompress.app.ui.UiState
import com.photocompress.app.ui.comparisonRatios
import com.photocompress.app.ui.components.AppBar
import com.photocompress.app.ui.components.AppMotion
import com.photocompress.app.ui.components.motionFloat
import com.photocompress.app.ui.components.motionTween
import com.photocompress.app.ui.components.CardSurface
import com.photocompress.app.ui.formatCount
import com.photocompress.app.ui.formatSize
import com.photocompress.app.ui.totals
import com.photocompress.app.ui.theme.appColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HomeScreen(state: UiState, onRescan: () -> Unit) {
    val totals = state.totals()
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        AppBar(
            title = stringResource(R.string.app_name),
            subtitle = when {
                state.fullScan -> "正在扫描相册…"
                state.scanning -> "正在同步相册变更…"
                else -> {
                    val time = if (state.lastScanAt > 0) {
                        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(state.lastScanAt))
                    } else "—"
                    "已扫描本机相册 · $time"
                }
            },
            actions = {
                IconButton(onClick = onRescan) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重新扫描相册")
                }
            },
        )

        AnimatedVisibility(visible = state.fullScan, enter = fadeIn(motionTween()), exit = fadeOut(motionTween(AppMotion.Exit))) {
            Column {
                ScanProgressCard(state, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(14.dp))
            }
        }
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            HeroCard(totals)
            Spacer(Modifier.height(14.dp))
            StatsRow(totals)
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(20.dp))
            CardSurface(modifier = Modifier.fillMaxWidth()) {
                // 水平内边距由 CompareMetric 分配给对比条和图例。
                Column(modifier = Modifier.padding(vertical = 16.dp)) {
                    CompareMetric(
                        title = "数量",
                        total = "${formatCount(totals.todoCount + totals.doneCount)} 项",
                        todoValue = formatCount(totals.todoCount),
                        doneValue = formatCount(totals.doneCount),
                        todoRaw = totals.todoCount.toDouble(),
                        doneRaw = totals.doneCount.toDouble(),
                    )
                    Spacer(Modifier.height(22.dp))
                    CompareMetric(
                        title = "占用",
                        total = formatSize(totals.todoBytes + totals.after),
                        todoValue = formatSize(totals.todoBytes),
                        doneValue = formatSize(totals.after),
                        todoRaw = totals.todoBytes.toDouble(),
                        doneRaw = totals.after.toDouble(),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 首次扫描进度：给出可见进度条与已扫描数量，避免长时间只显示「正在扫描相册…」。 */
@Composable
private fun ScanProgressCard(state: UiState, modifier: Modifier = Modifier) {
    val progress = motionFloat(state.scanProgress.coerceIn(0f, 1f), "scanProgress", AppMotion.Progress)
    CardSurface(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("正在扫描相册", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.scanTotal > 0) "${state.scanDone} / ${state.scanTotal}" else "统计中…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.appColors.onSurfaceMuted,
                )
            }
            Spacer(Modifier.height(10.dp))
            if (state.scanTotal > 0) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    drawStopIndicator = {},
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "首次扫描需要读取每个文件的元信息，完成后会缓存结果",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.appColors.onSurfaceMuted,
            )
        }
    }
}

/**
 * 首页主卡：按「图片 / 实况 / 视频」三类展示**当前实际占用**占比。
 *
 * 口径说明：未压缩媒体按原始大小、已压缩媒体按压缩后大小求和，
 * 因此饼图反映的是"此刻占了多少磁盘"，而非"压缩前有多少"。
 * 下方保留「已省」摘要，显示本应用节省的体积。
 */
@Composable
private fun HeroCard(totals: Totals) {
    val colors = MaterialTheme.appColors
    val segments = listOf(
        KindSegment(MediaKind.PHOTO, "图片", colors.kindPhoto, totals.kindBytes[MediaKind.PHOTO] ?: 0L),
        KindSegment(MediaKind.LIVE_PHOTO, "实况", colors.kindLive, totals.kindBytes[MediaKind.LIVE_PHOTO] ?: 0L),
        KindSegment(MediaKind.VIDEO, "视频", colors.kindVideo, totals.kindBytes[MediaKind.VIDEO] ?: 0L),
    )
    val totalBytes = segments.sumOf { it.bytes }
    val measurer = rememberTextMeasurer()
    val percentStyle = MaterialTheme.typography.labelMedium.copy(
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    )
    val sizeStyle = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
    // 三行共享按当前字体测得的列宽，避免不同位数/单位挤动百分比列。
    val percentWidth = with(LocalDensity.current) {
        measurer.measure("100%", percentStyle, maxLines = 1, softWrap = false).size.width.toDp()
    }
    val sizeWidth = with(LocalDensity.current) {
        segments.maxOf {
            measurer.measure(formatSize(it.bytes), sizeStyle, maxLines = 1, softWrap = false).size.width
        }.toDp()
    }

    CardSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindDonut(segments = segments, totalBytes = totalBytes)
                Spacer(Modifier.width(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    segments.forEach { seg -> KindLegendRow(seg, totalBytes, percentWidth, sizeWidth) }
                }
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = colors.divider)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已省 ${formatSize(totals.saved)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "-${totals.pct.roundToInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.dataDone,
                )
            }
        }
    }
}

/** 饼图的一段：一种媒体类型的展示名、配色与占用字节数。 */
private data class KindSegment(
    val kind: MediaKind,
    val label: String,
    val color: Color,
    val bytes: Long,
)

/**
 * 三段环形图，按占用字节数分配弧长，中心显示总占用。
 * 用 `StrokeCap.Butt` 而非圆头：圆头会让相邻段互相压边，
 * 视觉上扭曲实际占比（段越短偏差越明显）。
 */
@Composable
private fun KindDonut(segments: List<KindSegment>, totalBytes: Long) {
    val track = MaterialTheme.appColors.surfaceSunken
    val muted = MaterialTheme.appColors.onSurfaceMuted
    val sweeps = segments.map { motionFloat(if (totalBytes > 0L) 360f * it.bytes / totalBytes else 0f, "kindArc${it.kind}") }
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(116.dp)) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Butt),
            )
            if (totalBytes <= 0L) return@Canvas
            var start = -90f
            for ((index, s) in segments.withIndex()) {
                val sweep = sweeps[index]
                if (sweep <= 0f) continue
                drawArc(
                    color = s.color,
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("总占用", style = MaterialTheme.typography.labelSmall, color = muted)
            Text(
                formatSize(totalBytes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 图例一行：色点 + 名称 + 占比 + 体积。 */
@Composable
private fun KindLegendRow(seg: KindSegment, totalBytes: Long, percentWidth: Dp, sizeWidth: Dp) {
    val pct = if (totalBytes > 0L) seg.bytes.toFloat() / totalBytes * 100f else 0f
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                .background(seg.color),
        )
        Spacer(Modifier.width(8.dp))
        Text(seg.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).alignByBaseline())
        Text(
            "${pct.roundToInt()}%",
            modifier = Modifier.width(percentWidth).alignByBaseline(),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            formatSize(seg.bytes),
            modifier = Modifier.width(sizeWidth).alignByBaseline(),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.appColors.onSurfaceMuted,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun StatsRow(totals: Totals) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard(
            modifier = Modifier.weight(1f),
            dotColor = MaterialTheme.appColors.dataTodo,
            label = "未压缩",
            value = "${formatCount(totals.todoCount)} 项",
            meta = formatSize(totals.todoBytes),
        )
        StatCard(
            modifier = Modifier.weight(1f),
            dotColor = MaterialTheme.appColors.dataDone,
            label = "已压缩",
            value = "${formatCount(totals.doneCount)} 项",
            meta = formatSize(totals.before),
        )
    }
}

@Composable
private fun StatCard(
    modifier: Modifier,
    dotColor: Color,
    label: String,
    value: String,
    meta: String,
) {
    CardSurface(modifier = modifier) {
        // 两卡等宽，标题、数量与体积均在各自卡片内居中。
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.appColors.onSurfaceMuted,
                )
            }
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = value,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium.copy(
                    color = LocalContentColor.current,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 12.sp,
                    maxFontSize = MaterialTheme.typography.titleMedium.fontSize,
                    stepSize = 0.5.sp,
                ),
            )
            Text(
                meta,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.appColors.onSurfaceMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CompareMetric(
    title: String,
    total: String,
    todoValue: String,
    doneValue: String,
    todoRaw: Double,
    doneRaw: Double,
) {
    val todoColor = MaterialTheme.appColors.dataTodo
    val doneColor = MaterialTheme.appColors.dataDone
    // 比例必须用原始数值（字节/个数）计算，不能用格式化后的字符串
    val (todoRatio, _) = comparisonRatios(todoRaw, doneRaw)
    val ratio = motionFloat(todoRatio, "compareRatio$title")
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("${title}对比", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(total, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.appColors.onSurfaceMuted)
        }
        Spacer(Modifier.height(10.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(12.dp)
                .clip(CircleShape),
        ) {
            val gap = 2.dp.toPx()
            val left = (size.width - gap).coerceAtLeast(0f) * ratio.coerceIn(0f, 1f)
            drawRect(todoColor, size = Size(left, size.height))
            drawRect(doneColor, topLeft = androidx.compose.ui.geometry.Offset(left + gap, 0f), size = Size((size.width - left - gap).coerceAtLeast(0f), size.height))
        }
        Spacer(Modifier.height(10.dp))
        // 对比图例保持两列等宽，右列起点不随左列文字长度浮动。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LegendItem(todoColor, "未压缩 $todoValue", Modifier.weight(1f).padding(start = 16.dp))
            LegendItem(doneColor, "已压缩 $doneValue", Modifier.weight(1f).padding(start = 16.dp))
        }
    }
}

@Composable
private fun LegendItem(color: Color, text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.appColors.onSurfaceMuted)
    }
}
