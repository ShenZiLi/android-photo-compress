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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
                        title = "体积",
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

@Composable
private fun HeroCard(totals: Totals) {
    CardSurface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProgressRing(percent = totals.pct)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    "已节约 ${formatSize(totals.saved)}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ProgressRing(percent: Float) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.appColors.surfaceSunken
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(100.dp)) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Butt),
            )
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * (percent.coerceIn(0f, 100f) / 100f),
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            text = "${percent.toInt()}%",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StatsRow(totals: Totals) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard(
            modifier = Modifier.weight(1f),
            dotColor = MaterialTheme.appColors.dataTodo,
            label = "未压缩",
            value = "${formatCount(totals.todoCount)} 项",
            meta = "共 ${formatSize(totals.todoBytes)}",
        )
        StatCard(
            modifier = Modifier.weight(1f),
            dotColor = MaterialTheme.appColors.dataDone,
            label = "已压缩",
            value = "${formatCount(totals.doneCount)} 项",
            meta = "${formatSize(totals.before)} → ${formatSize(totals.after)}",
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
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                Spacer(Modifier.width(8.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.appColors.onSurfaceMuted,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
