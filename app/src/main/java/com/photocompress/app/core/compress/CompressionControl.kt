package com.photocompress.app.core.compress

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** 用户取消只设置协作信号；批次协程继续完成当前项的回滚和账本清理。 */
class CompressionControl {
    private val requested = AtomicBoolean(false)
    val isCancellationRequested: Boolean get() = requested.get()

    fun requestCancel() { requested.set(true) }

    fun checkCancelled() {
        if (isCancellationRequested) throw CompressionCancelledException()
    }
}

internal class CompressionCancelledException : CancellationException("用户取消压缩")
