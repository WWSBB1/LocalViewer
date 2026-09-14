package com.hippo.ehviewer.smb

import com.hippo.ehviewer.library.ThumbConcurrency
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Fire-and-forget smbj handle close off the calling thread.
 *
 * Callers used to spawn `Thread { file.close() }.start()` per cancel/close — that is
 * unbounded under mass cancel (leave folder / many archive covers). Bounded pool instead,
 * with the width following the settings ([ThumbConcurrency.thumbBudget]) instead of a
 * fixed 1/4; idle threads time out so nothing is held when no close is pending.
 */
internal object SmbAsyncClose {
    private val pool = ThreadPoolExecutor(
        /* core */
        ThumbConcurrency.thumbBudget(),
        /* max */
        ThumbConcurrency.thumbBudget(),
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(32),
        { r -> Thread(r, "smb-async-close").apply { isDaemon = true } },
        // Prefer running on caller over dropping closes (handle leak); rare under pressure.
        ThreadPoolExecutor.CallerRunsPolicy(),
    ).also { it.allowCoreThreadTimeOut(true) }

    fun run(block: () -> Unit) {
        try {
            pool.execute {
                runCatching(block)
            }
        } catch (_: Throwable) {
            // Executor shutdown / reject — best-effort close on this thread.
            runCatching(block)
        }
    }
}
