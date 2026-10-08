package com.photocompress.app.ui

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photocompress.app.core.rewrite.RecoveryJournal
import com.photocompress.app.data.ledger.CompressedItemEntity
import com.photocompress.app.data.media.ContainerFormat
import com.photocompress.app.data.media.MediaItem
import com.photocompress.app.data.media.MediaKind
import com.photocompress.app.data.media.QualityTier
import com.photocompress.app.data.media.SupportDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 只使用合成内存媒体，不扫描、读写文件或修改应用数据库。 */
@RunWith(AndroidJUnit4::class)
class LibrarySnapshotTest {
    @Test fun firstScanPublishesCountsAlbumsAndFilterNames() {
        val scanned = UiState().copy(items = listOf(
            media(1, "Camera"),
            media(2, "Movies").copy(
                kind = MediaKind.VIDEO, format = ContainerFormat.MP4,
                mimeType = "video/mp4", xmpCompressId = "synthetic-compressed-id",
            ),
        ))
        assertFalse(scanned.library.matches(scanned))

        val state = publish(scanned)

        assertTrue(state.library.matches(state))
        assertEquals(1, state.totals().todoCount)
        assertEquals(1024L, state.totals().todoBytes)
        assertEquals(1, state.totals().doneCount)
        assertEquals(listOf("Camera"), state.albumTodoAll().map { it.name })
        assertEquals(listOf("Movies"), state.albumDoneAll().map { it.name })
        assertEquals(setOf("Camera" to 1, "Movies" to 1), state.allAlbumNames().toSet())
    }

    @Test fun excludedAlbumUpdatesListsAndRemainsAvailableInSettings() {
        val original = consistent(UiState(items = listOf(media(1, "Camera"), media(2, "Screenshots"))))
        val hidden = publish(original.copy(settings = original.settings.copy(excludedAlbums = "Camera")))

        assertEquals(listOf("Screenshots"), hidden.albumTodoAll().map { it.name })
        assertEquals(1, hidden.totals().todoCount)
        assertEquals(setOf("Camera" to 1, "Screenshots" to 1), hidden.allAlbumNames().toSet())

        val shown = publish(hidden.copy(settings = hidden.settings.copy(excludedAlbums = "")))
        assertEquals(2, shown.totals().todoCount)
    }

    @Test fun pngSettingUpdatesCompressionCandidatesWithoutRescanning() {
        val items = listOf(media(1, "Screenshots").copy(format = ContainerFormat.PNG, mimeType = "image/png"))
        val disabled = consistent(UiState(items = items))
        assertFalse(disabled.library.todoItems.single().compressible)

        val enabled = publish(disabled.copy(settings = disabled.settings.copy(compressPng = true)))
        assertTrue(enabled.library.todoItems.single().compressible)
        assertSame(items, enabled.items)

        val disabledAgain = publish(enabled.copy(settings = enabled.settings.copy(compressPng = false)))
        assertFalse(disabledAgain.library.todoItems.single().compressible)
    }

    @Test fun staleResultsNeverOverwriteAnyNewerLibraryInputs() {
        val source = consistent(UiState(items = listOf(media(1, "Camera"))))
        val stale = snapshot(source)
        val changedStates = listOf(
            source.copy(items = listOf(media(2, "Screenshots"))),
            source.copy(ledger = ArrayList(source.ledger)),
            source.copy(recoveryEntries = ArrayList(source.recoveryEntries)),
            source.copy(settings = source.settings.copy(excludedAlbums = "Camera")),
        )

        for (changed in changedStates) {
            val current = consistent(changed)
            assertFalse(stale.matches(current))
            assertSame(current, stale.applyTo(current))
        }
    }

    @Test fun navigationSelectionAndProgressDuringCalculationArePreserved() {
        val source = UiState(items = listOf(media(1, "Camera")))
        val rebuilt = snapshot(source)
        val navigating = source.copy(
            page = AppPage.TODO,
            todo = LevelState(level = 2, album = "Camera", pickedItems = setOf(1L)),
            scanDone = 1, scanTotal = 1,
        )
        val flow = MutableStateFlow(navigating)

        flow.update { rebuilt.applyTo(it) }

        assertSame(rebuilt, flow.value.library)
        assertEquals(AppPage.TODO, flow.value.page)
        assertSame(navigating.todo, flow.value.todo)
        assertEquals(1, flow.value.scanDone)
        assertEquals(1, flow.value.totals().todoCount)
    }

