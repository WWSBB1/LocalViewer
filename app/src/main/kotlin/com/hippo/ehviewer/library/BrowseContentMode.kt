package com.hippo.ehviewer.library

/**
 * Folder-view content filter preset. Scanner always returns the full tagged list;
 * the UI filters instantly (same idea as name search) without re-listing.
 *
 * Pref int: 0=Galleries (default), 1=Media, 2=Video, 3=Folder, 4=FolderGallery.
 */
enum class BrowseContentMode(val prefValue: Int) {
    /** Gallery-related navigation dirs + folder/archive galleries. */
    Galleries(0),

    /** Galleries + video files. */
    Media(1),

    /** Enterable/video dirs + video files. */
    Video(2),

    /**
     * Full folder browser: all dirs + archives + videos + regular files
     * (including loose images). Synthetic folder galleries are hidden.
     */
    Folder(3),

    /**
     * [Folder] listing, except a leaf image folder is listed as its gallery row
     * (tapping it goes straight to the reader) instead of the enterable dir row.
     */
    FolderGallery(4),
    ;

    companion object {
        fun fromPref(value: Int): BrowseContentMode = entries.firstOrNull { it.prefValue == value } ?: Galleries
    }
}

/**
 * Directory visibility is based on independent content tags, not merely on whether
 * the directory is structurally enterable. This keeps video-only navigation out of
 * Galleries and unrelated navigation out of Video.
 */
fun DirPresence.visibleIn(
    mode: BrowseContentMode,
    hasGallery: Boolean,
    hasVideo: Boolean,
): Boolean = when (mode) {
    // Leaf image folders already have a FolderGallery row; only retain real navigation
    // branches that may lead to gallery content. Pending = shallow stub (paint now).
    // VideoOnly dirs stay visible: a video-only folder is still a real enterable
    // directory the user navigated into before — hiding it here made the folder
    // vanish from Galleries while History/favourites kept opening it fine.
    BrowseContentMode.Galleries ->
        this == DirPresence.Pending ||
            (this == DirPresence.Navigable && hasGallery) ||
            this == DirPresence.VideoOnly
    BrowseContentMode.Media -> when (this) {
        DirPresence.Pending -> true
        DirPresence.Navigable -> hasGallery || hasVideo
        DirPresence.LeafImages -> hasVideo // gallery row covers images; dir reaches videos
        DirPresence.VideoOnly, DirPresence.PromotedVideoLeaf -> true
        DirPresence.Empty, DirPresence.PromotedShell -> false
    }
    BrowseContentMode.Video ->
        this == DirPresence.Pending ||
            (
                hasVideo &&
                    this != DirPresence.Empty &&
                    this != DirPresence.PromotedShell
                )
    // Folder = real FS: hide virtual promoted video dirs (PromotedShell parent stays).
    BrowseContentMode.Folder -> this != DirPresence.PromotedVideoLeaf
    // Same dir set as Folder; leaf image dirs are dropped later by the gallery collapse.
    BrowseContentMode.FolderGallery -> this != DirPresence.PromotedVideoLeaf
}

/**
 * Single-video leaf promote uses a virtual display name (`@S` / `@S-leaf`) while
 * [BrowseEntry.VideoFile.path] points at the real file. Folder mode hides these.
 */
fun BrowseEntry.VideoFile.isPromotedVirtual(): Boolean = virtual || name != path.name

/**
 * Same promote as local: [BrowseEntryRemote.VideoFile.name] is the `@…` label;
 * [BrowseEntryRemote.VideoFile.fileName] is the real relative path (often multi-segment).
 */
fun BrowseEntryRemote.VideoFile.isPromotedVirtual(): Boolean = virtual || name != fileName

/**
 * [BrowseContentMode.FolderGallery] lists only the galleries that stand for a leaf image
 * dir — the synthetic / dual / promoted rows are dropped here so the main filter can treat
 * [BrowseEntry.FolderGallery] the same way Folder mode does. No-op for the other modes.
 */
