package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Folder thumbnail fallback order: **image → video → archive**.
 *
 * A folder only reaches the next tier when the previous one found nothing, so the
 * classifier fields on [BrowseEntryRemote.Directory] are mutually exclusive. The frames /
 * first pages themselves are produced later by [VideoThumbnail] / [ArchiveCoverCache].
 */
class FolderCoverFallbackTest {
    // --- tier 1: images -----------------------------------------------------

    @Test
    fun imageBeatsVideo_neverSetsLowerTiers() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Mixed", isDirectory = true)),
            childPeeks = mapOf(
                "Mixed" to listOf(
                    RemoteChild(name = "clip.mp4", isDirectory = false),
                    RemoteChild(name = "vol1.cbz", isDirectory = false),
                    RemoteChild(name = "01.jpg", isDirectory = false),
                ),
            ),
        )
        val mixed = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Mixed" }
        assertEquals("01.jpg", mixed.coverFileName)
        assertNull(mixed.coverVideoFileName)
        assertNull(mixed.coverArchiveFileName)
    }

    // --- tier 2: videos -----------------------------------------------------

    @Test
    fun videoOnlyFolder_coversWithFirstVideo() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Clips", isDirectory = true)),
            childPeeks = mapOf(
                "Clips" to listOf(
                    RemoteChild(name = "b.mp4", isDirectory = false),
                    RemoteChild(name = "a.mp4", isDirectory = false),
                ),
            ),
        )
        val clips = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Clips" }
        assertEquals(DirPresence.VideoOnly, clips.presence)
        assertNull(clips.coverFileName)
        assertEquals("b.mp4", clips.coverVideoFileName)
        assertNull(clips.coverArchiveFileName)
    }

    @Test
    fun videoBeatsArchive_whenNoImage() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Season", isDirectory = true)),
            childPeeks = mapOf(
                "Season" to listOf(
                    RemoteChild(name = "vol1.cbz", isDirectory = false),
                    RemoteChild(name = "ep01.mkv", isDirectory = false),
                ),
            ),
        )
        val season = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Season" }
        assertNull(season.coverFileName)
        assertEquals("ep01.mkv", season.coverVideoFileName)
        assertNull(season.coverArchiveFileName)
    }

    @Test
    fun sampleClip_doesNotBecomeFolderCover() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Preview", isDirectory = true)),
            childPeeks = mapOf(
                "Preview" to listOf(RemoteChild(name = "sample-01.mp4", isDirectory = false)),
            ),
        )
        val preview = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Preview" }
        assertNull(preview.coverFileName)
        assertNull(preview.coverVideoFileName)
        assertNull(preview.coverArchiveFileName)
    }

    @Test
    fun navigableFolderWithoutImage_coversWithDirectVideo() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Season", isDirectory = true)),
            childPeeks = mapOf(
                "Season" to listOf(
                    RemoteChild(name = "Disc1", isDirectory = true),
                    RemoteChild(name = "Disc2", isDirectory = true),
                    RemoteChild(name = "ep01.mkv", isDirectory = false),
                ),
            ),
        )
        val season = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Season" }
        assertEquals(DirPresence.Navigable, season.presence)
        assertNull(season.coverFileName)
        assertEquals("ep01.mkv", season.coverVideoFileName)
    }

    @Test
    fun videoCoverFallsBackToLeafPeek() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Album", isDirectory = true)),
            childPeeks = mapOf(
                "Album" to listOf(RemoteChild(name = "cd1", isDirectory = true)),
            ),
            grandPeeks = mapOf(
                "Album/cd1" to listOf(RemoteChild(name = "v.mp4", isDirectory = false)),
            ),
        )
        val album = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Album" }
        assertNull(album.coverFileName)
        assertEquals("cd1/v.mp4", album.coverVideoFileName)
    }

    // --- tier 3: archives ---------------------------------------------------

    @Test
    fun archiveOnlyFolder_coversWithFirstArchive() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Volumes", isDirectory = true)),
            childPeeks = mapOf(
                "Volumes" to listOf(
                    RemoteChild(name = "v01.cbz", isDirectory = false),
                    RemoteChild(name = "v02.cbz", isDirectory = false),
                ),
            ),
        )
        val volumes = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Volumes" }
        assertNull(volumes.coverFileName)
        assertNull(volumes.coverVideoFileName)
        assertEquals("v01.cbz", volumes.coverArchiveFileName)
    }

    @Test
    fun archiveCoverFallsBackToLeafPeek() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Shelf", isDirectory = true)),
            childPeeks = mapOf(
                "Shelf" to listOf(RemoteChild(name = "cd1", isDirectory = true)),
            ),
            grandPeeks = mapOf(
                "Shelf/cd1" to listOf(RemoteChild(name = "v01.cbz", isDirectory = false)),
            ),
        )
        val shelf = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Shelf" }
        assertNull(shelf.coverFileName)
        assertNull(shelf.coverVideoFileName)
        assertEquals("cd1/v01.cbz", shelf.coverArchiveFileName)
    }

    /** Solid RAR/7z and PDF/EPUB documents are coverable too ([ArchiveCoverCache]). */
    @Test
    fun solidAndDocumentArchives_areCoverCandidates() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Packs", isDirectory = true)),
            childPeeks = mapOf(
                "Packs" to listOf(
                    RemoteChild(name = "notes.txt", isDirectory = false),
                    RemoteChild(name = "vol.rar", isDirectory = false),
                ),
            ),
        )
        val packs = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Packs" }
        assertEquals("vol.rar", packs.coverArchiveFileName)
    }

    /**
     * Single-video folder: the video is lifted to a parent Videos row and this folder stays
     * behind as a `PromotedShell`. The shell still needs its own cover — without it the row
     * is stuck cover-less forever, which also makes every "refresh covers" pass count it as
     * missing and re-peek it endlessly.
     */
    @Test
    fun singleVideoFolder_shellStillCarriesItsCover() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Clip", isDirectory = true)),
            childPeeks = mapOf(
                "Clip" to listOf(RemoteChild(name = "only.mp4", isDirectory = false)),
            ),
        )
        val clip = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Clip" }
        assertEquals(DirPresence.PromotedShell, clip.presence)
        assertEquals("only.mp4", clip.coverVideoFileName)
        assertTrue(
            entries.filterIsInstance<BrowseEntryRemote.VideoFile>().any { it.fileName == "Clip/only.mp4" },
        )
    }

    // --- leaf-peek cover resolution -----------------------------------------

    /**
     * The video lives one level down: S itself is only a promoted shell, and its cover
     * still has to come from the same grand peek the promotion used.
     */
    @Test
    fun promotedLeafVideoShell_takesItsCoverFromTheGrandPeek() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Series", isDirectory = true)),
            childPeeks = mapOf(
                "Series" to listOf(RemoteChild(name = "cd1", isDirectory = true)),
            ),
            grandPeeks = mapOf(
                "Series/cd1" to listOf(RemoteChild(name = "ep1.mkv", isDirectory = false)),
            ),
        )
        val series = entries.filterIsInstance<BrowseEntryRemote.Directory>()
            .single { it.name == "Series" }
        assertEquals(DirPresence.PromotedShell, series.presence)
        assertNull(series.coverFileName)
        assertEquals("cd1/ep1.mkv", series.coverVideoFileName)
    }
}
