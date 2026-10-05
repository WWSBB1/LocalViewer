package com.hippo.ehviewer.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Bumped when the in-app player deletes a file, so the folder screen it was launched from
 * re-runs its loader and drops the row that no longer exists.
 *
 * The browsers already re-list when their own controls are used (delete mode edits its
 * `entries` in place) or when the source reconnects; a delete performed **in the player**
 * happens outside all of that, while the folder screen sits paused off-composition. Without
 * this signal the deleted file kept its row — most visibly on local roots, which have no
 * resume-driven reload, and on SMB while the source stayed connected.
 *
 * Screens read [revision] only as a loader key; the caches themselves are already rewritten
 * by the delete ([com.hippo.ehviewer.library.NetworkFolderIndexCache.replaceLocalListing] and
 * friends), so the re-run paints the surviving rows rather than a fresh scan.
 */
object BrowseListingInvalidation {
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun onFileDeleted() {
        _revision.update { it + 1 }
    }
}
