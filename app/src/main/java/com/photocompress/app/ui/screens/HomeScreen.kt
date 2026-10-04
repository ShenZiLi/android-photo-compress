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
import com.photocompress.app.ui.components.AppBar
import com.photocompress.app.ui.components.CardSurface
import com.photocompress.app.ui.components.SectionTitle
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
            subtitle = if (state.scanning) "正在扫描媒体库…" else {
                val time = if (state.lastScanAt > 0) {
                    SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(state.lastScanAt))
                } else "—"
                "已扫描本机媒体 · $time"
            },
            actions = {
                IconButton(onClick = onRescan) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重新扫描媒体库")
                }
            },
        )

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            HeroCard(totals)
            Spacer(Modifier.height(14.dp))
            StatsRow(totals)
        }

        SectionTitle("数量与体积对比")
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            CardSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    CompareMetric(
                        title = "数量",
                        total = "${formatCount(totals.todoCount + totals.doneCount)} 项",
                        todoValue = "${formatCount(totals.todoCount)}",
                        doneValue = "${formatCount(totals.doneCount)}",
                    )
                    Spacer(Modifier.height(22.dp))
                    CompareMetric(
                        title = "体积",
                        total = formatSize(totals.todoBytes + totals.after),
                        todoValue = formatSize(totals.todoBytes),
                        doneValue = formatSize(totals.after),
                        doneExtra = "（原 ${formatSize(totals.before)}）",
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
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
                Spacer(Modifier.height(4.dp))
                val allBytes = totals.before + totals.todoBytes
                val reduced = if (totals.before > 0) totals.saved * 100 / totals.before else 0
                Text(
                    "占全部媒体原始大小（${formatSize(allBytes)}）的 ${"%.1f".format(totals.pct)}%，" +
                        "已压缩部分体积减少 $reduced%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.onSurfaceMuted,
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
    doneExtra: String? = null,
) {
    val todoColor = MaterialTheme.appColors.dataTodo
    val doneColor = MaterialTheme.appColors.dataDone
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("${title}对比", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(total, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.appColors.onSurfaceMuted)
        }
        Spacer(Modifier.height(10.dp))
        // 比例来自两端数值
        val todoNum = todoValue.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 0f
        val doneNum = doneValue.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 0f
        val sum = todoNum + doneNum
        val todoRatio = if (sum > 0) todoNum / sum else 0f
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(CircleShape),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(
                modifier = Modifier.weight(todoRatio.coerceAtLeast(0.0001f)).fillMaxWidth().height(12.dp)
                    .background(todoColor),
            )
            Box(
                modifier = Modifier.weight((1f - todoRatio).coerceAtLeast(0.0001f)).fillMaxWidth().height(12.dp)
                    .background(doneColor),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(todoColor, "未压缩 $todoValue")
            LegendItem(doneColor, "已压缩 $doneValue${doneExtra ?: ""}")
        }
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.appColors.onSurfaceMuted)
    }
}
