package com.photocompress.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photocompress.app.data.ledger.AppDatabase
import com.photocompress.app.data.ledger.SettingsEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 独立内存数据库验证首次默认值和已保存选择，不打开实际应用数据库。 */
@RunWith(AndroidJUnit4::class)
class SettingsDefaultsTest {
    private fun fixture(block: suspend (AppDatabase) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }

    @Test fun firstUseEnablesPngAndFastCompression() = fixture { db ->
        val defaults = SettingsEntity()
        assertTrue(defaults.compressPng)
        assertTrue(defaults.fastCompress)
        db.settingsDao().insertIfMissing(defaults)
        val saved = checkNotNull(db.settingsDao().get())
        assertTrue(saved.compressPng)
        assertTrue(saved.fastCompress)
    }

    @Test fun existingDisabledChoicesSurviveDefaultInitializationAndScanUpdate() = fixture { db ->
        val previous = SettingsEntity(compressPng = false, fastCompress = false, photoTier = "GENTLE")
        db.settingsDao().insertIfMissing(previous)
        db.settingsDao().insertIfMissing(SettingsEntity())
        db.settingsDao().markScanCompleted(123, 9)
        val saved = checkNotNull(db.settingsDao().get())
        assertFalse(saved.compressPng)
        assertFalse(saved.fastCompress)
        assertEquals(previous.photoTier, saved.photoTier)
        assertEquals(123L, saved.lastScanSec)
    }
}
