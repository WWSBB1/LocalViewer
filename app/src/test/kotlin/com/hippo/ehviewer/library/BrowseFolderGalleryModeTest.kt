package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [BrowseContentMode.FolderGallery]: Folder listing with leaf image dirs opened as galleries. */
class BrowseFolderGalleryModeTest {
    private fun leafListing() = classifyRemoteListingWithPeeks(
        currentDirName = "Library",
        entries = listOf(RemoteChild(name = "Comics", isDirectory = true)),
        childPeeks = mapOf(
            "Comics" to listOf(RemoteChild(name = "01.jpg", isDirectory = false)),
        ),
    )

    private fun navigate(listing: List<BrowseEntryRemote>) = listing
        .filterRemoteByContentMode(BrowseContentMode.FolderGallery)
        .filterRemoteSmallGalleries(showSmall = true)
        .collapseRemoteFolderGalleryDirs(BrowseContentMode.FolderGallery)

    @Test
    fun leafImageDir_listedAsGalleryNotAsDir() {
        val view = navigate(leafListing())
        assertFalse(view.any { it is BrowseEntryRemote.Directory && it.name == "Comics" })
        val gallery = view.filterIsInstance<BrowseEntryRemote.FolderGallery>().single { it.name == "Comics" }
        assertEquals("Comics", gallery.relativeName)
    }

    /** Same folder in plain Folder mode keeps the enterable dir row and hides the gallery. */
    @Test
    fun plainFolderMode_isUnchanged() {
        val view = leafListing()
            .filterRemoteByContentMode(BrowseContentMode.Folder)
            .filterRemoteSmallGalleries(showSmall = true)
            .collapseRemoteFolderGalleryDirs(BrowseContentMode.Folder)
        assertTrue(view.any { it is BrowseEntryRemote.Directory && it.name == "Comics" })
        assertFalse(view.any { it is BrowseEntryRemote.FolderGallery })
    }

    /** Small-gallery filter must not make the folder vanish from the Folder-ish listing. */
    @Test
    fun smallGalleryHidden_keepsDirRow() {
        val view = leafListing()
            .filterRemoteByContentMode(BrowseContentMode.FolderGallery)
            .filterRemoteSmallGalleries(showSmall = false, minPages = 3)
            .collapseRemoteFolderGalleryDirs(BrowseContentMode.FolderGallery)
        assertFalse(view.any { it is BrowseEntryRemote.FolderGallery })
        assertTrue(view.any { it is BrowseEntryRemote.Directory && it.name == "Comics" })
    }

    /** Non-leaf dirs keep Folder behavior: dir row only, dual gallery stays hidden. */
    @Test
    fun navigableDir_keepsDirRowOnly() {
        val view = navigate(
            classifyRemoteListingWithPeeks(
                currentDirName = "Library",
                entries = listOf(RemoteChild(name = "Comics", isDirectory = true)),
                childPeeks = mapOf(
                    "Comics" to listOf(
                        RemoteChild(name = "01.jpg", isDirectory = false),
                        RemoteChild(name = "vol1.cbz", isDirectory = false),
                    ),
                ),
            ),
        )
        assertTrue(view.any { it is BrowseEntryRemote.Directory && it.name == "Comics" })
        assertFalse(view.any { it is BrowseEntryRemote.FolderGallery })
    }

    @Test
    fun looseImagesAndVideos_stayFolderLike() {
        val view = navigate(
            classifyRemoteListingWithPeeks(
                currentDirName = "Library",
                entries = listOf(
                    RemoteChild(name = "01.jpg", isDirectory = false),
                    RemoteChild(name = "clip.mp4", isDirectory = false),
                ),
                childPeeks = emptyMap(),
            ),
        )
        assertTrue(view.any { it is BrowseEntryRemote.RegularFile && it.name == "01.jpg" })
        assertTrue(view.any { it is BrowseEntryRemote.VideoFile && it.name == "clip.mp4" })
    }
}