private fun List<BrowseEntry>.applyLeafGalleryRule(mode: BrowseContentMode): List<BrowseEntry> {
    if (mode != BrowseContentMode.FolderGallery) return this
    val leafDirs = HashSet<String>()
    for (e in this) {
        if (e is BrowseEntry.Directory && e.presence == DirPresence.LeafImages) leafDirs += e.name
    }
    return filter { it !is BrowseEntry.FolderGallery || (!it.virtual && it.name in leafDirs) }
}

/** Same as [applyLeafGalleryRule] for remote (SMB / WebDAV) entries. */
private fun List<BrowseEntryRemote>.applyRemoteLeafGalleryRule(
    mode: BrowseContentMode,
): List<BrowseEntryRemote> {
    if (mode != BrowseContentMode.FolderGallery) return this
    val leafDirs = HashSet<String>()
    for (e in this) {
        if (e is BrowseEntryRemote.Directory && e.presence == DirPresence.LeafImages) leafDirs += e.name
    }
    return filter { it !is BrowseEntryRemote.FolderGallery || (!it.virtual && it.name in leafDirs) }
}

fun List<BrowseEntry>.filterByContentMode(
    mode: BrowseContentMode,
    showHiddenFiles: Boolean = true,
    showVirtualGalleries: Boolean = true,
): List<BrowseEntry> = applyLeafGalleryRule(mode).filter { e ->
    if (!showHiddenFiles && e.hidden) return@filter false
    if (!showVirtualGalleries && e.virtual) return@filter false
    when (mode) {
        BrowseContentMode.Galleries -> when (e) {
            is BrowseEntry.Directory -> {
                // When virtuals off, PromotedShell is the enterable real folder.
                if (!showVirtualGalleries && e.presence == DirPresence.PromotedShell) return@filter true
                e.presence.visibleIn(mode, e.hasGallery, e.hasVideo)
            }
            is BrowseEntry.FolderGallery, is BrowseEntry.ArchiveGallery -> true
            is BrowseEntry.VideoFile, is BrowseEntry.RegularFile -> false
        }
        BrowseContentMode.Media -> when (e) {
            is BrowseEntry.Directory -> {
                if (!showVirtualGalleries && e.presence == DirPresence.PromotedShell) return@filter true
                e.presence.visibleIn(mode, e.hasGallery, e.hasVideo)
            }
            is BrowseEntry.FolderGallery, is BrowseEntry.ArchiveGallery -> true
            is BrowseEntry.VideoFile -> true
            is BrowseEntry.RegularFile -> false
        }
        BrowseContentMode.Video -> when (e) {
            is BrowseEntry.Directory -> {
                if (!showVirtualGalleries && e.presence == DirPresence.PromotedShell) return@filter true
                e.presence.visibleIn(mode, e.hasGallery, e.hasVideo)
            }
            is BrowseEntry.VideoFile -> true
            is BrowseEntry.FolderGallery, is BrowseEntry.ArchiveGallery, is BrowseEntry.RegularFile -> false
        }
        BrowseContentMode.Folder -> when (e) {
            is BrowseEntry.Directory -> e.presence.visibleIn(mode, e.hasGallery, e.hasVideo)
            is BrowseEntry.ArchiveGallery, is BrowseEntry.RegularFile -> true
            // Real videos only — hide promoted rows (enter real dir instead).
            is BrowseEntry.VideoFile -> !e.virtual
            is BrowseEntry.FolderGallery -> false // synthetic / dual galleries
        }
        BrowseContentMode.FolderGallery -> when (e) {
            is BrowseEntry.Directory -> e.presence.visibleIn(mode, e.hasGallery, e.hasVideo)
            is BrowseEntry.ArchiveGallery, is BrowseEntry.RegularFile -> true
            is BrowseEntry.VideoFile -> !e.virtual
            // Leaf-dir galleries only (see [applyLeafGalleryRule]); the matching dir row
            // goes away in [collapseFolderGalleryDirs] so the folder is not listed twice.
            is BrowseEntry.FolderGallery -> true
        }
    }
}

/** Default minimum images for a folder gallery when "Small galleries" is off. */
const val BROWSE_SMALL_GALLERY_MIN_PAGES_DEFAULT = 3

/**
 * UI-only filter: when [showSmall] is false, drop folder/archive galleries with fewer
 * than [minPages] images. Capped folder counts and uncounted archives (pageCount 0)
 * are treated as large enough.
 * Does not touch the lazy scanner or listing cache.
 */
