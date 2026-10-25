package com.milan.music.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStartPolicyTest {

    @Test
    fun `first launch with no widget request starts automatically but not explicitly`() {
        val decision = decidePlaybackStart(
            playRequest = 0L,
            handledPlayRequest = 0L,
            autoStarted = false,
        )
        assertTrue(decision.proceed)
        assertFalse(decision.explicitPlay)
    }

    @Test
    fun `cold widget request starts explicitly`() {
        val decision = decidePlaybackStart(
            playRequest = 1L,
            handledPlayRequest = 0L,
            autoStarted = false,
        )
        assertTrue(decision.proceed)
        assertTrue(decision.explicitPlay)
    }

    @Test
    fun `library refresh does not repeat an automatic start`() {
        val decision = decidePlaybackStart(
            playRequest = 0L,
            handledPlayRequest = 0L,
            autoStarted = true,
        )
        assertFalse(decision.proceed)
    }

    @Test
    fun `handled widget request is not replayed after a library refresh`() {
        val decision = decidePlaybackStart(
            playRequest = 1L,
            handledPlayRequest = 1L,
            autoStarted = true,
        )
        assertFalse(decision.proceed)
    }

    @Test
    fun `new widget request still starts while the app is alive`() {
        val decision = decidePlaybackStart(
            playRequest = 2L,
            handledPlayRequest = 1L,
            autoStarted = true,
        )
        assertTrue(decision.proceed)
        assertTrue(decision.explicitPlay)
    }

    @Test
    fun `an explicit request is consumed after activity state restoration`() {
        assertEquals(1L, nextPlayRequest(current = 0L, requested = true))
    }

    @Test
    fun `an ordinary activity entry does not invent a play request`() {
        assertEquals(0L, nextPlayRequest(current = 0L, requested = false))
    }
}
