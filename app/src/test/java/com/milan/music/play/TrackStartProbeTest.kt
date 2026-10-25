package com.milan.music.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackStartProbeTest {

    @Test
    fun `a paused snapshot does not count the wait before the user presses play`() {
        val probe = TrackStartProbe()

        assertNull(probe.onTransition(1_000L, playWhenReady = false, isPlaying = false))
        probe.onPlayWhenReadyChanged(playWhenReady = true, nowMs = 5_000L)

        assertEquals(150L, probe.onIsPlayingChanged(nowMs = 5_150L))
    }

    @Test
    fun `an automatic buffering transition measures until sound starts`() {
        val probe = TrackStartProbe()

        assertNull(probe.onTransition(2_000L, playWhenReady = true, isPlaying = false))
        assertEquals(180L, probe.onIsPlayingChanged(nowMs = 2_180L))
    }

    @Test
    fun `a seamless transition is recorded as no audible delay`() {
        val probe = TrackStartProbe()

        assertEquals(0L, probe.onTransition(3_000L, playWhenReady = true, isPlaying = true))
        assertNull(probe.onIsPlayingChanged(nowMs = 3_200L))
    }

    @Test
    fun `pausing clears a buffering mark before the next play request`() {
        val probe = TrackStartProbe()

        probe.onTransition(1_000L, playWhenReady = true, isPlaying = false)
        probe.onPlayWhenReadyChanged(playWhenReady = false, nowMs = 1_100L)
        probe.onPlayWhenReadyChanged(playWhenReady = true, nowMs = 8_000L)

        assertEquals(120L, probe.onIsPlayingChanged(nowMs = 8_120L))
    }

    @Test
    fun `a stale mark is discarded`() {
        val probe = TrackStartProbe()

        probe.onPlayWhenReadyChanged(playWhenReady = true, nowMs = 0L)

        assertNull(probe.onIsPlayingChanged(nowMs = 60_001L))
    }
}
