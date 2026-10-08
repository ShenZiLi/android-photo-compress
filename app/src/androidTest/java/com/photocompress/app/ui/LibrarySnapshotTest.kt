package com.photocompress.app.ui

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photocompress.app.data.media.ContainerFormat
import com.photocompress.app.data.media.MediaItem
import com.photocompress.app.data.media.MediaKind
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
