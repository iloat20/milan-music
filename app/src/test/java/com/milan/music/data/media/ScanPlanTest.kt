package com.milan.music.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decides what a launch scan is allowed to cost the first track.
 *
 * The two cases are not the same job. Without a cache the scan *is* the first paint:
 * the user is looking at an empty list, so it must run at full speed and must not wait
 * for anything. With a cache the rows are already on screen, so the scan is a silent
 * reconcile that only competes with the audio path — and that one may be slowed down
 * and delayed.
 */
class ScanPlanTest {

    @Test
    fun `a library without a cache paints at full priority`() {
        val plan = scanPlanFor(hasCachedRows = false)
        assertEquals(ScanMode.INTERACTIVE, plan.mode)
        assertFalse(plan.waitForPlayback)
    }

    @Test
    fun `a cached library reconciles in the background`() {
        val plan = scanPlanFor(hasCachedRows = true)
        assertEquals(ScanMode.RECONCILE, plan.mode)
        assertTrue(plan.waitForPlayback)
    }

    @Test
    fun `a reconcile yields the scheduler to playback`() {
        // Process.THREAD_PRIORITY_* is the Linux nice scale: a larger number is a
        // lower priority, which is what makes the decoder win when both want the CPU
        // and the flash.
        assertTrue(
            "reconcile must run below the interactive scan",
            scanThreadPriority(ScanMode.RECONCILE) > scanThreadPriority(ScanMode.INTERACTIVE),
        )
    }
}
