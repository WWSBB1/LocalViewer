package com.hippo.ehviewer.library

import com.hippo.ehviewer.ui.screen.BrowseSortMode
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Double-tap prev/next must walk the gallery rows in the order the browse UI
 * currently shows them, not the raw directory listing order.
 */
class ReaderGalleryPlaylistOrderTest {
    private fun folder(name: String) = BrowseEntry.FolderGallery(
        name = name,
        path = "/lib/$name".toPath(),
        relativeName = name,
        pageCount = 3,
        coverPath = null,
    )

    private fun archive(name: String, mtime: Long = 0L) = BrowseEntry.ArchiveGallery(
        name = name,
        path = "/lib/$name".toPath(),
        lastModifiedMs = mtime,
    )

    /** Raw listing order deliberately differs from any name order. */
    private fun rawEntries() = listOf(
        folder("Chapter 10"),
        folder("Chapter 2"),
        folder("Chapter 1"),
    )

    @Test
    fun nameAscending_followsUiOrder() {
        val ordered = ReaderGalleryPlaylist.orderedGalleryRows(
            rawEntries(),
            BrowseSortMode.Name,
            ascending = true,
        )
        assertEquals(
            listOf("Chapter 1", "Chapter 2", "Chapter 10"),
            ordered.map { it.name },
        )
    }

    @Test
    fun nameDescending_reversesUiOrder() {
        val ordered = ReaderGalleryPlaylist.orderedGalleryRows(
            rawEntries(),
            BrowseSortMode.Name,
            ascending = false,
        )
        assertEquals(
            listOf("Chapter 10", "Chapter 2", "Chapter 1"),
            ordered.map { it.name },
        )
    }

    /** Directory rows must not leak into the gallery playlist. */
    @Test
    fun nonGalleryRowsAreDropped() {
        val entries = rawEntries() + BrowseEntry.Directory(
            name = "plain",
            path = "/lib/plain".toPath(),
            hasVideo = false,
            hasGallery = false,
            presence = DirPresence.Navigable,
        )
        val ordered = ReaderGalleryPlaylist.orderedGalleryRows(
            entries,
            BrowseSortMode.Name,
            ascending = true,
        )
        assertEquals(3, ordered.size)
    }

    /** Archive and folder galleries share one ordered run, as the UI does. */
    @Test
    fun folderAndArchiveGalleriesShareOneOrder() {
        val entries = listOf(
            archive("Zeta.cbz"),
            folder("Alpha"),
        )
        val ordered = ReaderGalleryPlaylist.orderedGalleryRows(
            entries,
            BrowseSortMode.Name,
            ascending = true,
        )
        assertEquals(listOf("Alpha", "Zeta.cbz"), ordered.map { it.name })
    }
}
