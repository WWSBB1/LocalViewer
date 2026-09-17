package com.hippo.ehviewer.library

/**
 * TEMPORARY browse/thumbnail diagnostics.
 *
 * The app's own [com.ehviewer.core.util.logcat] output never reaches Logcat in the
 * self-install release build, so this writes through [android.util.Log] — the same channel
 * [com.hippo.ehviewer.webdav.WebDavClient] already uses. Everything lands under one tag so
 * a single `adb logcat -s LVScan` capture covers listing, caching and cover loading.
 *
 * Delete this file and its call sites once the slow-folder question is settled.
 */
internal object ScanDiag {
    const val TAG = "LVScan"

    fun log(message: String) {
        android.util.Log.i(TAG, message)
    }
}
