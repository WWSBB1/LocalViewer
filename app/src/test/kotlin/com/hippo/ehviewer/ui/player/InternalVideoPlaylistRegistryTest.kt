package com.hippo.ehviewer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Delete-from-player playlist bookkeeping: the item after the removed one becomes the next. */
class InternalVideoPlaylistRegistryTest {
    private fun local(name: String) = InternalVideoSource.Local("/videos/$name")

    private fun sessionOf(vararg names: String): InternalVideoPlaylistRegistry.Created {
        val items = names.map(::local)
        return InternalVideoPlaylistRegistry.create(items.first(), items)
    }

    @Test
    fun createsSessionWithCurrentAtIndex() {
        val created = InternalVideoPlaylistRegistry.create(
            local("b.mp4"),
            listOf(local("a.mp4"), local("b.mp4"), local("c.mp4")),
        )
        try {
            assertEquals(1, created.initialIndex)
            assertEquals(3, created.session.items.size)
        } finally {
            InternalVideoPlaylistRegistry.remove(created.session.id)
        }
    }

    @Test
    fun removeMiddleItemReturnsNextInOrder() {
        val created = sessionOf("a.mp4", "b.mp4", "c.mp4")
        try {
            val next = InternalVideoPlaylistRegistry.removeItem(created.session.id, 1)
            assertEquals("c.mp4", next?.displayName)
            assertEquals(
                listOf("a.mp4", "c.mp4"),
                InternalVideoPlaylistRegistry.get(created.session.id)?.items?.map { it.displayName },
            )
        } finally {
            InternalVideoPlaylistRegistry.remove(created.session.id)
        }
    }

    @Test
    fun removeLastItemReturnsNull() {
        val created = sessionOf("a.mp4", "b.mp4")
        try {
            assertNull(InternalVideoPlaylistRegistry.removeItem(created.session.id, 1))
            assertEquals(1, InternalVideoPlaylistRegistry.get(created.session.id)?.items?.size)
        } finally {
            InternalVideoPlaylistRegistry.remove(created.session.id)
        }
    }

    @Test
    fun removeOnlyItemLeavesEmptyList() {
        val created = sessionOf("only.mp4")
        try {
            assertNull(InternalVideoPlaylistRegistry.removeItem(created.session.id, 0))
            assertEquals(0, InternalVideoPlaylistRegistry.get(created.session.id)?.items?.size)
        } finally {
            InternalVideoPlaylistRegistry.remove(created.session.id)
        }
    }

    @Test
    fun removeOutOfRangeIndexIsANoOp() {
        val created = sessionOf("a.mp4", "b.mp4")
        try {
            assertNull(InternalVideoPlaylistRegistry.removeItem(created.session.id, 9))
            assertEquals(2, InternalVideoPlaylistRegistry.get(created.session.id)?.items?.size)
        } finally {
            InternalVideoPlaylistRegistry.remove(created.session.id)
        }
    }

    @Test
    fun removeUnknownSessionReturnsNull() {
        assertNull(InternalVideoPlaylistRegistry.removeItem("missing", 0))
        assertNull(InternalVideoPlaylistRegistry.removeItem(null, 0))
    }
}
