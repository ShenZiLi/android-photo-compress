package com.photocompress.app.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photocompress.app.data.media.MediaKind
import com.photocompress.app.ui.Totals
import com.photocompress.app.ui.UiState
import com.photocompress.app.ui.comparisonRatios
import com.photocompress.app.ui.components.AppBar
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
            title = "照片压缩",
            subtitle = when {
                state.fullScan -> "正在扫描媒体库…"
                state.scanning -> "正在同步媒体变更…"
                else -> {
                    val time = if (state.lastScanAt > 0) {
                        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(state.lastScanAt))
                    } else "—"
                    "已扫描本机媒体 · $time"
                }
            },
            actions = {
                IconButton(onClick = onRescan) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重新扫描媒体库")
                }
            },
        )

        if (state.fullScan) {
            ScanProgressCard(state, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(14.dp))
        }
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            HeroCard(totals)
            Spacer(Modifier.height(14.dp))
            StatsRow(totals)
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(20.dp))
            CardSurface(modifier = Modifier.fillMaxWidth()) {
                // 只留垂直内边距：水平内边距交给 CompareMetric 自己分配，
                // 好让下方图例与 StatsRow 的卡片共用同一条水平网格（见 CompareMetric）。
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

/** 首次扫描进度：给出可见进度条与已扫描数量，避免长时间只显示「正在扫描媒体库…」。 */
@Composable
private fun ScanProgressCard(state: UiState, modifier: Modifier = Modifier) {
    CardSurface(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("正在扫描媒体库", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.scanTotal > 0) "${state.scanDone} / ${state.scanTotal}" else "统计中…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.appColors.onSurfaceMuted,
                )
            }
            Spacer(Modifier.height(10.dp))
            if (state.scanTotal > 0) {
                LinearProgressIndicator(
                    progress = { state.scanProgress },
                    modifier = Modifier.fillMaxWidth(),
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

    CardSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindDonut(segments = segments, totalBytes = totalBytes)
                Spacer(Modifier.width(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    segments.forEach { seg -> KindLegendRow(seg, totalBytes) }
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
                    "${totals.pct.roundToInt()}%",
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
            for (s in segments) {
                if (s.bytes <= 0L) continue
                val sweep = 360f * s.bytes / totalBytes
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
private fun KindLegendRow(seg: KindSegment, totalBytes: Long) {
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
        Text(seg.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Text(
            "${pct.roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            formatSize(seg.bytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.appColors.onSurfaceMuted,
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
        // 两卡等宽，内容与下方对比图例对齐；数值仍保持单行。
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
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
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.appColors.onSurfaceMuted,
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
    val (todoRatio, doneRatio) = comparisonRatios(todoRaw, doneRaw)
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(12.dp)
                .clip(CircleShape),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(
                modifier = Modifier.weight(todoRatio).fillMaxWidth().height(12.dp)
                    .background(todoColor),
            )
            Box(
                modifier = Modifier.weight(doneRatio).fillMaxWidth().height(12.dp)
                    .background(doneColor),
            )
        }
        Spacer(Modifier.height(10.dp))
        // 图例与 StatsRow 的卡片共用同一条水平网格——同宽（外层已无水平内边距，
        // 故可直接 fillMaxWidth）、同间隔（12dp）、同内容内缩（16dp）。
        // 这样两处图例的「已压缩」就会和顶部统计卡的「已压缩」落在同一条竖直线上；
        // 若改用 spacedBy 紧挨排列，右栏起点会随左栏文字长度浮动，上下互不对齐。
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
