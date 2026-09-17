package com.hippo.ehviewer.library

import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * Browse/video thumbnail concurrency, all settings-derived instead of the fixed caps those
 * paths used to carry.
 *
 * - Thumbnails / covers: Advanced → thumbnail concurrency, 1…20 ([Settings.thumbConcurrency])
 * - WebDAV transfers: Advanced → WebDAV concurrent downloads (`arrays.xml` 2…10)
 * - SMB transfers: Advanced → SMB concurrent connections (1…5)
 *
 * **Never read the preferences from the thumbnail path.** [PrefDelegate.value] is a
 * blocking DataStore read (`runBlocking` in `com.ehviewer.core.preferences.PrefDelegate`),
 * so resolving limits per thumbnail would block an IO thread each time a grid cell starts
 * — the flows below push values into [Volatile] fields instead, and every accessor is a
 * plain field read.
 */
object ThumbConcurrency {
    /** [Settings.thumbConcurrency] slider range (Advanced → thumbnail concurrency). */
    const val THUMB_CONCURRENCY_MAX = 20

    /**
     * Defaults mirror [Settings] so thumbnails have a sane budget before the first flow
     * emission lands.
     */
    @Volatile
    private var webDavSlots = 4

    @Volatile
    private var smbConnections = 3

    /** [Settings.thumbConcurrency] — the one knob behind [thumbBudget]. */
    @Volatile
    private var thumbSlots = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            Settings.webDavDownloadSlots.valueFlow().collectLatest { value ->
                webDavSlots = value.coerceAtLeast(1)
            }
        }
        scope.launch {
            Settings.multiThreadDownload.valueFlow().collectLatest { value ->
                smbConnections = value.coerceAtLeast(1)
            }
        }
        scope.launch {
            Settings.thumbConcurrency.valueFlow().collectLatest { value ->
                thumbSlots = value.coerceIn(1, THUMB_CONCURRENCY_MAX)
            }
        }
    }

    /** WebDAV browse/video transfer slots. */
    fun webDav(): Int = webDavSlots

    /** SMB browse/video transfer slots: follows the SMB concurrent-connection setting. */
    fun smb(): Int = smbConnections

    /**
     * Shared thumbnail / cover budget — every cover path reads this one value: image thumbs
     * (SMB/WebDAV caches), archive first-page covers, video probe + decode, folder-cover
     * child peeks. Set by Advanced → thumbnail concurrency, independent of the network
     * connection settings.
     */
    fun thumbBudget(): Int = thumbSlots

    /** Video probe budget. */
    fun videoProbe(): Int = thumbBudget()

    /** Concurrent native MMR decodes: same setting-derived budget, no memory ceiling knob. */
    fun videoDecode(): Int = thumbBudget()

    /**
     * Largest budget the settings UI can produce. Used to size fixed-capacity pools
     * (threads / executors that must cover [thumbBudget] and are built before or
     * independently of the first flow emission). Not a runtime cap.
     */
    val THUMB_BUDGET_MAX = THUMB_CONCURRENCY_MAX
}

/**
 * Semaphore whose permit count follows a settings-derived limit, rebuilt when the limit
 * changes — same pattern as `WebDavClient.downloadGate()`.
 *
 * Call sites use `slots.gate().withPermit { … }` so [kotlinx.coroutines.sync.withPermit]
 * (inline) still provides the permit, exactly like the other gates in the app.
 */
class SettingSlots(private val limit: () -> Int) {
    private val lock = Any()

    @Volatile
    private var cap = Int.MIN_VALUE

    @Volatile
    private var slots = Semaphore(1)

    fun gate(): Semaphore {
        // A settings hiccup must never fail a thumbnail: fall back to one permit and log.
        val want = runCatching { limit() }.getOrElse {
            logcat("ThumbConcurrency", it)
            1
        }.coerceAtLeast(1)
        if (want == cap) return slots
        synchronized(lock) {
            if (want == cap) return slots
            slots = Semaphore(want)
            cap = want
            return slots
        }
    }
}
