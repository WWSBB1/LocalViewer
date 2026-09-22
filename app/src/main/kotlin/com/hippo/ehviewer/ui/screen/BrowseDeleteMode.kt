package com.hippo.ehviewer.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.isProtectedSystemName
import com.hippo.ehviewer.library.isZipArchiveFileName

/**
 * Stable identity of one browse row inside the flat delete order.
 *
 * Mirrors the Lazy `key` strings the WebDAV browser already builds for its rows
 * (`d-` / `g-` / `a-` / `v-` / `f-`), so the range order and the rendered order
 * can never drift apart.
 */
fun browseDeleteKey(entry: BrowseEntryRemote): String = when (entry) {
    is BrowseEntryRemote.Directory -> "d-${entry.relativeName}"
    is BrowseEntryRemote.FolderGallery -> if (entry.relativeName.isEmpty()) {
        "g-self"
    } else {
        "g-child-${entry.relativeName}"
    }
    is BrowseEntryRemote.ArchiveGallery -> "a-${entry.parentRelativeName}/${entry.fileName}"
    is BrowseEntryRemote.VideoFile -> "v-${entry.fileName}"
    is BrowseEntryRemote.RegularFile -> "f-${entry.fileName}"
}

/**
 * True when [entry] maps to a real remote node and can be deleted over WebDAV.
 *
 * Promoted / virtual rows (`@S`, `@S-leaf`, `presence == PromotedVideoLeaf`) are synthesized
 * by the classifier and address no real path — issuing DELETE for them would 404 or, worse,
 * hit an unrelated node. Same rule as [com.hippo.ehviewer.webdav.WebDavGateway.knownRowsOf].
 */
fun isDeletableRemoteEntry(entry: BrowseEntryRemote): Boolean {
    if (entry.virtual) return false
    if (entry is BrowseEntryRemote.Directory && entry.relativeName != entry.name) return false
    return !isProtectedSystemName(entry.name.substringAfterLast('/'))
}

/**
 * Remote path of [entry] relative to the share root, or null when it is not addressable.
 *
 * [listedDir] is the directory currently shown. Zip-as-dir member rows (`dir/file.zip/Album`)
 * are not addressable and return null — see [browseDeleteUnsupportedReason].
 */
fun browseDeleteRemotePath(entry: BrowseEntryRemote, listedDir: String): String? = when (entry) {
    is BrowseEntryRemote.Directory -> joinDeletePath(listedDir, entry.relativeName)
    is BrowseEntryRemote.FolderGallery -> joinDeletePath(listedDir, entry.relativeName)
    is BrowseEntryRemote.ArchiveGallery ->
        joinDeletePath(listedDir, joinDeletePath(entry.parentRelativeName, entry.fileName))
    is BrowseEntryRemote.VideoFile -> joinDeletePath(listedDir, entry.fileName)
    is BrowseEntryRemote.RegularFile -> joinDeletePath(listedDir, entry.fileName)
}

private fun joinDeletePath(parent: String, child: String): String {
    val p = parent.replace('\\', '/').trim('/')
    val c = child.replace('\\', '/').trim('/')
    return when {
        c.isEmpty() -> p
        p.isEmpty() -> c
        else -> "$p/$c"
    }
}

/** Whether [entry] addresses a collection (recursive delete) rather than a plain file. */
fun isDeletableDirectory(entry: BrowseEntryRemote): Boolean = when (entry) {
    is BrowseEntryRemote.Directory -> true
    // A folder gallery is a real folder on the server; a current-dir gallery ("g-self")
    // has an empty relativeName and is not a child node of itself.
    is BrowseEntryRemote.FolderGallery -> entry.relativeName.isNotEmpty()
    else -> false
}

/**
 * Why [entry] cannot be deleted, or null when it can.
 *
 * The caller shows this to the user instead of silently doing nothing — delete mode has no
 * second confirmation, so every rejected tap must be explained.
 *
 * Members of a zip-as-dir virtual folder never reach here: the whole layer refuses delete
 * mode (see the browser's `deleteUnsupported`), because those members address no WebDAV node.
 */
