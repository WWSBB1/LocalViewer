package com.hippo.ehviewer.webdav

import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.library.BROWSE_PROGRESS_PUBLISH_INTERVAL_NS
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.DirPresence
import com.hippo.ehviewer.library.FolderGalleryIndex
import com.hippo.ehviewer.library.NetworkFolderIndexCache
import com.hippo.ehviewer.library.RemoteChild
import com.hippo.ehviewer.library.RemoteDirectorySlimPlan
import com.hippo.ehviewer.library.SMB_PROMOTE_MAX_LEAVES
import com.hippo.ehviewer.library.ScanDiag
import com.hippo.ehviewer.library.ThumbConcurrency
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.ZipCdParse
import com.hippo.ehviewer.library.ZipCentralDirectory
import com.hippo.ehviewer.library.classifyRemoteListing
import com.hippo.ehviewer.library.classifyRemoteListingWithPeeks
import com.hippo.ehviewer.library.hasUnpeekedDirectories
import com.hippo.ehviewer.library.hiddenDirectoriesNeedingDeepScan
import com.hippo.ehviewer.library.isDotHiddenName
import com.hippo.ehviewer.library.isPromotableLeafDirName
import com.hippo.ehviewer.library.isPartiallyTruncatedSlimListing
import com.hippo.ehviewer.library.isProtectedSystemName
import com.hippo.ehviewer.library.isShallowIncompleteListing
import com.hippo.ehviewer.library.isUntrustedSlimLiveListing
import com.hippo.ehviewer.library.isZipArchiveFileName
import com.hippo.ehviewer.library.mergeRemoteDirectorySlimRefresh
import com.hippo.ehviewer.library.peekIndicatesHiddenDir
import com.hippo.ehviewer.library.planRemoteDirectorySlimRefresh
import com.hippo.ehviewer.library.preferCompleteFolderGalleries
import com.hippo.ehviewer.library.replaceSlimDirectFilesFromLive
import com.hippo.ehviewer.library.selectCachedFolderListing
import com.hippo.ehviewer.library.withHiddenFlags
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Browse listing for WebDAV: PROPFIND + parallel peeks + same remote classify as SMB.
 * No TCP session pool — HTTP multiplexes; peek fan-out follows the browse budget
 * ([Settings.thumbConcurrency], Advanced → thumbnail concurrency).
 */
object WebDavGateway {
    /** Deep peek/classify budget after shallow paint; keep shallow on expiry. */
    private const val DEEP_CLASSIFY_TIMEOUT_MS = 180_000L

    /**
     * How often an unfinished deep pass writes what it has so far.
     *
     * A big layer can need minutes of PROPFINDs; before this, nothing was stored until it
     * finished, so leaving the folder threw all of it away and the next visit started from
     * zero — it never converged. Coarser than the paint throttle because each write
     * re-encodes the source's whole index file.
     */
    private const val PROGRESS_SAVE_INTERVAL_NS = 5_000_000_000L

    /**
     * Rows of direct children an earlier (interrupted) pass already classified, keyed by
     * child name. Promoted/virtual rows are excluded — they are not direct children.
     *
     * Hidden rows are excluded too: `hidden` comes from a `.nomedia` probe or a previous
     * mode filter, and once a row is known it was never re-peeked — so deleting the
     * `.nomedia` (or turning Hidden files on later) left the folder invisible forever.
     * Re-peeking a hidden child costs one PROPFIND and is the only way to notice that the
     * reason it was hidden is gone.
     */
    private fun knownRowsOf(previous: List<BrowseEntryRemote>?): Map<String, BrowseEntryRemote.Directory> =
        previous.orEmpty().asSequence()
            .filterIsInstance<BrowseEntryRemote.Directory>()
            .filter {
                it.presence != DirPresence.Pending &&
                    it.relativeName == it.name &&
                    !it.virtual &&
                    !it.hidden &&
                    !it.unreachable
            }
            .associateBy { it.name }

    /** Replace still-[DirPresence.Pending] rows with the ones [previous] already resolved. */
    private fun applyKnownRows(
        rows: List<BrowseEntryRemote>,
        known: Map<String, BrowseEntryRemote.Directory>,
    ): List<BrowseEntryRemote> = if (known.isEmpty()) {
        rows
    } else {
        rows.map { row ->
            if (row is BrowseEntryRemote.Directory && row.presence == DirPresence.Pending) {
                (known[row.name] ?: row).let { carried ->
                    // The child is in the live listing right now, so a stale `unreachable`
                    // flag (set by an earlier truncated/failed slim pass) must not survive.
                    // Keeping it hid perfectly reachable folders from every later visit.
                    if (carried is BrowseEntryRemote.Directory && carried.unreachable) {
                        carried.copy(unreachable = false)
                    } else {
                        carried
                    }
                }
            } else {
                row
            }
        }
    }

    /**
     * Subfolder PROPFIND / zip-root fan-out. Browse-owned, so it follows the browse budget
     * ([ThumbConcurrency.thumbBudget], Advanced → thumbnail concurrency) rather than a
     * separate network setting — one knob covers scanning and covers alike.
     */
    private fun peekConcurrency(): Int = ThumbConcurrency.thumbBudget()

    /** PROPFIND attempts per directory before the row is left unresolved. */
    private const val PEEK_ATTEMPTS = 3

    /** Backoff between attempts (× attempt number). */
    private const val PEEK_RETRY_DELAY_MS = 400L

    /**
     * Reader page-name lookup (History / sibling open with no folder index). A transient
     * transport blip must not turn into an empty page list → reader error.
     */
    private const val LIST_IMAGE_ATTEMPTS = 3
    private const val LIST_IMAGE_RETRY_DELAY_MS = 350L

