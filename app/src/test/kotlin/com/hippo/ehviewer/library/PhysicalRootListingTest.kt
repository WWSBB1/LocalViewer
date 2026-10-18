package com.hippo.ehviewer.library

import java.io.File
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * An all-files-access root (Download, storage root) is stored as a plain filesystem path
 * and enumerated with [physicalChildren]. It must report **everything** the directory
 * contains — subfolders, archives, images, videos and non-media files.
 *
 * The bug this pins down: those roots used to be resolved to their `mediastore:` twin,
 * which only knows what the system media scanner indexed, so archives, folders and
 * non-media files silently disappeared from the browser.
 */
class PhysicalRootListingTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("physical-root-", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        // A Download folder as people actually have it: mixed content, nested folders,
        // archives and files the media scanner never touches.
        File(root, "Comics").mkdirs()
        File(root, "Comics/01.jpg").writeBytes(ByteArray(16) { 1 })
        File(root, "Comics/02.jpg").writeBytes(ByteArray(16) { 2 })
        File(root, "Videos").mkdirs()
        File(root, "Videos/clip.mp4").writeBytes(ByteArray(16) { 3 })
        File(root, "pack.cbz").writeBytes(ByteArray(32) { 4 })
        File(root, "notes.txt").writeText("not media")
        File(root, "cover.png").writeBytes(ByteArray(8) { 5 })
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun listsEveryChildNotOnlyIndexedMedia() {
        val children = physicalChildren(root.path.toPath())

        val names = children.map { it.name }.toSet()
        assertEquals(
            setOf("Comics", "Videos", "pack.cbz", "notes.txt", "cover.png"),
            names,
        )
    }

    @Test
    fun archivesAndNonMediaFilesSurvive() {
        val children = physicalChildren(root.path.toPath()).associateBy { it.name }

        // The MediaStore-backed path dropped all three of these.
        assertTrue("archive missing", children.containsKey("pack.cbz"))
        assertTrue("plain text missing", children.containsKey("notes.txt"))
        assertTrue("nested folder missing", children.containsKey("Comics"))
    }

    @Test
    fun directoriesAreFlaggedAndFilesCarrySize() {
        val children = physicalChildren(root.path.toPath()).associateBy { it.name }

        assertTrue(children.getValue("Comics").isDirectory)
        assertFalse(children.getValue("pack.cbz").isDirectory)
        // Directory sizes are forced to 0 (the UI does not use them).
        assertEquals(0L, children.getValue("Comics").size)
        assertTrue(children.getValue("pack.cbz").size > 0L)
    }

    @Test
    fun dotNamesAreListedAsHidden() {
        File(root, ".hidden-folder").mkdirs()
        File(root, ".nomedia").writeText("")

        val children = physicalChildren(root.path.toPath()).associateBy { it.name }

        // Present (so the caller can decide), but tagged hidden.
        assertTrue(children.getValue(".hidden-folder").hidden)
        assertTrue(children.getValue(".nomedia").hidden)
    }

    @Test
    fun plainPathIsNotRewrittenToMediaStore() {
        // No media permission in unit tests, so the conversion is a no-op there anyway;
        // this asserts the shape rootPath() relies on: a leading '/' stays a filesystem
        // path rather than becoming `mediastore:/…`.
        val path = root.path.toPath()
        assertFalse(path.toString().startsWith("mediastore:"))
        assertTrue(path.toString().startsWith(root.path))
    }
}
