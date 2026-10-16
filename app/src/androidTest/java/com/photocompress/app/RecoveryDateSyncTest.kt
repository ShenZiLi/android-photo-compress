package com.photocompress.app

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photocompress.app.core.compress.CompressionEngine
import com.photocompress.app.core.compress.CompressOutcome
import com.photocompress.app.core.compress.RestoreOutcome
import com.photocompress.app.core.rewrite.FileUtils
import com.photocompress.app.core.rewrite.MediaStoreUpdater
import com.photocompress.app.core.rewrite.RecoveryJournal
import com.photocompress.app.core.rewrite.RecycleBin
import com.photocompress.app.data.ledger.CompressedItemEntity
import com.photocompress.app.data.media.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** 每次只创建和清理自己的独占 URI、照片、恢复记录及备份；不清空应用数据或枚举个人媒体。 */
@RunWith(AndroidJUnit4::class)
class RecoveryDateSyncTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val journal get() = RecoveryJournal(context)
    private val recycle get() = RecycleBin(context)

    private fun fixture(pending: Boolean = false, providerPendingRename: Boolean = false,
        block: suspend (MediaItem, RecoveryJournal.Entry) -> Unit) = runBlocking {
        val name = "PC-DateSync-${UUID.randomUUID()}.jpg"
        val resolver = context.contentResolver
        var uri: android.net.Uri? = null
        var source: File? = null
        var directCreated = false
        var path: String? = null
        var backup: RecycleBin.Backup? = null
        try {
            val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xff346789.toInt())
            try {
                if (pending && !providerPendingRename) {
                    // 与真机故障相同：普通路径写入由 FUSE 建立本应用的待发布行，物理文件名不带 .pending 前缀。
                    val directory = File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_PICTURES), "QingcunDateSync")
                    check(directory.isDirectory || directory.mkdirs())
                    val file = File(directory, name)
                    check(!file.exists() && file.createNewFile())
                    directCreated = true
                    source = file
                    path = file.path
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
                    uri = checkNotNull(syntheticUriForPath(file.path)) { "FUSE 未建立独占测试条目" }
                    val owner = checkNotNull(resolver.query(checkNotNull(uri), arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                        MediaStore.MediaColumns.IS_PENDING), null, null, null)).use {
                        check(it.moveToFirst()); it.getString(0) to it.getInt(1)
                    }
                    check(owner.first == context.packageName && owner.second == 1) { "测试环境未生成本应用待发布行" }
                } else {
                    val inserted = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/QingcunDateSync/")
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }))
                    uri = inserted
                    checkNotNull(resolver.openOutputStream(inserted)).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
                    if (!pending) resolver.update(inserted, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    path = MediaStoreUpdater.pathOf(context, inserted)
                    source = File(checkNotNull(path))
                }
            } finally { bitmap.recycle() }
            val actualUri = checkNotNull(uri)
            val file = checkNotNull(source)
            check(file.parentFile?.name == "QingcunDateSync" &&
                (file.name == name || (providerPendingRename && file.name.startsWith(".pending-") && file.name.endsWith(name))))
            val dates = MediaStoreUpdater.captureDates(context, actualUri)
            val ownBackup = recycle.backupWithDigest(UUID.randomUUID().toString(), file)
            backup = ownBackup
            val entry = RecoveryJournal.Entry(UUID.randomUUID().toString(), file.path, ownBackup.relativePath, ownBackup.sha256,
                Files.getLastModifiedTime(file.toPath()).toString(), actualUri.toString(), dates.getAsLong("datetaken") ?: 0L,
                dates.getAsLong("date_added"), dates.getAsLong("date_modified") ?: Files.getLastModifiedTime(file.toPath()).toInstant().epochSecond,
                dateTakenWasNull = dates.getAsLong("datetaken") == null)
            val item = MediaItem(ContentUris.parseId(actualUri), actualUri, file.path, MediaStore.VOLUME_EXTERNAL_PRIMARY,
                0, "QingcunDateSync", name, "image/jpeg", file.length(), entry.dateTakenMs,
                entry.dateAddedSec, entry.dateModifiedSec, 160, 120, MediaKind.PHOTO, ContainerFormat.JPEG, SupportDecision.Supported)
            block(item, entry)
        } finally {
            // 包括 setup 失败：只清理本次新建的 URI、独占路径和备份，绝不留下半初始化测试条目。
            journal.entries().filter { path != null && it.path == path }.forEach {
                recycle.delete(it.backupRelPath)
                it.safetyBackupRelPath?.let(recycle::delete)
                journal.finish(it.id)
            }
            backup?.let { recycle.delete(it.relativePath) }
            val directUri = if (directCreated) path?.let(::syntheticUriForPath) else null
            listOfNotNull(uri, directUri).distinct().forEach { resolver.delete(it, null, null) }
            if (directCreated) source?.let { if (it.exists()) check(it.delete()) }
        }
    }

    private fun syntheticUriForPath(path: String): android.net.Uri? {
        val file = File(path)
        check(file.parentFile?.name == "QingcunDateSync" && file.name.startsWith("PC-DateSync-"))
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL).buildUpon()
            .appendQueryParameter("includePending", "1").appendQueryParameter("includeTrashed", "1").build()
        return checkNotNull(context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DATA} = ?", arrayOf(path), null)).use {
            if (!it.moveToFirst()) null else {
                val id = it.getLong(0)
                check(!it.moveToNext()) { "独占测试路径出现多个条目" }
                ContentUris.withAppendedId(collection, id)
            }
        }
    }

    private fun stamp(file: File): List<Long> = Os.stat(file.path).let {
        listOf(it.st_ino, it.st_mtim.tv_sec, it.st_mtim.tv_nsec, it.st_ctim.tv_sec, it.st_ctim.tv_nsec)
    }

    private fun pendingState(item: MediaItem): Int {
        val uri = if (File(item.dataPath).name.startsWith("PC-DateSync-")) syntheticUriForPath(item.dataPath) ?: item.uri else item.uri
        return checkNotNull(context.contentResolver.query(uri,
            arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)).use {
            check(it.moveToFirst()); it.getInt(0)
        }
    }

    @Test fun ownedPendingOriginalPublishesThenCompletesStrictRecovery() = fixture(pending = true) { item, base ->
        journal.begin(base)
        val file = File(item.dataPath)
        val before = stamp(file)
        val captured = MediaStoreUpdater.captureExistingDates(context,
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Long.MAX_VALUE), item.dataPath)
        assertNotNull(captured)
        assertEquals(1, pendingState(item)) // 读取日期不能提前发布未校验文件。
        var restoredId: String? = null
        val result = CompressionEngine(context).recover(base) { restoredId = it }
        assertTrue(result.toString(), result is RestoreOutcome.Success)
        assertEquals(base.ledgerId, restoredId)
        assertEquals(0, pendingState(item))
        assertEquals(base.sha256, FileUtils.sha256(file))
        assertEquals(before.take(3), stamp(file).take(3)) // 发布扫描允许改变 ctime。
        assertTrue(journal.entries().none { it.id == base.id })
        assertFalse(recycle.fileOf(base.backupRelPath).exists())
    }

    @Test fun ownedPendingOriginalWithDateDriftPublishesAndKeepsDateProtection() = fixture(pending = true) { item, base ->
        val expected = base.copy(dateAddedSec = base.dateAddedSec - 60)
        journal.begin(expected)
        val file = File(item.dataPath)
        val before = stamp(file)
        var callbacks = 0
        val result = CompressionEngine(context).recover(expected) { callbacks++ }
        assertTrue(result.toString(), result is RestoreOutcome.DatesPending)
        assertEquals(0, callbacks)
        assertEquals(0, pendingState(item))
        assertEquals(base.sha256, FileUtils.sha256(file))
        assertEquals(before.take(3), stamp(file).take(3))
        val saved = journal.entries().single { it.id == base.id }
        assertEquals(expected.dateAddedSec, saved.dateAddedSec)
        assertTrue(saved.dateRestorePendingReason.orEmpty().contains("加入相册时间"))
        assertTrue(recycle.fileOf(saved.backupRelPath).isFile)
    }

    @Test fun ownedPendingMissingSyntheticFileRestoresBackupThenPublishes() = fixture(pending = true) { item, base ->
        val file = File(item.dataPath)
        journal.begin(base)
        check(file.delete()) // 仅本测试刚创建的独占文件，模拟原片被外部移除。
        var callbacks = 0
        val result = CompressionEngine(context).recover(base) { callbacks++ }
        assertTrue(result.toString(), result is RestoreOutcome.Success || result is RestoreOutcome.DatesPending)
        // FUSE 可在删除测试文件时移除原行，重新写入后获得新行与新加入日期；默认恢复仍严格保护该差异。
        if (result is RestoreOutcome.DatesPending) {
            assertEquals(0, callbacks)
            assertTrue(result.reason.contains("加入相册时间"))
            assertTrue(journal.entries().any { it.id == base.id })
            assertTrue(recycle.fileOf(base.backupRelPath).isFile)
        } else {
            assertEquals(1, callbacks)
            assertTrue(journal.entries().none { it.id == base.id })
        }
        assertEquals(0, pendingState(item))
        assertEquals(base.sha256, FileUtils.sha256(file))
        assertEquals(base.modifiedTime, Files.getLastModifiedTime(file.toPath()).toString())
    }

    @Test fun ownedPendingBadOriginalDigestCannotPublish() = fixture(pending = true) { item, base ->
        val bad = base.copy(sha256 = "0".repeat(64))
        journal.begin(bad)
        val before = stamp(File(item.dataPath))
        var callbacks = 0
        val result = CompressionEngine(context).recover(bad) { callbacks++ }
        assertTrue(result.toString(), result is RestoreOutcome.Failed)
        assertEquals(0, callbacks)
        assertEquals(1, pendingState(item))
        assertEquals(before, stamp(File(item.dataPath)))
        assertTrue(recycle.fileOf(base.backupRelPath).isFile)
        val accepted = CompressionEngine(context).recoverAcceptingDateAdded(bad) { callbacks++ }
        assertTrue(accepted.toString(), accepted is RestoreOutcome.Failed)
        assertEquals(0, callbacks)
        assertEquals(1, pendingState(item))
        assertEquals(before, stamp(File(item.dataPath)))
    }

    @Test fun providerPendingRenameIsRejectedBeforePublishingOrMovingOriginal() = fixture(pending = true,
        providerPendingRename = true) { item, base ->
        val file = File(item.dataPath)
        assertTrue(file.name.startsWith(".pending-"))
        val publishTarget = File(file.parentFile, item.displayName)
        assertFalse(publishTarget.exists())
        journal.begin(base)
        val before = stamp(file)
        var callbacks = 0
        val result = CompressionEngine(context).recover(base) { callbacks++ }
        assertTrue(result.toString(), result is RestoreOutcome.Failed)
        assertTrue((result as RestoreOutcome.Failed).reason.contains("发布可能移动文件"))
        assertEquals(0, callbacks)
        assertEquals(1, pendingState(item))
        assertTrue(file.isFile)
        assertFalse(publishTarget.exists())
        assertEquals(base.sha256, FileUtils.sha256(file))
        assertEquals(before, stamp(file))
        assertTrue(recycle.fileOf(base.backupRelPath).isFile)
    }

    @Test fun pendingPublicationRejectsForeignUnknownAndTrashedOwnership() {
        // MediaProvider 不允许测试篡改真实 owner 字段，直接验证生产入口共用的拒绝策略。
        val own = context.packageName
        assertTrue(runCatching { MediaStoreUpdater.requireRecoveryRowState(own, 1, 0, own) }.isSuccess)
        for (owner in listOf(null, "", "synthetic.other.app")) {
            assertTrue(runCatching { MediaStoreUpdater.requireRecoveryRowState(own, 1, 0, owner) }.isFailure)
        }
        assertTrue(runCatching { MediaStoreUpdater.requireRecoveryRowState(own, 1, 1, own) }.isFailure)
        assertTrue(runCatching { MediaStoreUpdater.requireRecoveryRowState(own, -1, 0, own) }.isFailure)
        assertTrue(runCatching { MediaStoreUpdater.requireRecoveryRowState(own, 0, -1, own) }.isFailure)
    }

    @Test fun missingOldUriDateOnlyMismatchPersistsAndRetryDoesNotRewriteOriginal() = fixture { item, base ->
        val source = File(item.dataPath)
        val safety = recycle.backupWithDigest("SAFE-${UUID.randomUUID()}", source)
        val expected = base.copy(mediaUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            Long.MAX_VALUE).toString(), dateAddedSec = base.dateAddedSec - 60, safetyBackupRelPath = safety.relativePath)
        journal.begin(expected)
        val before = stamp(source)
        var callbacks = 0
        repeat(2) {
            val outcome = CompressionEngine(context).recover(journal.entries().single { it.id == expected.id }) { callbacks++ }
            assertTrue(outcome.toString(), outcome is RestoreOutcome.DatesPending)
            assertTrue((outcome as RestoreOutcome.DatesPending).reason.contains("加入相册时间"))
            assertEquals(before, stamp(source))
            assertEquals(expected.sha256, FileUtils.sha256(source))
            val saved = journal.entries().single { it.id == expected.id }
            assertEquals(expected.dateAddedSec, saved.dateAddedSec)
            assertNotNull(saved.dateRestorePendingReason)
            assertTrue(recycle.fileOf(saved.backupRelPath).isFile)
            assertTrue(recycle.fileOf(safety.relativePath).isFile)
            assertTrue(MediaStoreUpdater.pointsTo(context, item.uri, item.dataPath))
        }
        assertEquals(0, callbacks)
        assertTrue(CompressionEngine(context).compress(item, QualityTier.BALANCED) is CompressOutcome.Failed)
    }

    @Test fun explicitAcceptanceCompletesOnlyDateAddedDifferenceWithoutChangingOriginalExpectations() = fixture { item, base ->
        val expected = base.copy(dateAddedSec = base.dateAddedSec - 60)
        journal.begin(expected)
        val engine = CompressionEngine(context)
        assertTrue(engine.recover(expected) {} is RestoreOutcome.DatesPending)
        val file = File(item.dataPath)
        val before = stamp(file)
        val actualAdded = MediaStoreUpdater.captureDates(context, item.uri).getAsLong("date_added")
        var callbacks = 0
        val result = engine.recoverAcceptingDateAdded(journal.entries().single { it.id == base.id }) {
            callbacks++
            assertEquals(expected.dateAddedSec, journal.entries().single { it.id == base.id }.dateAddedSec)
        }
        assertTrue(result.toString(), result is RestoreOutcome.Success)
        assertEquals(1, callbacks)
        assertEquals(before, stamp(file))
        assertEquals(base.sha256, FileUtils.sha256(file))
        assertEquals(actualAdded, MediaStoreUpdater.captureDates(context, item.uri).getAsLong("date_added"))
        assertEquals(base.dateModifiedSec, MediaStoreUpdater.captureDates(context, item.uri).getAsLong("date_modified"))
        assertTrue(journal.entries().none { it.id == base.id })
        assertFalse(recycle.fileOf(base.backupRelPath).exists())
    }

    @Test fun explicitDateAddedAcceptanceCannotAcceptOtherDateDifferences() = fixture { item, base ->
        val expected = base.copy(dateAddedSec = base.dateAddedSec - 60, dateModifiedSec = base.dateModifiedSec - 60)
        journal.begin(expected)
        var callbacks = 0
        val result = CompressionEngine(context).recoverAcceptingDateAdded(expected) { callbacks++ }
        assertTrue(result.toString(), result !is RestoreOutcome.Success)
        assertEquals(0, callbacks)
        val saved = journal.entries().single { it.id == base.id }
        assertEquals(expected.dateAddedSec, saved.dateAddedSec)
        assertEquals(expected.dateModifiedSec, saved.dateModifiedSec)
        assertTrue(recycle.fileOf(base.backupRelPath).isFile)
        assertEquals(base.sha256, FileUtils.sha256(File(item.dataPath)))
    }

    @Test fun ordinaryRefreshStillRejectsChangedOriginalDates() = fixture { item, base ->
        val before = stamp(File(item.dataPath))
        val error = runCatching { MediaStoreUpdater.refresh(context, item.uri, item.dataPath,
            base.dateTakenMs, base.dateAddedSec - 60, base.dateModifiedSec) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error?.message.orEmpty().contains("相册日期已改变"))
        assertEquals(before, stamp(File(item.dataPath)))
    }

    @Test fun integrityFailureClearsPreviousDateOnlyStatusWithoutTouchingPhoto() = fixture { item, base ->
        val bad = base.copy(sha256 = "0".repeat(64), dateRestorePendingReason = "旧日期差异")
        journal.begin(bad)
        val before = stamp(File(item.dataPath))
        var callback = false
        val result = CompressionEngine(context).recover(bad) { callback = true }
        assertTrue(result.toString(), result is RestoreOutcome.Failed)
        assertFalse(callback)
        assertNull(journal.entries().single { it.id == bad.id }.dateRestorePendingReason)
        assertEquals(before, stamp(File(item.dataPath)))
        assertTrue(recycle.fileOf(base.backupRelPath).isFile)
    }

    @Test fun restoredBytesRefreshStaleSizeThenCompleteAndCleanOnlyOwnBackups() = fixture { item, base ->
        val file = File(item.dataPath)
        // 先让自己的已发布行记录不同大小，再模拟恢复过程中留下的半成品。
        file.appendBytes(ByteArray(200))
        context.contentResolver.update(item.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        journal.begin(base)
        var restoredId: String? = null
        val result = CompressionEngine(context).recover(base) { restoredId = it }
        assertTrue(result.toString(), result is RestoreOutcome.Success)
        assertEquals(base.ledgerId, restoredId)
        assertEquals(base.sha256, FileUtils.sha256(file))
        assertEquals(base.modifiedTime, Files.getLastModifiedTime(file.toPath()).toString())
        assertTrue(journal.entries().none { it.id == base.id })
        assertFalse(recycle.fileOf(base.backupRelPath).exists())
        val size = checkNotNull(context.contentResolver.query(item.uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)).use {
            check(it.moveToFirst()); it.getLong(0)
        }
        assertEquals(file.length(), size)
    }

    @Test fun sameSizeOriginalRefreshesStaleDerivedModifiedDatePreservingContentAndMtime() = fixture { item, base ->
        val file = File(item.dataPath)
        val originalTime = Files.getLastModifiedTime(file.toPath())
        val staleSec = base.dateModifiedSec - 60
        // 只改变自己的合成照片时间，让媒体库记录旧派生日期，大小和内容始终不变。
        Files.setLastModifiedTime(file.toPath(), java.nio.file.attribute.FileTime.fromMillis(staleSec * 1000L))
        context.contentResolver.update(item.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        assertEquals(staleSec, MediaStoreUpdater.captureDates(context, item.uri).getAsLong("date_modified"))
        Files.setLastModifiedTime(file.toPath(), originalTime)
        journal.begin(base)
        val before = stamp(file)
        var callback = false
        val result = CompressionEngine(context).recover(base) { callback = true }
        assertTrue(result.toString(), result is RestoreOutcome.Success)
        assertTrue(callback)
        assertEquals(base.sha256, FileUtils.sha256(file))
        // 此分支必须让 MediaProvider 重新扫描，系统可能更新状态变更时间 ctime（不是创建时间）。
        // 验证内容、inode 和纳秒修改时间；无需扫描的重复恢复仍在其他用例中严格核对 ctime。
        assertEquals(before.take(3), stamp(file).take(3))
        assertEquals(base.dateModifiedSec, MediaStoreUpdater.captureDates(context, item.uri).getAsLong("date_modified"))
        assertTrue(journal.entries().none { it.id == base.id })
        assertFalse(recycle.fileOf(base.backupRelPath).exists())
    }

    private fun record(item: MediaItem, base: RecoveryJournal.Entry) : CompressedItemEntity {
        val file = File(item.dataPath)
        return CompressedItemEntity(id = UUID.randomUUID().toString(), mediaStoreId = item.id,
            dataPath = item.dataPath, originalPath = item.dataPath, volumeName = item.volumeName,
            bucketName = item.bucketName, displayName = item.displayName, mediaKind = item.kind.name,
            mimeType = item.mimeType, containerFormat = item.format.name, videoCodec = null,
            originalSize = file.length(), compressedSize = file.length(), originalSha256 = base.sha256,
            originalDateTakenMs = base.dateTakenMs, originalDateAddedSec = base.dateAddedSec,
            originalDateModifiedSec = base.dateModifiedSec, qualityTier = "BALANCED", codecUsed = "DateSyncFixture",
            compressedAtMs = 0, restoreDeadlineMs = Long.MAX_VALUE, backupRelPath = base.backupRelPath,
            backupSize = file.length(), status = "DONE")
    }

    @Test fun ordinaryRestoreDateMismatchHasPartialOutcomeAndKeepsRecovery() = fixture { item, base ->
        val file = File(item.dataPath)
        val record = record(item, base).copy(originalDateAddedSec = base.dateAddedSec - 60)
        val before = stamp(file)
        var callback = false
        val result = CompressionEngine(context).restore(record) { callback = true }
        assertTrue(result.toString(), result is RestoreOutcome.DatesPending)
        assertFalse(callback)
        assertEquals(before, stamp(file))
        assertNotNull(journal.entries().single { it.path == item.dataPath }.dateRestorePendingReason)
        assertTrue(recycle.fileOf(base.backupRelPath).isFile)
    }

    @Test fun ordinaryRestorePreservesRawEmptyTakenInsteadOfLedgerUiFallback() = fixture { item, base ->
        org.junit.Assume.assumeTrue("Provider fixture must have empty raw taken date", base.dateTakenWasNull || base.dateTakenMs == 0L)
        val file = File(item.dataPath)
        val before = stamp(file)
        val record = record(item, base).copy(originalDateTakenMs = base.dateAddedSec * 1000L)
        var restoredId: String? = null
        val result = CompressionEngine(context).restore(record) { restoredId = it }
        assertTrue(result.toString(), result is RestoreOutcome.Success)
        assertEquals(record.id, restoredId)
        assertEquals(before, stamp(file))
        assertEquals(if (base.dateTakenWasNull) null else 0L,
            MediaStoreUpdater.captureDates(context, item.uri).getAsLong("datetaken"))
        assertTrue(journal.entries().none { it.path == item.dataPath })
        assertFalse(recycle.fileOf(base.backupRelPath).exists())
    }

    @Test fun ordinaryRestoreWithMissingOldUriPreservesSamePathRawEmptyTaken() = fixture { item, base ->
        org.junit.Assume.assumeTrue("Provider fixture must have empty raw taken date", base.dateTakenWasNull || base.dateTakenMs == 0L)
        val file = File(item.dataPath)
        val before = stamp(file)
        val record = record(item, base).copy(mediaStoreId = Long.MAX_VALUE,
            originalDateTakenMs = base.dateAddedSec * 1000L)
        var restoredId: String? = null
        val result = CompressionEngine(context).restore(record) { restoredId = it }
        assertTrue(result.toString(), result is RestoreOutcome.Success)
        assertEquals(record.id, restoredId)
        assertEquals(before, stamp(file))
        assertTrue(MediaStoreUpdater.pointsTo(context, item.uri, item.dataPath))
        assertEquals(if (base.dateTakenWasNull) null else 0L,
            MediaStoreUpdater.captureDates(context, item.uri).getAsLong("datetaken"))
        assertTrue(journal.entries().none { it.path == item.dataPath })
        assertFalse(recycle.fileOf(base.backupRelPath).exists())
    }

    @Test fun journalWithoutOptionalDateStatusRemainsReadable() = fixture { _, base ->
        journal.begin(base)
        val ownJournal = File(context.filesDir, "recovery/${base.id}.json")
        val json = JSONObject(ownJournal.readText()).apply { remove("dateRestorePendingReason") }
        ownJournal.writeText(json.toString())
        val read = journal.entries().single { it.id == base.id }
        assertNull(read.dateRestorePendingReason)
        assertEquals(base.sha256, read.sha256)
        assertEquals(base.dateAddedSec, read.dateAddedSec)
    }
}
