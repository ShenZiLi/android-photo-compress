package com.photocompress.app.ui

import java.util.Locale
import java.util.concurrent.TimeUnit

/** 体积格式化：与原型 fmtSize 语义一致（B → KB/MB/GB）。 */
fun formatSize(bytes: Long): String {
    if (bytes < 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format(Locale.CHINA, "%.2f GB", gb)
        mb >= 1 -> String.format(Locale.CHINA, "%.1f MB", mb)
        kb >= 1 -> String.format(Locale.CHINA, "%.0f KB", kb)
        else -> "$bytes B"
    }
}

/** 紧凑体积：无单位间空格，去掉小数末尾的零（4.00 GB → 4GB）。 */
fun formatCompactSize(bytes: Long): String {
    val size = formatSize(bytes)
    val number = size.substringBefore(' ').let {
        if ('.' in it) it.trimEnd('0').trimEnd('.') else it
    }
    return number + size.substringAfter(' ')
}

/** 千分位数字。 */
fun formatCount(value: Int): String = String.format(Locale.CHINA, "%,d", value)

fun formatDateTime(ms: Long): String {
    if (ms <= 0) return "未知"
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    return fmt.format(java.util.Date(ms))
}

/** 剩余可还原天数（负数表示已超期）。 */
fun daysLeft(deadlineMs: Long, nowMs: Long): Int =
    TimeUnit.MILLISECONDS.toDays(deadlineMs - nowMs).toInt()
