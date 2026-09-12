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
 * Browse/video thumbnail concurrency, derived from the network settings instead of the
 * fixed 3-slot caps those paths used to carry.
 *
 * - WebDAV: Advanced → WebDAV concurrent downloads (`arrays.xml` 2…10)
 * - SMB: Advanced → SMB concurrent connections (1…5)
 *
 * **Never read the preferences from the thumbnail path.** [PrefDelegate.value] is a
 * blocking DataStore read (`runBlocking` in `com.ehviewer.core.preferences.PrefDelegate`),
 * so resolving limits per thumbnail would block an IO thread each time a grid cell starts
 * — the flows below push values into [Volatile] fields instead, and every accessor is a
 * plain field read.
 */
object ThumbConcurrency {
    /** `webdav_download_slots` bounds — keep in sync with arrays.xml and WebDavClient. */
    private const val WEBDAV_MIN = 2
    const val WEBDAV_MAX = 10

    /** [com.hippo.ehviewer.SmbGateway] CONNECTIONS range; mirrors `multi_thread_download`. */
    private const val SMB_CONNECTIONS_MIN = 1
    private const val SMB_CONNECTIONS_MAX = 5

    /** `webdav_concurrent_listing` bounds — keep in sync with arrays.xml. */
    private const val WEBDAV_LISTING_MIN = 3
    const val WEBDAV_LISTING_MAX = 10

    /**
     * Defaults mirror [Settings] so thumbnails have a sane budget before the first flow
     * emission lands.
     */
    @Volatile
    private var webDavSlots = 4

    @Volatile
    private var smbConnections = 3

    @Volatile
    private var webDavListingSlots = 5

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            Settings.webDavDownloadSlots.valueFlow().collectLatest { value ->
                webDavSlots = value.coerceIn(WEBDAV_MIN, WEBDAV_MAX)
            }
        }
        scope.launch {
            Settings.multiThreadDownload.valueFlow().collectLatest { value ->
                smbConnections = value.coerceIn(SMB_CONNECTIONS_MIN, SMB_CONNECTIONS_MAX)
            }
        }
        scope.launch {
            Settings.webDavConcurrentListing.valueFlow().collectLatest { value ->
                webDavListingSlots = value.coerceIn(WEBDAV_LISTING_MIN, WEBDAV_LISTING_MAX)
            }
        }
    }

    /** WebDAV browse/video transfer slots. */
    fun webDav(): Int = webDavSlots

    /** WebDAV list/PROPFIND slots (Advanced → WebDAV concurrent listing). */
    fun webDavListing(): Int = webDavListingSlots

    /**
     * SMB browse/video transfer slots: one per configured connection, so a full grid of
     * new thumbs cannot eat the whole per-host op budget
     * ([com.hippo.ehviewer.smb.SmbGateway.maxConcurrentOpsPerHost]).
     */
    fun smb(): Int = smbConnections

    /** Video probe budget: whichever network the video lives on allows the most. */
    fun videoProbe(): Int = maxOf(webDav(), smb())

    /**
     * Concurrent native MMR decodes. Each decoded frame is a full bitmap, so this stays
     * below the network budget on purpose — a memory-safety ceiling, not a knob.
     */
    fun videoDecode(): Int = videoProbe().coerceAtMost(VIDEO_DECODE_MAX)

    /** Upper bound for concurrent native MMR decodes (pool sizing). */
    val VIDEO_DECODE_MAX = 6

    /** Upper bound for concurrent remote video head/tail probes (pool sizing). */
    val VIDEO_PROBE_MAX = WEBDAV_MAX.coerceAtLeast(SMB_CONNECTIONS_MAX)
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