fun List<BrowseEntry>.filterSmallGalleries(
    showSmall: Boolean,
    minPages: Int = BROWSE_SMALL_GALLERY_MIN_PAGES_DEFAULT,
): List<BrowseEntry> {
    if (showSmall) return this
    val threshold = minPages.coerceAtLeast(1)
    return filter { e ->
        when (e) {
            is BrowseEntry.FolderGallery -> e.pageCountCapped || e.pageCount >= threshold
            is BrowseEntry.ArchiveGallery -> e.pageCount <= 0 || e.pageCount >= threshold
            else -> true
        }
    }
}

/**
 * [BrowseContentMode.FolderGallery] keeps a leaf image dir as its gallery row, so drop the
 * matching enterable dir row to avoid listing the folder twice.
 *
 * Runs **after** [filterSmallGalleries]: a dir whose gallery row was just hidden keeps its
 * dir row, so Folder reachability is never lost. A leaf dir that also holds videos keeps
 * its dir row too — the gallery row does not reach them. No-op for the other modes.
 */
fun List<BrowseEntry>.collapseFolderGalleryDirs(mode: BrowseContentMode): List<BrowseEntry> {
    if (mode != BrowseContentMode.FolderGallery) return this
    val covered = HashSet<String>()
    for (e in this) if (e is BrowseEntry.FolderGallery) covered += e.name
    if (covered.isEmpty()) return this
    return filterNot {
        it is BrowseEntry.Directory &&
            it.presence == DirPresence.LeafImages &&
            !it.hasVideo &&
            it.name in covered
    }
}

/** Same as [collapseFolderGalleryDirs] for remote (SMB / WebDAV) entries. */
fun List<BrowseEntryRemote>.collapseRemoteFolderGalleryDirs(
    mode: BrowseContentMode,
): List<BrowseEntryRemote> {
    if (mode != BrowseContentMode.FolderGallery) return this
    val covered = HashSet<String>()
    for (e in this) if (e is BrowseEntryRemote.FolderGallery) covered += e.name
    if (covered.isEmpty()) return this
    return filterNot {
        it is BrowseEntryRemote.Directory &&
            it.presence == DirPresence.LeafImages &&
            !it.hasVideo &&
            it.name in covered
    }
}

/** Same as [filterSmallGalleries] for remote (SMB / WebDAV) entries. */
fun List<BrowseEntryRemote>.filterRemoteSmallGalleries(
    showSmall: Boolean,
    minPages: Int = BROWSE_SMALL_GALLERY_MIN_PAGES_DEFAULT,
): List<BrowseEntryRemote> {
    if (showSmall) return this
    val threshold = minPages.coerceAtLeast(1)
    return filter { e ->
        when (e) {
            is BrowseEntryRemote.FolderGallery ->
                e.pageCountCapped || e.pageCount >= threshold
            is BrowseEntryRemote.ArchiveGallery -> e.pageCount <= 0 || e.pageCount >= threshold
            else -> true
        }
    }
}