fun browseDeleteUnsupportedReason(entry: BrowseEntryRemote): BrowseDeleteReject? {
    if (entry.virtual) return BrowseDeleteReject.Virtual
    if (entry is BrowseEntryRemote.Directory && entry.relativeName != entry.name) {
        return BrowseDeleteReject.Virtual
    }
    if (entry is BrowseEntryRemote.FolderGallery && entry.relativeName.isEmpty()) {
        return BrowseDeleteReject.Virtual
    }
    // A zip segment *above* the row's own leaf means it lives inside an archive, not beside
    // it. A row whose leaf is itself the archive (`pack.zip`) is a normal deletable file.
    val path = browseDeleteRemotePath(entry, "")
    if (path != null) {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.dropLast(1).any { isZipArchiveFileName(it) }) {
            return BrowseDeleteReject.InsideArchive
        }
    }
    if (isProtectedSystemName(entry.name.substringAfterLast('/'))) {
        return BrowseDeleteReject.Protected
    }
    return null
}

enum class BrowseDeleteReject {
    /** Promoted / virtual row that addresses no real remote node. */
    Virtual,

    /** Member inside a zip-as-dir virtual folder. */
    InsideArchive,

    /** System junk (`#recycle`, `$ADMIN`, `@eaDir`, …). */
    Protected,
}

/**
 * Inclusive range between [anchorKey] and [tapKey] in [order].
 *
 * Entries that cannot be deleted are skipped rather than truncating the range, so a
 * protected name sitting between two images does not swallow the rest of the selection.
 * Returns an empty list when either key is missing.
 */
fun deleteRange(
    order: List<BrowseEntryRemote>,
    anchorKey: String,
    tapKey: String,
): List<BrowseEntryRemote> {
    val anchor = order.indexOfFirst { browseDeleteKey(it) == anchorKey }
    val tap = order.indexOfFirst { browseDeleteKey(it) == tapKey }
    if (anchor < 0 || tap < 0) return emptyList()
    val from = minOf(anchor, tap)
    val to = maxOf(anchor, tap)
    return order.subList(from, to + 1).filter(::isDeletableEntry)
}

/**
 * Deletable in both senses: a real remote node ([isDeletableRemoteEntry]) with nothing else
 * blocking it ([browseDeleteUnsupportedReason]). Range filtering uses this so a selected
 * batch never trips the per-entry rejection the caller reports separately.
 */
fun isDeletableEntry(entry: BrowseEntryRemote): Boolean =
    isDeletableRemoteEntry(entry) && browseDeleteUnsupportedReason(entry) == null

/**
 * WebDAV delete mode: tap a row to delete it, long-press to set a range anchor and tap
 * another row to delete everything in between.
 *
 * Deliberately **not** persisted in [com.hippo.ehviewer.library.BrowseSession] — a mode that
 * survives process death and is armed to delete on first tap is a footgun. The browser
 * resets it on path change instead.
 */
@Stable
class BrowseDeleteModeState {
    var active by mutableStateOf(false)
        private set

    /** Row key set by long-press; non-null means the next tap deletes a range. */
    var rangeAnchorKey by mutableStateOf<String?>(null)
        private set

    /** A batch is running: row taps are ignored so a double-tap cannot overlap batches. */
    var busy by mutableStateOf(false)
        private set

    fun enter() {
        active = true
        rangeAnchorKey = null
    }

    fun exit() {
        active = false
        rangeAnchorKey = null
        busy = false
    }

    /** Long-press: set (or move) the range anchor. */
    fun setAnchor(key: String) {
        rangeAnchorKey = if (rangeAnchorKey == key) null else key
    }

    fun clearAnchor() {
        rangeAnchorKey = null
    }

    fun beginBatch() {
        busy = true
    }

    fun endBatch() {
        busy = false
        rangeAnchorKey = null
    }

    /** @return true when the event was consumed (exit delete mode, no navigation). */
    fun handleBack(): Boolean {
        if (!active) return false
        exit()
        return true
    }
}

@Composable
fun rememberBrowseDeleteModeState(): BrowseDeleteModeState = remember { BrowseDeleteModeState() }

/**
 * Top-bar delete-mode toggle, left of the search action.
 *
 * Inactive: asks for confirmation before arming ([onEnter] shows the dialog). Active: the
 * same slot becomes the exit button so the mode is always one tap away from off.
 */
@Composable
fun BrowseDeleteModeAction(
    delete: BrowseDeleteModeState,
    enabled: Boolean,
    onEnter: () -> Unit,
) {
    if (delete.active) {
        IconButton(
            onClick = { delete.exit() },
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.browse_delete_mode),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    } else {
        IconButton(
            onClick = onEnter,
            enabled = enabled,
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.browse_delete_mode),
            )
        }
    }
}

