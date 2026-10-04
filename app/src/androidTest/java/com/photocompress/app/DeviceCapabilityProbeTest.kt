package com.photocompress.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photocompress.app.core.diagnostics.DeviceCapabilityProbe
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 阶段 1 能力探针入口：在设备上运行并把结论写入 logcat（前缀 PCPROBE）。
 * 执行：gradlew :app:connectedDebugAndroidTest
 * 抓取：adb logcat -d -s PCPROBE
 */
@RunWith(AndroidJUnit4::class)
class DeviceCapabilityProbeTest {

    @Test
    fun runProbe() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DeviceCapabilityProbe.runAll(context)
    }
}
