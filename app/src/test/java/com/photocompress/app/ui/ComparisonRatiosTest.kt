package com.photocompress.app.ui

import com.photocompress.app.data.media.ContainerFormat
import com.photocompress.app.data.media.MediaClassifier
import com.photocompress.app.data.media.MediaKind
import com.photocompress.app.data.media.QualityTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首页对比条的比例计算：必须按原始数值换算，不能受格式化单位影响。 */
class ComparisonRatiosTest {

    private fun ratioOf(left: Double, right: Double): Float = comparisonRatios(left, right).first

    @Test
    fun `67GB 对 301KB 时未压缩一段占绝对多数`() {
        val gb67 = 67.0 * 1024 * 1024 * 1024
        val kb301 = 301.0 * 1024
        val left = ratioOf(gb67, kb301)
        // 早期实现把 "67.0 GB" 抠成 67、"301 KB" 抠成 301 → 得出 301>67 的错误比例
        assertTrue("未压缩段应几乎占满，实际 $left", left > 0.999)
        assertTrue("已压缩段应极小", comparisonRatios(gb67, kb301).second < 0.001)
    }

    @Test
    fun `单位一致的常见情形比例正确`() {
        val left = ratioOf(30.0 * 1024 * 1024, 10.0 * 1024 * 1024)
        assertEquals(0.75f, left, 0.001f)
    }

    @Test
    fun `反向数量级同样正确`() {
        val left = ratioOf(301.0 * 1024, 67.0 * 1024 * 1024 * 1024)
        assertTrue("此时未压缩段应极小，实际 $left", left < 0.001)
    }

    @Test
    fun `两边为零时各占一半`() {
        assertEquals(0.5f, ratioOf(0.0, 0.0), 0.001f)
    }

    @Test
    fun `一段为零时另一段仍有可见宽度`() {
        val (left, right) = comparisonRatios(1024.0, 0.0)
        assertEquals(0.9999f, left, 0.0001f)
        assertTrue("零值一侧保留最小宽度，避免完全消失", right > 0f)
    }
}
