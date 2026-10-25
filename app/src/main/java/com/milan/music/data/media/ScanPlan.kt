package com.milan.music.data.media

import android.os.Process

/**
 * What a library scan is competing with on this launch.
 *
 * The distinction exists because the same 1 200-row cursor walk costs something very
 * different in the two cases. See [scanPlanFor].
 */
internal enum class ScanMode {
    /** No rows on screen yet: this scan is the first paint and nothing may delay it. */
    INTERACTIVE,

    /** Rows are already on screen: this scan is only reconciling against the index. */
    RECONCILE,
}

/**
 * How to run one scan.
 *
 * [waitForPlayback] is separate from [mode] only so that the reason can be read at the
 * call site instead of being re-derived from the enum there.
 */
internal data class ScanPlan(
    val mode: ScanMode,
    val waitForPlayback: Boolean,
)

/**
 * The launch scan's budget depends on whether the user is already looking at rows.
 *
 * A cold start has two independent users of the same flash and the same CPU: the
 * reconcile that walks the media index, and the first track, which has to open a file,
 * spin up a decoder and fill a 120 ms `AudioTrack` buffer. The second one is audible,
 * the first one is not — so when cached rows are already on screen the scan steps back
 * in both scheduling and time: lower priority (a larger nice value keeps it out of the
 * decoder's way) and a start that waits for the first track to be audible.
 *
 * With no cache the trade-off inverts: nothing is on screen, the scan *is* the first
 * paint, and delaying it would only make the empty list last longer.
 */
internal fun scanPlanFor(hasCachedRows: Boolean): ScanPlan =
    if (hasCachedRows) {
        ScanPlan(mode = ScanMode.RECONCILE, waitForPlayback = true)
    } else {
        ScanPlan(mode = ScanMode.INTERACTIVE, waitForPlayback = false)
    }

/**
 * Thread priority for [mode], on the Linux nice scale where larger is less important.
 *
 * `THREAD_PRIORITY_BACKGROUND` also drops the thread into the background cgroup, and on
 * Android that group's I/O weight applies to the binder call as well — the work done
 * inside MediaProvider on our behalf inherits it. That is the point: the reconcile
 * yields the flash to the file read that the user is waiting to hear.
 */
internal fun scanThreadPriority(mode: ScanMode): Int = when (mode) {
    ScanMode.INTERACTIVE -> Process.THREAD_PRIORITY_DEFAULT
    ScanMode.RECONCILE -> Process.THREAD_PRIORITY_BACKGROUND
}

/**
 * Upper bound on the wait described by [ScanPlan.waitForPlayback].
 *
 * Reaching it is normal, not an error: a device with no storage permission, an empty
 * library or a MediaController that fails to bind never becomes audible, and the
 * reconcile still has to happen. The bound is short enough that a launch which will
 * never play anything is not visibly slower than before.
 */
internal const val SCAN_DEFER_TIMEOUT_MS = 1_500L
