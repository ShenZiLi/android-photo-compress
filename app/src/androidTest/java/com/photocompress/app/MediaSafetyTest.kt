package com.photocompress.app

import android.content.ContentValues
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photocompress.app.core.compress.*
import com.photocompress.app.core.rewrite.*
import com.photocompress.app.data.ledger.CompressedItemEntity
import com.photocompress.app.data.media.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

/** 故障注入仅操作本测试创建的图片、URI、私有备份；不枚举或修改用户媒体。 */
@RunWith(AndroidJUnit4::class)
class MediaSafetyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val recycle get() = RecycleBin(context)
    private val journal get() = RecoveryJournal(context)
    private val records = mutableListOf<CompressedItemEntity>()

    private fun fixture(block: suspend (MediaItem) -> Unit) = runBlocking {
        val name = "PC-Safety-${UUID.randomUUID()}.jpg"
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/QingcunSafety/")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(123)
        val pixels = IntArray(640 * 480) { 0xff000000.toInt() or random.nextInt(0x1000000) }
        bitmap.setPixels(pixels, 0, 640, 0, 0, 640, 480)
        checkNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        bitmap.recycle()
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val dates = MediaStoreUpdater.captureDates(context, uri)
        val path = checkNotNull(resolver.query(uri, arrayOf("_data"), null, null, null)).use {
            check(it.moveToFirst()); it.getString(0)
        }
        val file = File(path)
        val item = MediaItem(ContentUris.parseId(uri), uri, path, MediaStore.VOLUME_EXTERNAL_PRIMARY,
            0, "QingcunSafety", name, "image/jpeg", file.length(),
            dates.getAsLong("datetaken") ?: 0L, dates.getAsLong("date_added"), dates.getAsLong("date_modified"),
            640, 480, MediaKind.PHOTO, ContainerFormat.JPEG, SupportDecision.Supported)
        try { block(item) } finally {
            journal.entries().filter { it.path == path }.forEach {
                recycle.delete(it.backupRelPath)
                it.safetyBackupRelPath?.let(recycle::delete)
                journal.finish(it.id)
            }
            records.filter { it.dataPath == path }.forEach { it.backupRelPath?.let(recycle::delete) }
            // 仅本测试的独占条目；测试清理有意移除自己的模拟照片。
            check(file.name == name && path.contains("/QingcunSafety/"))
            resolver.delete(uri, null, null)
        }
    }

    private fun record(item: MediaItem, backup: RecycleBin.Backup) = CompressedItemEntity(
        id = UUID.randomUUID().toString(), mediaStoreId = item.id, dataPath = item.dataPath,
        originalPath = item.dataPath, volumeName = item.volumeName, bucketName = item.bucketName,
        displayName = item.displayName, mediaKind = item.kind.name, mimeType = item.mimeType,
        containerFormat = item.format.name, videoCodec = null, originalSize = backup.size,
        compressedSize = File(item.dataPath).length(), originalSha256 = backup.sha256,
        originalDateTakenMs = item.dateTakenMs, originalDateAddedSec = item.dateAddedSec,
        originalDateModifiedSec = item.dateModifiedSec, qualityTier = "BALANCED", codecUsed = "SafetyFixture",
        compressedAtMs = System.currentTimeMillis(), restoreDeadlineMs = Long.MAX_VALUE,
        backupRelPath = backup.relativePath, backupSize = backup.size, status = "DONE",
    ).also { records += it }

    @Test fun suppliedHeicIsSkippedWithoutChangingAnyBytes() = runBlocking {
        val source = File(context.cacheDir, "heic-safety-source.heic")
        assertTrue("Explicitly supplied HEIC copy required", source.isFile)
        val copy = File(context.cacheDir, "PC-Safety-${UUID.randomUUID()}.heic")
        source.copyTo(copy)
        try {
            val sha = FileUtils.sha256(copy)
            val mtime = Files.getLastModifiedTime(copy.toPath())
            val inode = Os.stat(copy.path).st_ino
            val item = MediaItem(0, Uri.parse("content://media/external/images/media/0"), copy.path,
                "external_primary", 0, "Safety", copy.name, "image/heic", copy.length(), 0, 0, 0,
                0, 0, MediaKind.PHOTO, ContainerFormat.HEIC, SupportDecision.Supported)
            assertTrue(CompressionEngine(context).compress(item, QualityTier.BALANCED) is CompressOutcome.Skipped)
            assertEquals(sha, FileUtils.sha256(copy)); assertEquals(mtime, Files.getLastModifiedTime(copy.toPath()))
            assertEquals(inode, Os.stat(copy.path).st_ino)
            assertTrue(MediaClassifier.decideImage(ContainerFormat.HEIC, false) is SupportDecision.Skipped)
        } finally { copy.delete() }
    }

    @Test fun dateSyncFailureRestoresBytesAndProtectsBackupAcrossRestart() = fixture { item ->
        val source = File(item.dataPath)
        val sha = FileUtils.sha256(source); val mtime = Files.getLastModifiedTime(source.toPath())
        val engine = CompressionEngine(context)
        val result = engine.compress(item.copy(dateAddedSec = item.dateAddedSec - 60), QualityTier.BALANCED)
        assertTrue(result.toString(), result is CompressOutcome.Failed)
        assertTrue(source.isFile); assertEquals(sha, FileUtils.sha256(source))
        assertEquals(mtime, Files.getLastModifiedTime(source.toPath()))
        val entry = RecoveryJournal(context).entries().single { it.path == item.dataPath }
        assertTrue(recycle.fileOf(entry.backupRelPath).isFile)
        val backup = RecycleBin.Backup(entry.backupRelPath, entry.sha256, source.length())
        var registered = false
        val purge = engine.purgeBackups(listOf(record(item, backup)), onPurged = { registered = true })
        assertEquals(1, purge.protectedCount); assertEquals(0, purge.failedCount); assertFalse(registered)
        assertTrue(recycle.fileOf(entry.backupRelPath).isFile)
    }

    @Test fun ledgerPublishFailureRestoresOriginalAndMediaIdentity() = fixture { item ->
        val source = File(item.dataPath)
        val sha = FileUtils.sha256(source); val inode = Os.stat(source.path).st_ino
        val result = CompressionEngine(context).compress(item, QualityTier.BALANCED, onCommit = {
            records += it; throw IOException("Injected ledger failure")
        })
        assertTrue(result.toString(), result is CompressOutcome.Failed)
        assertEquals(sha, FileUtils.sha256(source)); assertEquals(inode, Os.stat(source.path).st_ino)
        assertTrue(MediaStoreUpdater.pointsTo(context, item.uri, item.dataPath))
        assertTrue(journal.entries().none { it.path == item.dataPath })
    }

    @Test fun cancelAfterPublishOnlyRollsBackCurrentItem() = fixture { item ->
        val source = File(item.dataPath); val sha = FileUtils.sha256(source)
        val control = CompressionControl(); var rolledBack: String? = null
        val result = CompressionEngine(context).compress(item, QualityTier.BALANCED, control = control,
            onCommit = { records += it; control.requestCancel() }, onRollback = { rolledBack = it })
        assertTrue(result.toString(), result is CompressOutcome.Cancelled)
        assertNotNull(rolledBack); assertEquals(sha, FileUtils.sha256(source))
        assertTrue(journal.entries().none { it.path == item.dataPath })
    }

    @Test fun corruptBackupIsRejectedBeforeOverwritingPhoto() = fixture { item ->
        val source = File(item.dataPath); val sha = FileUtils.sha256(source)
        val backup = recycle.backupWithDigest(UUID.randomUUID().toString(), source)
        val record = record(item, backup)
        recycle.fileOf(backup.relativePath).appendBytes(byteArrayOf(42))
        assertTrue(CompressionEngine(context).restore(record) is RestoreOutcome.Failed)
        assertEquals(sha, FileUtils.sha256(source))
        assertTrue(recycle.fileOf(backup.relativePath).isFile)
    }

    @Test fun missingPublishedPhotoPreventsPurgingLastBackup() = fixture { item ->
        val source = File(item.dataPath)
        val backup = recycle.backupWithDigest(UUID.randomUUID().toString(), source)
        val record = record(item, backup)
        check(source.delete()) // 仅删除新建测试原片，模拟旧版本已发生的照片丢失。
        var cleared = false
        val result = CompressionEngine(context).purgeBackups(listOf(record), onPurged = { cleared = true })
        assertEquals(1, result.protectedCount); assertEquals(0, result.failedCount); assertFalse(cleared)
        assertTrue(recycle.fileOf(backup.relativePath).isFile)
        assertEquals(backup.sha256, FileUtils.sha256(recycle.fileOf(backup.relativePath)))
    }

    @Test fun restoreLedgerFailureKeepsOriginalAndAllowsRecoveryRetry() = fixture { item ->
        val source = File(item.dataPath); val sha = FileUtils.sha256(source)
        val engine = CompressionEngine(context)
        val compressed = engine.compress(item, QualityTier.BALANCED, onCommit = { records += it })
        assertTrue(compressed.toString(), compressed is CompressOutcome.Success)
        val record = (compressed as CompressOutcome.Success).record
        val result = engine.restore(record, onRestored = { throw IOException("Injected restore ledger failure") })
        assertTrue(result.toString(), result is RestoreOutcome.Failed)
        assertEquals(sha, FileUtils.sha256(source)); assertTrue(recycle.fileOf(record.backupRelPath!!).isFile)
        val entry = journal.entries().single { it.path == item.dataPath }
        var recoveredId: String? = null
        val retried = engine.recover(entry) { recoveredId = it }
        assertTrue(retried.toString(), retried is RestoreOutcome.Success)
        assertEquals(record.id, recoveredId); assertEquals(sha, FileUtils.sha256(source))
        assertFalse(recycle.fileOf(record.backupRelPath).exists())
        entry.safetyBackupRelPath?.let(recycle::delete)
    }

    @Test fun interruptedWriteHasDurableRecoveryAndRestoresExactOriginal() = fixture { item ->
        val source = File(item.dataPath); val sha = FileUtils.sha256(source)
        val time = Files.getLastModifiedTime(source.toPath())
        val backup = recycle.backupWithDigest(UUID.randomUUID().toString(), source)
        val entry = RecoveryJournal.Entry(UUID.randomUUID().toString(), source.path, backup.relativePath,
            sha, time.toString(), item.uri.toString(), item.dateTakenMs, item.dateAddedSec, item.dateModifiedSec)
        journal.begin(entry)
        source.writeBytes(byteArrayOf(1, 2, 3)) // 模拟进程在写入中断后留下半成品。
        val engine = CompressionEngine(context)
        assertTrue(engine.compress(item, QualityTier.BALANCED) is CompressOutcome.Failed)
        assertArrayEquals(byteArrayOf(1, 2, 3), source.readBytes())
        val recovered = engine.recover(RecoveryJournal(context).entries().single { it.id == entry.id }) {}
        assertTrue(recovered.toString(), recovered is RestoreOutcome.Success)
        assertEquals(sha, FileUtils.sha256(source)); assertEquals(time, Files.getLastModifiedTime(source.toPath()))
        assertFalse(recycle.fileOf(backup.relativePath).exists())
        recycle.delete(backup.relativePath)
    }

    @Test fun legacyHeicDateFailureKeepsBothOriginalAndConvertedPhoto() = fixture { item ->
        val converted = File(item.dataPath); val convertedSha = FileUtils.sha256(converted)
        val heic = File(context.cacheDir, "heic-safety-source.heic")
        assertTrue(heic.isFile)
        val original = File(converted.parentFile, converted.nameWithoutExtension + "_original.heic")
        check(!original.exists())
        val backup = recycle.backupWithDigest(UUID.randomUUID().toString(), heic)
        val record = record(item, backup).copy(originalPath = original.path, originalDateAddedSec = 1)
        try {
            val result = CompressionEngine(context).restore(record, onRestored = {
                throw IOException("Injected date/ledger failure")
            })
            assertTrue(result.toString(), result is RestoreOutcome.Failed)
            assertTrue(original.isFile); assertEquals(backup.sha256, FileUtils.sha256(original))
            assertTrue(converted.isFile); assertEquals(convertedSha, FileUtils.sha256(converted))
            assertTrue(recycle.fileOf(backup.relativePath).isFile)
            assertTrue(journal.entries().any { it.path == original.path })
        } finally {
            journal.entries().filter { it.path == original.path }.forEach {
                it.safetyBackupRelPath?.let(recycle::delete); journal.finish(it.id)
            }
            // 新建测试 HEIC 独占路径，没有用户原片。
            check(original.name.startsWith("PC-Safety-") && original.parentFile == converted.parentFile)
            if (original.isFile) {
                val uri = MediaStoreUpdater.scanExisting(context, original.path)
                context.contentResolver.delete(uri, null, null)
            }
        }
    }
}