fun List<BrowseEntryRemote>.filterRemoteByContentMode(
    mode: BrowseContentMode,
    showHiddenFiles: Boolean = true,
    showVirtualGalleries: Boolean = true,
): List<BrowseEntryRemote> {
    val unreachable = cachedUnreachableDirectoryNames(this)
    return applyRemoteLeafGalleryRule(mode).filter { e ->
        if (e.isUnderUnreachableFolder(unreachable)) return@filter false
        if (!showHiddenFiles && e.hidden) return@filter false
        if (!showVirtualGalleries && e.virtual) return@filter false
        when (mode) {
            BrowseContentMode.Galleries -> when (e) {
                is BrowseEntryRemote.Directory -> {
                    if (!showVirtualGalleries && e.presence == DirPresence.PromotedShell) return@filter true
                    e.presence.visibleIn(mode, e.hasGallery, e.hasVideo)
                }
                is BrowseEntryRemote.FolderGallery, is BrowseEntryRemote.ArchiveGallery -> true
                is BrowseEntryRemote.VideoFile, is BrowseEntryRemote.RegularFile -> false
            }
            BrowseContentMode.Media -> when (e) {
                is BrowseEntryRemote.Directory -> {
                    if (!showVirtualGalleries && e.presence == DirPresence.PromotedShell) return@filter true
                    e.presence.visibleIn(mode, e.hasGallery, e.hasVideo)
                }
                is BrowseEntryRemote.FolderGallery, is BrowseEntryRemote.ArchiveGallery -> true
                is BrowseEntryRemote.VideoFile -> true
                is BrowseEntryRemote.RegularFile -> false
            }
            BrowseContentMode.Video -> when (e) {
                is BrowseEntryRemote.Directory -> {
                    if (!showVirtualGalleries && e.presence == DirPresence.PromotedShell) return@filter true
                    e.presence.visibleIn(mode, e.hasGallery, e.hasVideo)
                }
                is BrowseEntryRemote.VideoFile -> true
                is BrowseEntryRemote.FolderGallery,
                is BrowseEntryRemote.ArchiveGallery,
                is BrowseEntryRemote.RegularFile,
                -> false
            }
            BrowseContentMode.Folder -> when (e) {
                is BrowseEntryRemote.Directory -> e.presence.visibleIn(mode, e.hasGallery, e.hasVideo)
                is BrowseEntryRemote.ArchiveGallery,
                is BrowseEntryRemote.RegularFile,
                -> true
                is BrowseEntryRemote.VideoFile -> !e.virtual
                is BrowseEntryRemote.FolderGallery -> false
            }
            BrowseContentMode.FolderGallery -> when (e) {
                is BrowseEntryRemote.Directory -> e.presence.visibleIn(mode, e.hasGallery, e.hasVideo)
                is BrowseEntryRemote.ArchiveGallery,
                is BrowseEntryRemote.RegularFile,
                -> true
                is BrowseEntryRemote.VideoFile -> !e.virtual
                // Leaf-dir galleries only — same rule as the local filter.
                is BrowseEntryRemote.FolderGallery -> true
            }
        }
    }
}

data class BrowseFolderSections<T>(
    val directories: List<T>,
    val galleries: List<T>,
    val videos: List<T>,
    val files: List<T>,
)

fun List<BrowseEntry>.toBrowseSections(): BrowseFolderSections<BrowseEntry> {
    val directories = ArrayList<BrowseEntry>()
    val galleries = ArrayList<BrowseEntry>()
    val videos = ArrayList<BrowseEntry>()
    val files = ArrayList<BrowseEntry>()
    val seenGallery = HashSet<String>()
    for (e in this) {
        when (e) {
            is BrowseEntry.Directory -> directories += e
            is BrowseEntry.FolderGallery -> {
                val id = "g-${e.path}|${e.relativeName}"
                if (seenGallery.add(id)) galleries += e
            }
            is BrowseEntry.ArchiveGallery -> {
                val id = "a-${e.path}"
                if (seenGallery.add(id)) galleries += e
            }
            is BrowseEntry.VideoFile -> videos += e
            is BrowseEntry.RegularFile -> files += e
        }
    }
    return BrowseFolderSections(directories, galleries, videos, files)
}

fun List<BrowseEntryRemote>.toRemoteBrowseSections(): BrowseFolderSections<BrowseEntryRemote> {
    val directories = ArrayList<BrowseEntryRemote>()
    val galleries = ArrayList<BrowseEntryRemote>()
    val videos = ArrayList<BrowseEntryRemote>()
    val files = ArrayList<BrowseEntryRemote>()
    val seenGallery = HashSet<String>()
    for (e in this) {
        when (e) {
            is BrowseEntryRemote.Directory -> directories += e
            is BrowseEntryRemote.FolderGallery -> {
                val id = "g-${e.relativeName}"
                if (seenGallery.add(id)) galleries += e
            }
            is BrowseEntryRemote.ArchiveGallery -> {
                val id = "a-${e.parentRelativeName}/${e.fileName}"
                if (seenGallery.add(id)) galleries += e
            }
            is BrowseEntryRemote.VideoFile -> videos += e
            is BrowseEntryRemote.RegularFile -> files += e
        }
    }
    return BrowseFolderSections(directories, galleries, videos, files)
}
