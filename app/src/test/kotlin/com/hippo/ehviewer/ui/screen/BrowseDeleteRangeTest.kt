package com.hippo.ehviewer.ui.screen

import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.DirPresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Delete mode has no per-action confirmation, so the range math is the only thing standing
 * between a long-press and an unintended bulk delete. These pin the edges.
 */
class BrowseDeleteRangeTest {
    private fun dir(name: String, relativeName: String = name, virtual: Boolean = false) =
        BrowseEntryRemote.Directory(
            name = name,
            relativeName = relativeName,
            hasVideo = false,
            hasGallery = true,
            presence = DirPresence.Navigable,
            virtual = virtual,
        )

    private fun file(name: String) = BrowseEntryRemote.RegularFile(name = name)

    private fun gallery(name: String, relativeName: String = name) =
        BrowseEntryRemote.FolderGallery(
            name = name,
            relativeName = relativeName,
            pageCount = 3,
            coverFileName = null,
            imageFileNames = emptyList(),
        )

    private fun order(vararg entries: BrowseEntryRemote) = entries.toList()

    @Test
    fun forwardRangeIsInclusive() {
        val a = file("a.jpg")
        val b = file("b.jpg")
        val c = file("c.jpg")
        val list = order(a, b, c)
        val deleted = deleteRange(list, browseDeleteKey(a), browseDeleteKey(c))
        assertEquals(listOf(a, b, c), deleted)
    }

    @Test
    fun reverseRangeSelectsSameSet() {
        val a = file("a.jpg")
        val b = file("b.jpg")
        val c = file("c.jpg")
        val list = order(a, b, c)
        val forward = deleteRange(list, browseDeleteKey(a), browseDeleteKey(c))
        val backward = deleteRange(list, browseDeleteKey(c), browseDeleteKey(a))
        assertEquals(forward, backward)
    }

    @Test
    fun sameAnchorAndTapYieldsSingleItem() {
        val a = file("a.jpg")
        val b = file("b.jpg")
        val list = order(a, b)
        val key = browseDeleteKey(b)
        assertEquals(listOf(b), deleteRange(list, key, key))
    }

    @Test
    fun missingKeyYieldsEmpty() {
        val list = order(file("a.jpg"))
        assertTrue(deleteRange(list, "d-none", "f-a.jpg").isEmpty())
        assertTrue(deleteRange(list, "f-a.jpg", "d-none").isEmpty())
    }

    /** A promoted row sits between two real files; it must not swallow the rest. */
    @Test
    fun virtualRowsAreSkippedNotTruncated() {
        val a = file("a.jpg")
        val promoted = dir("@S", relativeName = "S/leaf", virtual = true)
        val c = file("c.jpg")
        val list = order(a, promoted, c)
        val deleted = deleteRange(list, browseDeleteKey(a), browseDeleteKey(c))
        assertEquals(listOf(a, c), deleted)
    }

    @Test
    fun protectedNamesAreSkipped() {
        val a = file("a.jpg")
        val recycle = dir("#recycle")
        val c = file("c.jpg")
        val list = order(a, recycle, c)
        val deleted = deleteRange(list, browseDeleteKey(a), browseDeleteKey(c))
        assertEquals(listOf(a, c), deleted)
    }

    @Test
    fun directoryRelativeNameMismatchIsNotDeletable() {
        // Promoted video leaf: display name and path segment differ.
        val promoted = dir("leaf", relativeName = "S/leaf")
        assertFalse(isDeletableRemoteEntry(promoted))
        assertEquals(BrowseDeleteReject.Virtual, browseDeleteUnsupportedReason(promoted))
    }

    @Test
    fun protectedSystemNamesAreRejected() {
        assertEquals(BrowseDeleteReject.Protected, browseDeleteUnsupportedReason(dir("#recycle")))
        assertEquals(BrowseDeleteReject.Protected, browseDeleteUnsupportedReason(dir("@eaDir")))
        assertEquals(BrowseDeleteReject.Protected, browseDeleteUnsupportedReason(file("\$Admin")))
    }

    @Test
    fun rowsInsideAnArchiveAreRejected() {
        // Zip-as-dir member: the .zip is a path segment, so the row is inside the archive.
        val member = gallery("Album", relativeName = "pack.zip/Album")
        assertEquals(BrowseDeleteReject.InsideArchive, browseDeleteUnsupportedReason(member))

        // The archive file itself sits beside the zip, not inside it.
        val archive = BrowseEntryRemote.ArchiveGallery(name = "pack.zip", fileName = "pack.zip")
        assertNull(browseDeleteUnsupportedReason(archive))
    }

