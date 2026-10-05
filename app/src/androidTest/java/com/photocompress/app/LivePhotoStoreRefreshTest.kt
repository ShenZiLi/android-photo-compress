package com.photocompress.app

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photocompress.app.data.media.LivePhotoDetector
import com.photocompress.app.core.compress.CompressionEngine
import com.photocompress.app.core.compress.CompressOutcome
import com.photocompress.app.core.compress.RestoreOutcome
import com.photocompress.app.core.rewrite.MediaStoreUpdater
import com.photocompress.app.data.media.ContainerFormat
import com.photocompress.app.data.media.MediaItem
import com.photocompress.app.data.media.MediaKind
import com.photocompress.app.data.media.QualityTier
import com.photocompress.app.data.media.SupportDecision
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only fresh owned copies; never updates the failed personal row or its original backup. */
@RunWith(AndroidJUnit4::class)
class LivePhotoStoreRefreshTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val columns = arrayOf("_id", "_data", "_size", "o_video_size", "o_cover_time_stamps", "is_pending", "date_added", "date_modified", "datetaken", "owner_package_name")
    private fun row(uri: Uri): List<String?> = checkNotNull(context.contentResolver.query(uri, columns, null, null, null)).use { c ->
        check(c.moveToFirst())
        columns.indices.map { if (c.isNull(it)) null else c.getString(it) }
    }
    private fun source() = File(context.cacheDir, "oem-live-before-d242cc64.jpg").also { check(it.isFile) }
    private fun output() = File(context.cacheDir, "oem-live-after-d242cc64.jpg").also { check(it.isFile) }
    private fun ownedFixture(block: (Uri) -> Unit) {
        check(Environment.isExternalStorageManager())
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "PC-Refresh-${UUID.randomUUID()}.jpg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PhotoCompressAcceptance/")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")).use { out -> source().inputStream().use { it.copyTo(out) } }
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            assertEquals(context.packageName, row(uri)[9])
            block(uri)
        } finally { assertEquals(1, resolver.delete(uri, null, null)) }
    }
    private fun overwrite(uri: Uri, input: File) {
        val file = File(checkNotNull(row(uri)[1])); val mtime = file.lastModified()
        RandomAccessFile(file, "rw").use { dest ->
            input.inputStream().use { src ->
                val buf = ByteArray(65536)
                while (true) { val n = src.read(buf); if (n < 0) break; dest.write(buf, 0, n) }
            }
            dest.setLength(input.length()); dest.fd.sync()
        }
        check(file.setLastModified(mtime))
    }
    private fun assertRefreshed(uri: Uri, before: List<String?>, input: File) = runBlocking {
        val file = File(checkNotNull(before[1]))
        val inode = Os.stat(file.path).st_ino; val mtime = Files.getLastModifiedTime(file.toPath())
        MediaStoreUpdater.refresh(context, uri, file.path, before[8]!!.toLong(), before[6]!!.toLong(), before[7]!!.toLong())
        val after = row(uri)
        assertEquals(before[0], after[0]); assertEquals(before[1], after[1]); assertEquals("0", after[5])
        assertEquals(before.drop(6), after.drop(6)); assertEquals(before[4], after[4])
        assertEquals(input.length(), after[2]!!.toLong())
        assertEquals(checkNotNull(LivePhotoDetector.detect(input)).motionPhotoLength, after[3]!!.toLong())
        assertEquals(inode, Os.stat(file.path).st_ino); assertEquals(mtime, Files.getLastModifiedTime(file.toPath()))
        assertArrayEquals(input.readBytes(), file.readBytes())
        Log.i("PCOEMREFRESH", "id=${after[0]} bytes=${after[2]} motion=${after[3]} datesAndIdentityPreserved=true owner=${after[9]}")
    }
    @Test fun productionRefreshAndRestorePreserveOwnedRow() = ownedFixture { uri ->
        val before = row(uri)
        overwrite(uri, output()); assertEquals(before[3], row(uri)[3])
        assertRefreshed(uri, before, output())
        overwrite(uri, source()); assertRefreshed(uri, before, source())
    }
    /** Explicitly provided fresh shell-owned fixture; never discovers or mutates personal media. */
    @Test fun productionRefreshAndRestorePreserveForeignRow() {
        val uri = Uri.parse(checkNotNull(InstrumentationRegistry.getArguments().getString("foreignFixtureUri")))
        val before = row(uri)
        check((before[9] == null || before[9] == "com.android.shell") && before[1]!!.contains("/PhotoCompressAcceptance/PC-Foreign-"))
        overwrite(uri, output()); assertRefreshed(uri, before, output())
        overwrite(uri, source()); assertRefreshed(uri, before, source())
    }
    @Test fun productionRejectsWrongPathWithoutChangingRow() = ownedFixture { uri ->
        val before = row(uri)
        val result = runCatching { runBlocking {
            MediaStoreUpdater.refresh(context, uri, output().path, before[8]!!.toLong(), before[6]!!.toLong(), before[7]!!.toLong())
        } }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(before, row(uri))
    }
    @Test fun compressionEngineCompressesAndRestoresWithFreshOemLengths() = ownedFixture { uri ->
        // GT7 Pro's cached-process cleanup can kill a long background instrumentation run.
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val before = row(uri); val path = checkNotNull(before[1]); val file = File(path)
        val item = MediaItem(before[0]!!.toLong(), uri, path, MediaStore.VOLUME_EXTERNAL_PRIMARY,
            0L, "PhotoCompressAcceptance", file.name, "image/jpeg", file.length(),
            before[8]!!.toLong(), before[6]!!.toLong(), before[7]!!.toLong(), 3072, 4096,
            MediaKind.LIVE_PHOTO, ContainerFormat.JPEG, SupportDecision.Supported,
            motionPhotoOffset = LivePhotoDetector.detect(file)!!.motionPhotoOffset)
        runBlocking {
            val engine = CompressionEngine(context)
            val result = engine.compress(item, QualityTier.BALANCED, QualityTier.COMPACT)
            assertTrue("$result", result is CompressOutcome.Success)
            val record = (result as CompressOutcome.Success).record
            try {
                val compressed = row(uri)
                assertTrue(file.length() < source().length())
                assertEquals(file.length(), compressed[2]!!.toLong())
                assertEquals(LivePhotoDetector.detect(file)!!.motionPhotoLength, compressed[3]!!.toLong())
                assertEquals(before.take(2), compressed.take(2)); assertEquals(before.drop(6), compressed.drop(6))
                val restored = engine.restore(record)
                assertTrue("$restored", restored is RestoreOutcome.Success)
                assertRefreshed(uri, before, source())
            } finally {
                // Only this test's UUID backup; leave a failed restore's backup available for diagnosis.
                if (file.readBytes().contentEquals(source().readBytes())) record.backupRelPath?.let {
                    com.photocompress.app.core.rewrite.RecycleBin(context).delete(it)
                }
            }
        }
    }
    @Test fun publishingAlreadyPublishedOwnedCopyRefreshesOemMotionLength() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(Environment.isExternalStorageManager())
        val source = File(context.cacheDir, "oem-live-before-d242cc64.jpg")
        val output = File(context.cacheDir, "oem-live-after-d242cc64.jpg")
        check(source.isFile && output.isFile && source.length() > output.length())
        val expectedOld = checkNotNull(LivePhotoDetector.detect(source)).motionPhotoLength
        val expectedNew = checkNotNull(LivePhotoDetector.detect(output)).motionPhotoLength
        val resolver = context.contentResolver
        val name = "PC-OEM-${UUID.randomUUID()}.jpg"
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PhotoCompressAcceptance/")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        var keep = false
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")).use { out -> source.inputStream().use { it.copyTo(out) } }
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            val columns = arrayOf("_id", "_data", "_size", "o_video_size", "o_cover_time_stamps", "is_pending", "date_added", "date_modified", "datetaken", "owner_package_name")
            fun row(): List<String?> = checkNotNull(resolver.query(uri, columns, null, null, null)).use { c ->
                check(c.moveToFirst()); check(c.getString(c.getColumnIndexOrThrow("owner_package_name")) == context.packageName)
                columns.indices.map { if (c.isNull(it)) null else c.getString(it) }
            }
            val before = row(); val file = File(checkNotNull(before[1]))
            assertEquals(source.length(), before[2]!!.toLong()); assertEquals(expectedOld, before[3]!!.toLong())
            val oldMtime = file.lastModified()
            RandomAccessFile(file, "rw").use { dest ->
                output.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; dest.write(buffer, 0, n) } }
                dest.setLength(output.length()); dest.fd.sync()
            }
            check(file.setLastModified(oldMtime))
            val inode = Os.stat(file.path).st_ino; val mtime = Files.getLastModifiedTime(file.toPath())
            val stale = row(); assertEquals(expectedOld, stale[3]!!.toLong())
            Log.i("PCOEMPUBLISH", "ownedUri=$uri oldSize=${stale[2]} oldMotion=${stale[3]} actualSize=${file.length()} actualMotion=$expectedNew")
            val updated = resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val after = row()
            Log.i("PCOEMPUBLISH", "forcePublished=$updated newSize=${after[2]} newMotion=${after[3]} sameId=${before[0] == after[0]} samePath=${before[1] == after[1]} datesBefore=${before.drop(6)} datesAfter=${after.drop(6)}")
            assertEquals(1, updated); assertEquals(output.length(), after[2]!!.toLong()); assertEquals(expectedNew, after[3]!!.toLong())
            assertEquals(before[0], after[0]); assertEquals(before[1], after[1]); assertEquals("0", after[5]); assertEquals(before.drop(6), after.drop(6))
            assertEquals(inode, Os.stat(file.path).st_ino); assertEquals(mtime, Files.getLastModifiedTime(file.toPath()))
            if (InstrumentationRegistry.getArguments().getString("keepFixture") == "true") {
                File(context.cacheDir, "oem-live-kept-uri.txt").writeText(uri.toString())
                keep = true
            }
        } finally { if (!keep) check(resolver.delete(uri, null, null) == 1) }
    }
}
