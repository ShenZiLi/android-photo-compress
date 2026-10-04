package com.photocompress.app

import android.app.Application
import com.photocompress.app.worker.RecycleCleanupWorker

class PhotoCompressApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 回收站 30 天到期清理（F11）
        RecycleCleanupWorker.schedule(this)
    }
}