    @Test fun emptyRescanClearsPreviousResults() {
        val original = consistent(UiState(items = listOf(media(1, "Camera"))))
        assertEquals(1, original.totals().todoCount)

        val empty = publish(original.copy(items = emptyList()))

        assertTrue(empty.library.matches(empty))
        assertEquals(0, empty.totals().todoCount)
        assertTrue(empty.albumTodoAll().isEmpty())
        assertTrue(empty.allAlbumNames().isEmpty())
    }

    /**
     * 回收站备份列表由账本直接派生：清理写入账本后立即可见，不等待整库快照重建。
     * 这是「清理完成瞬间列表仍显示旧条目」的回归断言。
     */
    @Test fun recycleBackupsFollowLedgerInsteadOfDerivedSnapshot() {
        val backup = ledgerRecord()
        // 快照尚未重建（仍是空库快照）时，备份列表也必须立即由账本给出。
        val withBackup = UiState(ledger = listOf(backup))
        assertFalse(withBackup.library.matches(withBackup))
        assertEquals(listOf(backup.id), withBackup.successfulBackups().map { it.id })

        // 清理：账本清空备份路径后，列表立即为空，无需任何快照重建或重扫。
        val purged = withBackup.copy(
            ledger = listOf(backup.copy(backupRelPath = null, backupSize = 0, status = CompressedItemEntity.STATUS_PURGED)),
        )
        assertTrue(purged.successfulBackups().isEmpty())
    }

    /** 恢复日志涉及的路径不进入回收站：未完成恢复的备份必须保留。 */
    @Test fun recycleBackupsSkipPathsHeldByRecoveryJournal() {
        val backup = ledgerRecord()
        val guarded = UiState(
            ledger = listOf(backup),
            recoveryEntries = listOf(recoveryEntry(path = backup.dataPath)),
        )

        assertTrue(guarded.successfulBackups().isEmpty())
    }

    private fun ledgerRecord() = CompressedItemEntity(
        id = "record-1", mediaStoreId = 1, dataPath = "/synthetic/Camera/1.jpg", originalPath = "",
        volumeName = "external_primary", bucketName = "Camera", displayName = "1.jpg",
        mediaKind = MediaKind.PHOTO.name, mimeType = "image/jpeg", containerFormat = ContainerFormat.JPEG.name,
        videoCodec = null, originalSize = 2048, compressedSize = 1024, originalSha256 = "synthetic-sha",
        originalDateTakenMs = 1000, originalDateAddedSec = 1, originalDateModifiedSec = 1,
        qualityTier = QualityTier.BALANCED.name, codecUsed = null, compressedAtMs = 2000,
        restoreDeadlineMs = Long.MAX_VALUE, backupRelPath = "record-1/1.jpg", backupSize = 1024,
        status = CompressedItemEntity.STATUS_DONE,
    )

    private fun recoveryEntry(path: String) = RecoveryJournal.Entry(
        id = "recovery-1", path = path, backupRelPath = "recovery-1/1.jpg", sha256 = "synthetic-sha",
        modifiedTime = "1", mediaUri = "content://media/external/images/media/1",
        dateTakenMs = 1000, dateAddedSec = 1, dateModifiedSec = 1,
    )

    private fun snapshot(state: UiState) =
        LibrarySnapshot(state.items, state.ledger, state.recoveryEntries, state.settings)

    private fun publish(state: UiState): UiState = snapshot(state).applyTo(state)

    private fun consistent(state: UiState): UiState = state.copy(library = snapshot(state))

    private fun media(id: Long, album: String) = MediaItem(
        id = id, uri = Uri.parse("content://media/external/images/media/$id"),
        dataPath = "/synthetic/$album/$id.jpg", volumeName = "external_primary",
        bucketId = 1, bucketName = album, displayName = "$id.jpg", mimeType = "image/jpeg",
        size = 1024, dateTakenMs = 1000, dateAddedSec = 1, dateModifiedSec = 1,
        width = 100, height = 100, kind = MediaKind.PHOTO, format = ContainerFormat.JPEG,
        support = SupportDecision.Supported,
    )
}