    /**
     * List one directory, retrying transient failures.
     *
     * Returns null only after every attempt failed, and **never** swallows
     * [CancellationException] — that is what turned a cancelled pass into thousands of
     * "this folder is empty" rows, which were then persisted: folders in the middle of a
     * large layer kept showing with no cover and no gallery.
     *
     * A null result means "unknown", not "empty". Callers must leave the child unresolved
     * ([DirPresence.Pending]) so [hasUnpeekedDirectories] brings it back on the resume pass,
     * instead of recording an empty listing that looks final.
     */
    private suspend fun peekWithRetry(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
    ): List<RemoteChild>? {
        var last: Throwable? = null
        repeat(PEEK_ATTEMPTS) { attempt ->
            try {
                return listChildrenForRelativeDir(source, password, relativeDir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                ScanDiag.log(
                    "propfind FAIL ${e::class.simpleName} dir=$relativeDir " +
                        "attempt=${attempt + 1}/$PEEK_ATTEMPTS msg=${e.message}",
                )
                if (attempt < PEEK_ATTEMPTS - 1) delay(PEEK_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        ScanDiag.log(
            "propfind GIVEUP dir=$relativeDir after $PEEK_ATTEMPTS attempts " +
                "(${last?.let { it::class.simpleName }}: ${last?.message})",
        )
        return null
    }

    fun sourceConfigKey(source: WebDavSourceEntity): String = "${source.id}|${source.baseUrl}|${source.pathPrefix}|${source.username}"

    fun joinRelative(parent: String, child: String): String {
        val p = parent.replace('\\', '/').trim('/')
        val c = child.replace('\\', '/').trim('/')
        return when {
            p.isEmpty() -> c
            c.isEmpty() -> p
            else -> "$p/$c"
        }
    }

    suspend fun listDirectory(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
        useCache: Boolean = true,
        onCached: ((List<BrowseEntryRemote>) -> Unit)? = null,
        /**
         * Refresh semantics: keep the children that already have a thumbnail and re-peek only
         * the ones that have none. Folders left without a cover by an earlier failed pass are
         * exactly the cover-less ones, so this is what makes the refresh button heal them.
         */
        retryCoverless: Boolean = false,
    ): List<BrowseEntryRemote> {
        if (Settings.browseZipAsDir.value) {
            ZipAsDirListing.splitZipBrowsePath(relativeDir)?.let { (zipRel, inner) ->
                return listZipVirtualDirectory(
                    source,
                    password,
                    relativeDir,
                    zipRel,
                    inner,
                    useCache,
                    onCached,
                )
            }
        }
        val configKey = sourceConfigKey(source)
        // Read the cached rows *before* anything below invalidates them: the cover-less retry
        // needs to know which children already have a thumbnail so it can leave them alone.
        val coverlessRetryBaseline = if (retryCoverless) {
            val cachedRows = BrowseSession.getWebDavCachedListing(source.id, relativeDir)?.entries
                ?: NetworkFolderIndexCache.loadWebDav(source.id, configKey, relativeDir)
            cachedRows
                ?.filterNot { it is BrowseEntryRemote.Directory && it.isCoverless() }
                ?.also { kept ->
                    ScanDiag.log(
                        "refresh dir=$relativeDir kept=${kept.size} " +
                            "repeek=${cachedRows.size - kept.size}",
                    )
                }
        } else {
            null
        }
        if (useCache) {
            val ram = BrowseSession.getWebDavCachedListing(source.id, relativeDir)
            val needDisk = ram == null || isShallowIncompleteListing(ram.entries)
            val disk = if (needDisk) {
                NetworkFolderIndexCache.loadWebDav(source.id, configKey, relativeDir)
            } else {
                null
            }
            val selected = selectCachedFolderListing(
                ramEntries = ram?.entries,
                ramSessionCurrent = ram?.sessionCurrent == true,
                diskEntries = disk,
            )
            val cached = selected?.let { (entries, sessionCurrent) ->
                if (ram == null || ram.entries !== entries || ram.sessionCurrent != sessionCurrent) {
                    BrowseSession.putWebDavListing(
                        source.id,
                        relativeDir,
                        entries,
                        sessionCurrent = sessionCurrent,
                    )
                }
                BrowseSession.CachedRemoteListing(entries = entries, sessionCurrent = sessionCurrent)
            }
            if (cached != null) {
                val presented = presentListingForZipAsDirToggle(
                    source,
                    configKey,
                    relativeDir,
                    cached.entries,
                    cached.sessionCurrent,
                )
                onCached?.invoke(presented)
                val shouldQuickScan =
                    com.hippo.ehviewer.Settings.networkFolderIndexQuickScan.value &&
                        !cached.sessionCurrent
                ScanDiag.log(
                    "cache HIT dir=$relativeDir entries=${presented.size} " +
                        "sessionCurrent=${cached.sessionCurrent} quickScan=$shouldQuickScan " +
                        "ram=${ram != null} disk=${disk != null}",
                )
                if (!shouldQuickScan) return presented
                // Shallow stub, or a deep pass that was interrupted and saved its progress:
                // both need the peek/classify pass, which resumes from the stored rows.
                if (isShallowIncompleteListing(cached.entries) || hasUnpeekedDirectories(cached.entries)) {
                    return listDirectoryShallowThenDeep(
                        source,
                        password,
                        relativeDir,
                        configKey,
                        onCached,
                    )
                }
                return try {
                    val slimStart = System.nanoTime()
                    val refresh = withIOContext {
                        listDirectorySlim(source, password, relativeDir, cached.entries, configKey)
                    }
                    ScanDiag.log(
                        "slim dir=$relativeDir entries=${refresh.entries.size} " +
                            "persist=${refresh.persist} " +
                            "ms=${(System.nanoTime() - slimStart) / 1_000_000}",
                    )
                    if (!refresh.persist) {
                        logcat("FolderIndex") {
                            "WebDAV slim ignored untrusted listing for source=${source.id} " +
                                "dir=$relativeDir; keeping cache"
                        }
                        return presented
                    }
                    val toKeep = if (refresh.entries != cached.entries) {
                        NetworkFolderIndexCache.saveWebDav(
                            source.id,
                            configKey,
                            relativeDir,
                            refresh.entries,
                        )
                    } else {
                        refresh.entries
                    }
                    presentListingForZipAsDirToggle(
                        source,
                        configKey,
                        relativeDir,
                        toKeep,
                        sessionCurrent = true,
                        previousForZipNames = cached.entries,
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Leave non-current so a later visit can retry quick scan.
                    presented
                }
            }
        } else {
            BrowseSession.invalidateWebDavListing(source.id, relativeDir)
        }
        // Cold miss: shallow-first (one PROPFIND → paint), then deferred peeks.
        ScanDiag.log("cache MISS dir=$relativeDir useCache=$useCache → shallow+deep")
        return listDirectoryShallowThenDeep(
            source,
            password,
            relativeDir,
            configKey,
            onCached,
            previousOverride = coverlessRetryBaseline,
        )
    }

    /**
     * Cold list: publish name-only shallow rows immediately, then peek/classify.
     * Deep failure / timeout / cancel keeps shallow (`sessionCurrent=false`).
     */
    /** Child row that ended up with no cover of any kind — the refresh button re-peeks these. */
    private fun BrowseEntryRemote.Directory.isCoverless(): Boolean =
        coverFileName == null && coverVideoFileName == null && coverArchiveFileName == null

    private suspend fun listDirectoryShallowThenDeep(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
        configKey: String,
        onCached: ((List<BrowseEntryRemote>) -> Unit)?,
        /**
         * Baseline for the resume machinery. Refresh passes the cached listing minus the
         * cover-less rows, so [knownRowsOf] keeps only folders that already have a thumbnail
         * and the rest are peeked again — a refresh costs a handful of PROPFINDs instead of
         * restarting a layer that may hold hundreds of folders.
         */
        previousOverride: List<BrowseEntryRemote>? = null,
    ): List<BrowseEntryRemote> {
        val previous = previousOverride ?: BrowseSession.getWebDavListing(source.id, relativeDir)
        val t0 = System.nanoTime()
        val children = withIOContext {
            listChildrenForRelativeDir(source, password, relativeDir)
        }
        ScanDiag.log(
            "children dir=$relativeDir count=${children.size} " +
                "ms=${(System.nanoTime() - t0) / 1_000_000}",
        )
        val dirName = relativeDir.substringAfterLast('/').ifEmpty { source.displayName }
        val shallowChildren = if (Settings.browseZipAsDir.value) {
            ZipAsDirListing.zipFilesAsPendingDirectories(children)
        } else {
            children
        }
        // Keep rows an interrupted earlier pass already resolved: the shallow classify has
        // every directory as Pending, and without this the covers already cached would be
        // dropped for however long the fresh PROPFINDs take.
        val shallow = applyKnownRows(
            classifyRemoteListing(dirName, shallowChildren.withHiddenFlags()),
            knownRowsOf(previous),
        )
        val shallowMerged = if (previous != null) {
            preferCompleteFolderGalleries(previous, shallow)
        } else {
            shallow
        }
        // RAM-only until deep succeeds (same reason as SMB — avoid slim false-complete).
        // childrenFresh: these rows ARE this session's live child list, so browse may
        // fetch thumbs now; only the deferred peeks are still missing.
        BrowseSession.putWebDavListing(
            source.id,
            relativeDir,
            shallowMerged,
            sessionCurrent = false,
            childrenFresh = true,
        )
        logcat("FolderIndex") {
            "WebDAV shallow list source=${source.id} dir=$relativeDir " +
                "children=${children.size} entries=${shallowMerged.size} " +
                "ms=${(System.nanoTime() - t0) / 1_000_000}"
        }
        ScanDiag.log(
            "shallow dir=$relativeDir entries=${shallowMerged.size} " +
                "ms=${(System.nanoTime() - t0) / 1_000_000}",
        )
        onCached?.invoke(shallowMerged)

        BrowseSession.getWebDavCachedListing(source.id, relativeDir)?.let { cached ->
            if (cached.sessionCurrent) return cached.entries
        }

        // Clock for the incremental save inside the deep pass (coarser than the paint).
        val lastProgressSaveNs = AtomicLong(0L)

        return try {
            withTimeout(DEEP_CLASSIFY_TIMEOUT_MS) {
                coroutineContext.ensureActive()
                val t1 = System.nanoTime()
                val deep = withIOContext {
                    classifyDirectoryChildren(
                        source,
                        password,
                        relativeDir,
                        children,
                        previous = previous,
                        onPartial = { partial ->
                            val merged = preferCompleteFolderGalleries(shallowMerged, partial)
                            BrowseSession.putWebDavListing(
                                source.id,
                                relativeDir,
                                merged,
                                sessionCurrent = false,
                                childrenFresh = true,
                            )
                            onCached?.invoke(merged)
                            // Persist progress: leaving the folder must not throw away the
                            // peeks already done. The saved listing still holds Pending rows,
                            // which [hasUnpeekedDirectories] reads back as "resume me", so it
                            // is never adopted as the finished listing.
                            val saveNow = System.nanoTime()
                            val lastSave = lastProgressSaveNs.get()
                            if (saveNow - lastSave >= PROGRESS_SAVE_INTERVAL_NS &&
                                lastProgressSaveNs.compareAndSet(lastSave, saveNow)
                            ) {
                                NetworkFolderIndexCache.saveWebDav(
                                    source.id,
                                    configKey,
                                    relativeDir,
                                    merged,
                                )
                            }
                        },
                    )
                }
                val fromRam = preferCompleteFolderGalleries(shallowMerged, deep)
                val stored = presentListingForZipAsDirToggle(
                    source,
                    configKey,
                    relativeDir,
                    fromRam,
                    sessionCurrent = true,
                    previousForZipNames = shallowMerged,
                    persist = true,
                )
                logcat("FolderIndex") {
                    "WebDAV deep classify source=${source.id} dir=$relativeDir " +
                        "entries=${stored.size} ms=${(System.nanoTime() - t1) / 1_000_000}"
                }
                ScanDiag.log(
                    "deep dir=$relativeDir entries=${stored.size} " +
                        "ms=${(System.nanoTime() - t1) / 1_000_000} " +
                        "total=${(System.nanoTime() - t0) / 1_000_000}",
                )
                stored
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            logcat("FolderIndex") {
                "WebDAV deep classify timed out source=${source.id} dir=$relativeDir; keeping shallow"
            }
            ScanDiag.log("deep TIMEOUT dir=$relativeDir; keeping shallow")
            shallowMerged
        } catch (e: Throwable) {
            logcat("FolderIndex") {
                "WebDAV deep classify failed source=${source.id} dir=$relativeDir " +
                    "(${e.message}); keeping shallow"
            }
            ScanDiag.log("deep FAILED dir=$relativeDir (${e::class.simpleName}: ${e.message}); keeping shallow")
            shallowMerged
        }
    }

    private data class SlimDirectoryRefresh(
        val entries: List<BrowseEntryRemote>,
        val removedDirectoryNames: Set<String>,
        val persist: Boolean = true,
    )

    private suspend fun listDirectoryUncached(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
    ): List<BrowseEntryRemote> {
        val children = listChildrenForRelativeDir(source, password, relativeDir)
        return classifyDirectoryChildren(source, password, relativeDir, children)
    }

    /**
     * Flat non-directory child basenames (one PROPFIND, no classify / peeks).
     * Used by HTTP access-dir so folder playlists match the real remote directory.
     */
    suspend fun listChildFileNames(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
    ): List<String> = withIOContext {
        if (ZipAsDirListing.splitZipBrowsePath(relativeDir) != null) return@withIOContext emptyList()
        WebDavClient.listChildren(source, password, relativeDir)
            .asSequence()
            .filterNot { it.isDirectory || it.name.startsWith('.') || isProtectedSystemName(it.name) }
            .map { it.name }
            .toList()
    }

    /**
     * Flat child files and directories (one PROPFIND, no classify / peeks).
     * Used by HTML loopback HTTP to serve a folder tree.
     */
    suspend fun listChildFilesAndDirs(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
    ): Pair<List<String>, List<String>> = withIOContext {
        if (ZipAsDirListing.splitZipBrowsePath(relativeDir) != null) {
            return@withIOContext emptyList<String>() to emptyList()
        }
        val children = WebDavClient.listChildren(source, password, relativeDir)
        val files = ArrayList<String>()
        val dirs = ArrayList<String>()
        for (child in children) {
            if (child.name.startsWith('.') || isProtectedSystemName(child.name)) continue
            if (child.isDirectory) dirs += child.name else files += child.name
        }
        files.sorted() to dirs.sorted()
    }

    /**
     * Cache-hit refresh: one PROPFIND for the current directory. Only new child folders
     * run the existing child/leaf peek classifier. Direct files are reconciled
     * (drop stale / add new) from the live listing.
     */
    private suspend fun listDirectorySlim(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
        cached: List<BrowseEntryRemote>,
        configKey: String,
    ): SlimDirectoryRefresh {
        val children = listChildrenForRelativeDir(source, password, relativeDir)
        if (isUntrustedSlimLiveListing(cached, children) ||
            isPartiallyTruncatedSlimListing(cached, children)
        ) {
            return SlimDirectoryRefresh(cached, emptySet(), persist = false)
        }
        val plan = planRemoteDirectorySlimRefresh(cached, children)
        val zipFileNames = if (Settings.browseZipAsDir.value) {
            ZipAsDirListing.zipFileNames(children)
        } else {
            emptySet()
        }
        // Re-peek hidden rows regardless of the Hidden-files setting. A cached row can be
        // hidden because the folder used to contain `.nomedia`; once that marker is gone
        // nothing else revisits it (slim skips it, the deep pass follows [knownRowsOf]),
        // so the folder stayed invisible through every refresh.
        val reappraiseHidden = cached.asSequence()
            .filterIsInstance<BrowseEntryRemote.Directory>()
            .filter {
                it.hidden &&
                    it.relativeName == it.name &&
                    !it.virtual &&
                    !isDotHiddenName(it.name) &&
                    '/' !in it.relativeName.replace('\\', '/')
            }
            .mapNotNull { row -> children.firstOrNull { it.isDirectory && it.name == row.name } }
            .toList()
        val deepHidden = if (com.hippo.ehviewer.Settings.browseShowHiddenFiles.value) {
            hiddenDirectoriesNeedingDeepScan(cached, children) + reappraiseHidden
        } else {
            reappraiseHidden
        }.distinctBy { it.name }
        val deepNames = deepHidden.mapTo(HashSet()) { it.name }
        val cachedZipAsDir = if (zipFileNames.isEmpty()) {
            emptySet()
        } else {
            ZipAsDirListing.cachedDirectZipAsDirNames(cached)
        }
        val newZips = if (zipFileNames.isEmpty()) {
            emptyList()
        } else {
            children.filter { it.name in zipFileNames && it.name !in cachedZipAsDir }
        }
        val toClassify = (plan.addedDirectories + deepHidden + newZips).distinctBy { it.name }
        val dirName = relativeDir.substringAfterLast('/').ifEmpty { source.displayName }
        val zipAdjustedUnreachable = plan.unreachableDirectoryNames - zipFileNames
        val recovered = plan.recoveredDirectoryNames
        val dirsUnchanged = plan.addedDirectories.isEmpty() &&
            zipAdjustedUnreachable.isEmpty() &&
            recovered.isEmpty()
        if (dirsUnchanged && deepHidden.isEmpty() && newZips.isEmpty()) {
            return SlimDirectoryRefresh(
                entries = replaceSlimDirectFilesFromLive(cached, children, dirName),
                removedDirectoryNames = emptySet(),
            )
        }
        val effectivePlan = RemoteDirectorySlimPlan(
            addedDirectories = toClassify,
            removedDirectoryNames = deepNames,
            unreachableDirectoryNames = zipAdjustedUnreachable,
            recoveredDirectoryNames = recovered,
        )
        val addedEntries = if (toClassify.isEmpty()) {
            emptyList()
        } else {
            classifyDirectoryChildren(source, password, relativeDir, toClassify)
        }
        val merged = replaceSlimDirectFilesFromLive(
            mergeRemoteDirectorySlimRefresh(cached, effectivePlan, addedEntries),
            children,
            dirName,
        )
        return SlimDirectoryRefresh(
            entries = merged,
            removedDirectoryNames = emptySet(),
        )
    }

    private suspend fun classifyDirectoryChildren(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
        children: List<RemoteChild>,
        previous: List<BrowseEntryRemote>? = null,
        onPartial: (suspend (List<BrowseEntryRemote>) -> Unit)? = null,
    ): List<BrowseEntryRemote> {
        val deepScanHidden = com.hippo.ehviewer.Settings.browseShowHiddenFiles.value
        // Resume: a child an interrupted earlier pass already classified is carried over and
        // not peeked again. Without this a layer with hundreds of children restarts from
        // zero on every visit and never converges.
        val known = knownRowsOf(previous)
        // Dot folders: always tag-only (never peek). `.nomedia` dirs peek only when Hidden on.
        // A cached row that was hidden is deliberately absent from [known], so it reaches
        // this filter again and gets re-peeked — that is how a removed `.nomedia` heals.
        val dirsToPeek = children.filter { c ->
            c.isDirectory &&
                !isProtectedSystemName(c.name) &&
                !isDotHiddenName(c.name) &&
                (deepScanHidden || !c.hidden) &&
                c.name !in known
        }
        val peeks = ConcurrentHashMap<String, List<RemoteChild>>()
        val grandPeeks = ConcurrentHashMap<String, List<RemoteChild>>()
        val dirName = relativeDir.substringAfterLast('/').ifEmpty { source.displayName }
        val zipAsDir = Settings.browseZipAsDir.value
        val gate = Semaphore(peekConcurrency())

        /** Carry forward rows of children this pass did not re-peek (they stay Pending). */
        fun withKnown(rows: List<BrowseEntryRemote>): List<BrowseEntryRemote> = applyKnownRows(rows, known)

        // Progressive paint while the layer is being classified. Throttled so a folder with
        // hundreds of children repaints in steps rather than once per child; CAS keeps the
        // stamp unique across the parallel peek chains.
        val lastPublishNs = AtomicLong(0L)
        suspend fun publishPartial() {
            val callback = onPartial ?: return
            val now = System.nanoTime()
            val last = lastPublishNs.get()
            if (now - last < BROWSE_PROGRESS_PUBLISH_INTERVAL_NS) return
            if (!lastPublishNs.compareAndSet(last, now)) return
            callback(
                withKnown(
                    if (zipAsDir) {
                        val nonZip = children.filter { it.isDirectory || !isZipArchiveFileName(it.name) }
                        classifyRemoteListingWithPeeks(dirName, nonZip, peeks, grandPeeks) +
                            ZipAsDirListing.pendingZipDirectoryRows(children)
                    } else {
                        classifyRemoteListingWithPeeks(dirName, children, peeks, grandPeeks)
                    },
                ),
            )
        }

        if (dirsToPeek.isNotEmpty()) {
            val chainsStart = System.nanoTime()
            coroutineScope {
                dirsToPeek.map { c ->
                    async {
                        gate.withPermit {
                            val chainStart = System.nanoTime()
                            val childRel = joinRelative(relativeDir, c.name)
                            val peek = peekWithRetry(source, password, childRel)
                            val peekMs = (System.nanoTime() - chainStart) / 1_000_000
                            if (peek == null) {
                                // Unknown, not empty: leaving `peeks` without this child keeps
                                // its row at DirPresence.Pending, so nothing records a bogus
                                // "empty folder" and the resume pass re-peeks it.
                                ScanDiag.log(
                                    "chain UNRESOLVED dir=$relativeDir sub=${c.name} " +
                                        "peekMs=$peekMs — left Pending",
                                )
                                return@withPermit
                            }
                            // One child's whole chain: a folder of folders takes its cover
                            // from one level below, so its leaves are walked here rather than
                            // in a second wave after every sibling. That is what lets this
                            // folder show its cover without waiting for the rest of the layer.
                            //
                            // First peek already ran (needed for `.nomedia` detection); hidden
                            // dirs are not scanned into when Hidden files is off.
                            val leaves = if (!deepScanHidden && peekIndicatesHiddenDir(c.name, peek)) {
                                emptyList()
                            } else {
                                peek.filter { it.isDirectory && isPromotableLeafDirName(it.name) }
                            }
                            val leavesToPeek = if (leaves.size in 1..SMB_PROMOTE_MAX_LEAVES) {
                                leaves
                            } else {
                                // Cover-only: first leaf when promote budget exceeded.
                                leaves.take(1)
                            }
                            var leafUnresolved = false
                            for (leaf in leavesToPeek) {
                                val leafRel = joinRelative(childRel, leaf.name)
                                val leafPeek = peekWithRetry(source, password, leafRel)
                                if (leafPeek == null) {
                                    leafUnresolved = true
                                    break
                                }
                                grandPeeks["${c.name}/${leaf.name}"] = leafPeek
                            }
                            if (leafUnresolved) {
                                // A missing grand peek would mis-classify the child (no leaf
                                // promote → no gallery row), so treat the whole chain as
                                // unresolved rather than settle for a wrong row.
                                ScanDiag.log(
                                    "chain UNRESOLVED dir=$relativeDir sub=${c.name} " +
                                        "(leaf) — left Pending",
                                )
                                return@withPermit
                            }
                            // Publish the child only once its whole chain is in, so the row it
                            // paints is already its final shape.
                            peeks[c.name] = peek
                            publishPartial()
                            ScanDiag.log(
                                "chain dir=$relativeDir sub=${c.name} entries=${peek.size} " +
                                    "peekMs=$peekMs leaves=${leavesToPeek.size} " +
                                    "chainMs=${(System.nanoTime() - chainStart) / 1_000_000}",
                            )
                        }
                    }
                }.awaitAll()
            }
            ScanDiag.log(
                "chains done dir=$relativeDir children=${dirsToPeek.size} " +
                    "ms=${(System.nanoTime() - chainsStart) / 1_000_000}",
            )
        }

        val zipListings = if (zipAsDir &&
            children.any { !it.isDirectory && isZipArchiveFileName(it.name) }
        ) {
            val zipStart = System.nanoTime()
            val out = zipRootListings(source, password, relativeDir, children)
            ScanDiag.log(
                "zip roots dir=$relativeDir zips=${out.size} " +
                    "ms=${(System.nanoTime() - zipStart) / 1_000_000}",
            )
            out
        } else {
            emptyMap()
        }
        return withKnown(
            ZipAsDirListing.classifyListingWithZipAsDirs(
                currentDirName = dirName,
                children = children,
                childPeeks = peeks,
                grandPeeks = grandPeeks,
            ) { zipListings[it] },
        )
    }

    private suspend fun listZipVirtualDirectory(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
        zipRel: String,
        inner: String,
        useCache: Boolean,
        onCached: ((List<BrowseEntryRemote>) -> Unit)?,
    ): List<BrowseEntryRemote> {
        val configKey = sourceConfigKey(source)
        val zipName = zipRel.substringAfterLast('/')
        val parentRel = ZipAsDirListing.parentRelative(zipRel)
        val parentEntries = BrowseSession.getWebDavListing(source.id, parentRel)
            ?: NetworkFolderIndexCache.loadWebDav(source.id, configKey, parentRel)
        val stale = ZipAsDirListing.isZipAsDirStale(parentEntries, zipName)
        if (useCache && !stale) {
            val cached = BrowseSession.getWebDavListing(source.id, relativeDir)
                ?: NetworkFolderIndexCache.loadWebDav(source.id, configKey, relativeDir)
            if (cached != null) {
                // EOCD listings are complete; there is no slim scan of a virtual zip path.
                BrowseSession.putWebDavListing(source.id, relativeDir, cached, sessionCurrent = true)
                onCached?.invoke(cached)
                return cached
            }
        } else if (!useCache) {
            BrowseSession.invalidateWebDavListing(source.id, relativeDir)
        }
        if (stale) {
            BrowseSession.invalidateWebDavListingsUnder(source.id, zipRel)
            NetworkFolderIndexCache.removeWebDavUnder(source.id, zipRel)
        }
        val title = inner.substringAfterLast('/').ifEmpty {
            zipName.ifEmpty { source.displayName }
        }
        val entries = withIOContext {
            try {
                WebDavArchiveByteSource(
                    source,
                    password,
                    zipRel,
                    pipeline = false,
                    readahead = false,
                ).use { src ->
                    val cd = ZipCentralDirectory.open(src, ZipCdParse.Enter) ?: return@use emptyList()
                    persistZipVirtualFolderTree(source, configKey, zipRel, cd)
                    if (stale && parentEntries != null) {
                        clearZipAsDirStaleOnParent(source, configKey, zipRel, zipName, parentEntries)
                    }
                    BrowseSession.getWebDavListing(source.id, relativeDir)
                        ?: ZipAsDirListing.listingAt(cd, inner, title)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
        }
        onCached?.invoke(entries)
        return entries
    }

    private suspend fun zipRootListings(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
        children: List<RemoteChild>,
    ): Map<String, ZipAsDirListing.ZipRootListing> {
        if (!Settings.browseZipAsDir.value) return emptyMap()
        val zips = children.filter { !it.isDirectory && isZipArchiveFileName(it.name) }
        if (zips.isEmpty()) return emptyMap()
        val out = ConcurrentHashMap<String, ZipAsDirListing.ZipRootListing>()
        val interiors = ConcurrentHashMap<String, List<BrowseEntryRemote>>()
        val t0 = System.nanoTime()
        val gate = Semaphore(peekConcurrency())
        coroutineScope {
            zips.map { child ->
                async {
                    gate.withPermit {
                        val zipRel = joinRelative(relativeDir, child.name)
                        runCatching {
                            WebDavArchiveByteSource(
                                source,
                                password,
                                zipRel,
                                pipeline = false,
                                knownSize = child.size,
                                readahead = false,
                            ).use { src ->
                                val cd = ZipCentralDirectory.open(src, ZipCdParse.Parent) ?: return@use
                                out[child.name] = ZipAsDirListing.zipRootListingFromCd(cd)
                                interiors.putAll(ZipAsDirListing.parentListingInteriors(cd, child.name))
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        persistZipVirtualInteriors(source, sourceConfigKey(source), relativeDir, interiors)
        logcat("FolderIndex") {
            "WebDAV zip-as-dir EOCD source=${source.id} dir=$relativeDir " +
                "zips=${zips.size} ok=${out.size} interiors=${interiors.size} " +
                "ms=${(System.nanoTime() - t0) / 1_000_000}"
        }
        return out
    }

    private suspend fun persistZipVirtualFolderTree(
        source: WebDavSourceEntity,
        configKey: String,
        zipRel: String,
        cd: ZipCentralDirectory,
    ) {
        val zipName = zipRel.substringAfterLast('/')
        persistZipVirtualInteriors(
            source,
            configKey,
            ZipAsDirListing.parentRelative(zipRel),
            ZipAsDirListing.virtualFolderTree(cd, zipName),
        )
    }

    private suspend fun persistZipVirtualInteriors(
        source: WebDavSourceEntity,
        configKey: String,
        parentRelativeDir: String,
        interiors: Map<String, List<BrowseEntryRemote>>,
    ) {
        if (interiors.isEmpty()) return
        ZipAsDirListing.persistFolderIndexes(
            parentRelativeDir = parentRelativeDir,
            interiors = interiors,
            saveAll = { folders ->
                NetworkFolderIndexCache.saveWebDavAll(source.id, configKey, folders)
            },
            putRam = { dir, entries ->
                BrowseSession.putWebDavListing(source.id, dir, entries, sessionCurrent = true)
            },
        )
    }

    private suspend fun clearZipAsDirStaleOnParent(
        source: WebDavSourceEntity,
        configKey: String,
        zipRel: String,
        zipName: String,
        parentEntries: List<BrowseEntryRemote>,
    ) {
        val cleared = ZipAsDirListing.clearZipAsDirStale(parentEntries, zipName)
        if (cleared === parentEntries) return
        val parent = ZipAsDirListing.parentRelative(zipRel)
        val stored = NetworkFolderIndexCache.saveWebDav(source.id, configKey, parent, cleared)
        BrowseSession.putWebDavListing(source.id, parent, stored, sessionCurrent = true)
    }

    /**
     * Shape a listing for the current zip-as-dir toggle and land it in RAM.
     *
     * On: keep zip Directory/FolderGallery rows. [persist] writes the parent index
     * (deep classify). Slim/cache hits only [BrowseSession.putWebDavListing] so
     * session-current can allow folder thumbs.
     *
     * Off: demote zip rows to ArchiveGallery, persist that, and drop interior keys.
     */
    private suspend fun presentListingForZipAsDirToggle(
        source: WebDavSourceEntity,
        configKey: String,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
        sessionCurrent: Boolean,
        previousForZipNames: List<BrowseEntryRemote>? = null,
        persist: Boolean = false,
    ): List<BrowseEntryRemote> {
        if (Settings.browseZipAsDir.value) {
            val stored = if (persist) {
                NetworkFolderIndexCache.saveWebDav(source.id, configKey, relativeDir, entries)
            } else {
                entries
            }
            BrowseSession.putWebDavListing(source.id, relativeDir, stored, sessionCurrent = sessionCurrent)
            return stored
        }
        var zips = ZipAsDirListing.cachedDirectZipAsDirNames(previousForZipNames ?: entries)
        if (zips.isEmpty()) {
            zips = ZipAsDirListing.cachedDirectZipAsDirNames(
                NetworkFolderIndexCache.loadWebDav(source.id, configKey, relativeDir).orEmpty(),
            )
        }
        val presented = ZipAsDirListing.demoteZipFoldersToArchives(entries)
        if (zips.isEmpty() && presented == entries && !persist) {
            BrowseSession.putWebDavListing(source.id, relativeDir, entries, sessionCurrent = sessionCurrent)
            return entries
        }
        val stored = NetworkFolderIndexCache.saveWebDav(
            source.id,
            configKey,
            relativeDir,
            presented,
            zips,
        )
        BrowseSession.putWebDavListing(source.id, relativeDir, stored, sessionCurrent = sessionCurrent)
        for (name in zips) {
            BrowseSession.invalidateWebDavListingsUnder(source.id, joinRelative(relativeDir, name))
        }
        return stored
    }

    /** One PROPFIND, reused when a parent peek already listed this relative path. */
    private suspend fun listChildrenForRelativeDir(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
    ): List<RemoteChild> {
        val start = System.nanoTime()
        // `live` flips only when the loader actually runs: a hit returns from
        // rememberWebDavRawChildren without touching the network.
        var live = false
        val children = BrowseSession.rememberWebDavRawChildren(source.id, relativeDir) {
            live = true
            WebDavClient.listChildren(source, password, relativeDir)
        }
        ScanDiag.log(
            "propfind dir=$relativeDir count=${children.size} " +
                "src=${if (live) "live" else "ram-cache"} " +
                "ms=${(System.nanoTime() - start) / 1_000_000}",
        )
        return children
    }

    suspend fun listImageFileNames(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
    ): List<String> {
        // Complete index → no PROPFIND. Miss → live list like before (stateless HTTP).
        FolderGalleryIndex.loadWebDav(source.id, sourceConfigKey(source), relativeDir)?.let { return it }
        if (Settings.browseZipAsDir.value) {
            ZipAsDirListing.splitZipBrowsePath(relativeDir)?.let { (zipRel, inner) ->
                // Retry the EOCD/CD read. Returning an empty list here became a
                // full-screen "ZIP folder has no images" on a transient Range failure,
                // which is the intermittent error when opening the next gallery.
                var last: Throwable? = null
                repeat(LIST_IMAGE_ATTEMPTS) { attempt ->
                    val names = withIOContext {
                        runCatching {
                            WebDavArchiveByteSource(source, password, zipRel, pipeline = false).use { src ->
                                val cd = ZipCentralDirectory.open(src) ?: return@use emptyList()
                                ZipAsDirListing.directImageNames(cd, inner)
                            }
                        }.onFailure { if (it is CancellationException) throw it; last = it }.getOrNull()
                    }
                    if (names != null) return names
                    if (attempt < LIST_IMAGE_ATTEMPTS - 1) {
                        delay(LIST_IMAGE_RETRY_DELAY_MS * (attempt + 1))
                    }
                }
                throw last ?: IllegalStateException("Cannot read ZIP listing $zipRel")
            }
        }
        // Opening a gallery from History / double-tap sibling navigation lands here when
        // the folder index has no complete name list. A single transient PROPFIND failure
        // used to surface as "No images in WebDAV folder" even though the directory was
        // full of images, which is exactly the intermittent reader-open error.
        var last: Throwable? = null
        repeat(LIST_IMAGE_ATTEMPTS) { attempt ->
            try {
                return WebDavClient.listImageFileNames(source, password, relativeDir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                if (attempt < LIST_IMAGE_ATTEMPTS - 1) {
                    delay(LIST_IMAGE_RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        throw last ?: IllegalStateException("Cannot list WebDAV images in $relativeDir")
    }

    /**
     * Delete [targets] (remote relative path → isDirectory) one by one.
     *
     * Never throws for a per-item failure: a range delete of forty files where one is locked
     * must still remove the other thirty-nine, so failures are collected and returned.
     * [kotlinx.coroutines.CancellationException] is rethrown — swallowing it here once turned
     * a cancelled pass into thousands of bogus "this folder is empty" rows.
     *
     * Targets are filtered so no entry is deleted twice or survives as an orphan under a
     * deleted parent, then ordered deepest-first: a range can span a promoted directory and
     * its children, and removing children before their parent avoids 409/423 races.
     */
    suspend fun deleteEntries(
        source: WebDavSourceEntity,
        password: String,
        targets: List<DeleteTarget>,
    ): DeleteResult = withIOContext {
        val work = normalizeDeleteTargets(targets)
        if (work.isEmpty()) return@withIOContext DeleteResult(deleted = 0, failures = emptyList())

        val failures = ArrayList<DeleteFailure>()
        var deleted = 0
        // Chunked rather than one coroutine per target: the client already gates each request
        // on listSlots, so a flat fan-out would only pile up parked coroutines.
        val budget = ThumbConcurrency.thumbBudget().coerceAtLeast(1)
        for (chunk in work.chunked(budget)) {
            coroutineContext.ensureActive()
            val errors = chunk.map { target ->
                async {
                    try {
                        WebDavClient.delete(source, password, target.remotePath, target.isDirectory)
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        e
                    }
                }
            }.awaitAll()
            errors.forEachIndexed { i, error ->
                if (error == null) {
                    deleted++
                } else {
                    failures += DeleteFailure(
                        remotePath = chunk[i].remotePath,
                        message = error.message ?: error.toString(),
                    )
                }
            }
        }
        DeleteResult(deleted = deleted, failures = failures)
    }

    /**
     * Drop duplicates and any target nested under another target, then sort deepest-first.
     * Deleting `A` makes every `A/…` entry a 404 at best and a race at worst.
     */
    internal fun normalizeDeleteTargets(targets: List<DeleteTarget>): List<DeleteTarget> {
        val byPath = LinkedHashMap<String, DeleteTarget>()
        for (target in targets) {
            val path = target.remotePath.replace('\\', '/').trim('/')
            if (path.isEmpty()) continue
            if (path.split('/').any { it == ".." || it == "." }) continue
            byPath.putIfAbsent(path, target.copy(remotePath = path))
        }
        val paths = byPath.keys
        return byPath.values
            .filterNot { target ->
                paths.any { other ->
                    other != target.remotePath && target.remotePath.startsWith("$other/")
                }
            }
            .sortedByDescending { it.remotePath.count { c -> c == '/' } }
    }
}

/** One WebDAV delete target: path relative to the share root, plus collection flag. */
data class DeleteTarget(
    val remotePath: String,
    val isDirectory: Boolean,
)

data class DeleteFailure(
    val remotePath: String,
    val message: String,
)

data class DeleteResult(
    val deleted: Int,
    val failures: List<DeleteFailure>,
) {
    val requested: Int get() = deleted + failures.size
    val isComplete: Boolean get() = failures.isEmpty()
}