    /** Range filtering and the per-entry rejection must agree, or a batch trips on a member. */
    @Test
    fun rangeOnlyYieldsEntriesThatPassTheRejectionCheck() {
        val entries = listOf(
            file("a.jpg"),
            dir("@S", relativeName = "S/leaf", virtual = true),
            dir("#recycle"),
            gallery("Inside", relativeName = "pack.zip/Inside"),
            file("b.jpg"),
        )
        val selected = deleteRange(entries, browseDeleteKey(entries.first()), browseDeleteKey(entries.last()))
        assertEquals(listOf(entries[0], entries[4]), selected)
        selected.forEach { assertNull(browseDeleteUnsupportedReason(it)) }
        selected.forEach { assertTrue(isDeletableEntry(it)) }
    }

    /**
     * The order handed to [deleteRange] must be the one actually rendered. Photo-grid mode
     * renders loose images alone, so passing the section order there finds no key at all.
     */
    @Test
    fun rangeRequiresAnOrderContainingTheRenderedRows() {
        val a = file("a.jpg")
        val b = file("b.jpg")
        val sections = listOf(dir("Album"), gallery("Album"))
        assertTrue(deleteRange(sections, browseDeleteKey(a), browseDeleteKey(b)).isEmpty())
        assertEquals(listOf(a, b), deleteRange(listOf(a, b), browseDeleteKey(a), browseDeleteKey(b)))
    }

    @Test
    fun plainEntriesAreDeletable() {
        assertTrue(isDeletableRemoteEntry(file("a.jpg")))
        assertTrue(isDeletableRemoteEntry(dir("Album")))
        assertTrue(isDeletableRemoteEntry(gallery("Album")))
        assertNull(browseDeleteUnsupportedReason(file("a.jpg")))
    }

    /** "This folder as gallery" has no relativeName, so it is not a child of itself. */
    @Test
    fun currentDirGalleryIsNotDeletable() {
        val self = gallery("Current", relativeName = "")
        assertEquals(BrowseDeleteReject.Virtual, browseDeleteUnsupportedReason(self))
        assertFalse(isDeletableDirectory(self))
    }

    @Test
    fun onlyDirectoriesReportAsCollections() {
        assertTrue(isDeletableDirectory(dir("Album")))
        assertTrue(isDeletableDirectory(gallery("Album")))
        assertFalse(isDeletableDirectory(file("a.jpg")))
        assertFalse(
            isDeletableDirectory(
                BrowseEntryRemote.ArchiveGallery(name = "a.cbz", fileName = "a.cbz"),
            ),
        )
    }

    @Test
    fun remotePathsJoinUnderTheListedDir() {
        assertEquals("Album", browseDeleteRemotePath(dir("Album"), ""))
        assertEquals("Books/Album", browseDeleteRemotePath(dir("Album"), "Books"))
        assertEquals("Books/a.jpg", browseDeleteRemotePath(file("a.jpg"), "Books"))
    }

    @Test
    fun archiveRemotePathIncludesParentSegment() {
        val archive = BrowseEntryRemote.ArchiveGallery(
            name = "a.cbz",
            fileName = "a.cbz",
            parentRelativeName = "Album",
        )
        assertEquals("Books/Album/a.cbz", browseDeleteRemotePath(archive, "Books"))
    }

    @Test
    fun keysAreDistinctAcrossSections() {
        val entries = listOf(dir("x"), gallery("x"), file("x"))
        assertEquals(entries.size, entries.map(::browseDeleteKey).toSet().size)
        assertNotNull(browseDeleteKey(entries.first()))
    }

    @Test
    fun videoPathUsesRealFileNameNotDisplayName() {
        val video = BrowseEntryRemote.VideoFile(name = "@S-leaf", fileName = "S/leaf.mp4", virtual = true)
        assertEquals("Books/S/leaf.mp4", browseDeleteRemotePath(video, "Books"))
        // The promoted row is virtual, so it is refused rather than deleted by its label.
        assertFalse(isDeletableRemoteEntry(video))

        // A real loose video row in the listing stays deletable.
        val realVideo = BrowseEntryRemote.VideoFile(name = "clip.mp4", fileName = "clip.mp4")
        assertTrue(isDeletableRemoteEntry(realVideo))
        assertEquals("Books/clip.mp4", browseDeleteRemotePath(realVideo, "Books"))
    }
}
