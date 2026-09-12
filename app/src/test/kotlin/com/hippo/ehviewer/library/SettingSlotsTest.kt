package com.hippo.ehviewer.library

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SettingSlots] replaced the fixed `Semaphore(3)` browse-thumb caps, so the permit
 * rebuild is what makes the Advanced concurrency settings actually apply.
 */
class SettingSlotsTest {
    @Test
    fun `permits follow the limit and rebuild when it changes`() {
        var limit = 3
        val slots = SettingSlots { limit }

        assertEquals(3, slots.gate().availablePermits)

        limit = 7
        assertEquals(7, slots.gate().availablePermits)

        // Same value again: no needless rebuild (identity of the semaphore is stable).
        val rebuilt = slots.gate()
        assertEquals(7, slots.gate().availablePermits)
        assertTrue(rebuilt === slots.gate())
    }

    @Test
    fun `at most the configured number of bodies run at once`() = runBlocking {
        var limit = 2
        val slots = SettingSlots { limit }
        val active = AtomicInteger()
        val peak = AtomicInteger()

        val first = (1..4).map {
            launch(Dispatchers.Default) {
                slots.gate().withPermit {
                    val now = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, now) }
                    delay(40)
                    active.decrementAndGet()
                }
            }
        }
        first.joinAll()
        assertEquals(2, peak.get())

        // Raising the setting is visible to the next batch, without a restart.
        limit = 4
        peak.set(0)
        val second = (1..4).map {
            launch(Dispatchers.Default) {
                slots.gate().withPermit {
                    val now = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, now) }
                    delay(40)
                    active.decrementAndGet()
                }
            }
        }
        second.joinAll()
        assertEquals(4, peak.get())
    }

    /**
     * Mirrors the cache call sites: the permit wraps a suspend body that returns from the
     * enclosing `withContext` — the shape that must not leak or deadlock.
     */
    @Test
    fun `non-local return out of the permit releases it`() = runBlocking {
        val slots = SettingSlots { 1 }

        val first = withContext(Dispatchers.IO) {
            slots.gate().withPermit {
                if (true) return@withContext 42
                0
            }
        }
        assertEquals(42, first)
        assertEquals(1, slots.gate().availablePermits)

        val second = withContext(Dispatchers.IO) {
            slots.gate().withPermit { 7 }
        }
        assertEquals(7, second)
        assertEquals(1, slots.gate().availablePermits)
    }

    @Test
    fun `a limit below one still admits a single body`() = runBlocking {
        val slots = SettingSlots { 0 }
        assertEquals(1, slots.gate().availablePermits)
        val ran = AtomicInteger()
        slots.gate().withPermit { ran.incrementAndGet() }
        assertEquals(1, ran.get())
    }
}
