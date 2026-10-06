package com.hippo.ehviewer.library

import java.io.File
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZIP seek tables must number members in natural name order from **every** writer.
 *
 * The browse grid warms [ArchiveStreamPageCache] from the pure-Kotlin cover path, and the
 * reader then opens straight from that table. When the cover path wrote central-directory
 * order instead, page 0 was the archive's first stored member and the reader showed
 * `1, 10, 2` for archives whose CD was not written sorted.
 */
class ArchiveStreamPageCacheOrderTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("archive-pages-", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        ArchiveStreamPageCache.rootOverrideForTests = root.path.toPath()
    }

    @After
    fun tearDown() {
        ArchiveStreamPageCache.rootOverrideForTests = null
        root.deleteRecursively()
    }

    @Test
    fun preV4ZipIndexIsPurgedWithItsPageFiles() {
        val key = "local:/books/unsorted.cbz"
        save(key, version = 3, format = "zip")
        val dir = dirFor(key)
        File(dir, "0.jpg").writeBytes(ByteArray(64) { 1 })
        File(dir, "1.jpg").writeBytes(ByteArray(64) { 2 })

        assertNull(ArchiveStreamPageCache.loadIndex(key))
        // Page files are named by the stale order — they must not survive the index.
        assertTrue(!dir.exists() || (dir.list() ?: emptyArray()).isEmpty())
    }

    @Test
    fun preV4TarIndexStaysUsable() {
        val key = "smb:1:/books/ok.cbt"
        save(key, version = 3, format = "tar")

        val loaded = ArchiveStreamPageCache.loadIndex(key)
        assertEquals("tar", loaded?.format)
        assertEquals(3, loaded?.members?.size)
    }

    @Test
    fun currentVersionZipIndexLoads() {
        val key = "local:/books/sorted.cbz"
        save(key, version = ArchiveStreamPageCache.INDEX_VERSION, format = "zip")

        val loaded = ArchiveStreamPageCache.loadIndex(key)
        assertEquals(ArchiveStreamPageCache.INDEX_VERSION, loaded?.v)
        // CD order (1, 10, 2) round-trips; ordering itself is the writer's contract.
        assertEquals(listOf("1.jpg", "10.jpg", "2.jpg"), loaded?.members?.map { it.name })
    }

    private fun dirFor(cacheKey: String): File = File(ArchiveStreamPageCache.dirFor(cacheKey).toString())

    /** Round-trips through the real serializer — CD order on purpose. */
    private fun save(cacheKey: String, version: Int, format: String) {
        val members = listOf("1.jpg", "10.jpg", "2.jpg").mapIndexed { i, name ->
            ArchiveStreamPageCache.Member(
                i = i,
                name = name,
                ext = "jpg",
                uncSize = 1024L,
                offset = 100L + i,
                compSize = 900L,
                method = 0,
            )
        }
        ArchiveStreamPageCache.saveIndex(
            ArchiveStreamPageCache.Index(
                v = version,
                cacheKey = cacheKey,
                remoteSize = 4096L,
                format = format,
                complete = false,
                structureComplete = true,
                members = members,
            ),
        )
    }
}
