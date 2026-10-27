package com.milan.music.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetDisplayTest {

    @Test
    fun `a live playing track wins over a paused snapshot`() {
        val display = resolveWidgetDisplay(
            hasLiveTrack = true,
            livePlaying = true,
            snapshotAvailable = true,
            snapshotWasPlaying = false,
        )
        assertTrue(display.hasTrack)
        assertTrue(display.isPlaying)
    }

    /**
     * The precedence itself: a stale snapshot must never turn a paused session into a
     * pause icon.
     */
    @Test
    fun `a live paused track wins over a playing snapshot`() {
        val display = resolveWidgetDisplay(
            hasLiveTrack = true,
            livePlaying = false,
            snapshotAvailable = true,
            snapshotWasPlaying = true,
        )
        assertTrue(display.hasTrack)
        assertFalse(display.isPlaying)
    }

    @Test
    fun `a cold start shows the snapshot track and its play state`() {
        val display = resolveWidgetDisplay(
            hasLiveTrack = false,
            livePlaying = false,
            snapshotAvailable = true,
            snapshotWasPlaying = true,
        )
        assertTrue(display.hasTrack)
        assertTrue(display.isPlaying)
    }

    @Test
    fun `a paused snapshot restores as paused`() {
        val display = resolveWidgetDisplay(
            hasLiveTrack = false,
            livePlaying = false,
            snapshotAvailable = true,
            snapshotWasPlaying = false,
        )
        assertTrue(display.hasTrack)
        assertFalse(display.isPlaying)
    }

    @Test
    fun `nothing to show falls back to the app name`() {
        val display = resolveWidgetDisplay(
            hasLiveTrack = false,
            livePlaying = false,
            snapshotAvailable = false,
            snapshotWasPlaying = false,
        )
        assertFalse(display.hasTrack)
        assertFalse(display.isPlaying)
    }

    @Test
    fun `an empty snapshot is not a track`() {
        val display = resolveWidgetDisplay(
            hasLiveTrack = false,
            livePlaying = true,
            snapshotAvailable = false,
            snapshotWasPlaying = true,
        )
        assertFalse(display.hasTrack)
        assertEquals(false, display.isPlaying)
    }
}
