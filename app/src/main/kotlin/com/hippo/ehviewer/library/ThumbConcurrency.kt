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
    /** Keep in sync with arrays.xml and WebDavClient. */
    const val WEBDAV_MAX = 10

    /** [com.hippo.ehviewer.SmbGateway] CONNECTIONS range; mirrors `multi_thread_download`. */
    private const val SMB_CONNECTIONS_MAX = 5

    /** Keep in sync with arrays.xml. */
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
                webDavSlots = value.coerceAtLeast(1)
            }
        }
        scope.launch {
            Settings.multiThreadDownload.valueFlow().collectLatest { value ->
                smbConnections = value.coerceAtLeast(1)
            }
        }
        scope.launch {
            Settings.webDavConcurrentListing.valueFlow().collectLatest { value ->
                webDavListingSlots = value.coerceAtLeast(1)
            }
        }
    }

    /** WebDAV browse/video transfer slots. */
    fun webDav(): Int = webDavSlots

    /** WebDAV list/PROPFIND slots (Advanced → WebDAV concurrent listing). */
    fun webDavListing(): Int = webDavListingSlots

    /** SMB browse/video transfer slots: follows the SMB concurrent-connection setting. */
    fun smb(): Int = smbConnections

    /**
     * Shared thumbnail/fetch budget: whichever network the user configured highest.
     * No downward clamp — the settings are the only limit.
     */
    fun thumbBudget(): Int = maxOf(webDav(), smb())

    /** Video probe budget. */
    fun videoProbe(): Int = thumbBudget()

    /** Concurrent native MMR decodes: same setting-derived budget, no memory ceiling knob. */
    fun videoDecode(): Int = thumbBudget()

    /**
     * Largest budget the settings UI can produce. Used to size fixed-capacity pools
     * (threads / executors that must cover [thumbBudget] and are built before or
     * independently of the first flow emission). Not a runtime cap.
     */
    val THUMB_BUDGET_MAX = WEBDAV_MAX.coerceAtLeast(SMB_CONNECTIONS_MAX)
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
