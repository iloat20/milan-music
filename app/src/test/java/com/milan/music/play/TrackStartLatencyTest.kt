package com.milan.music.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackStartLatencyTest {

    @Test
    fun `latency is the gap between the transition and audible playback`() {
        assertEquals(180L, trackStartLatency(markAtMs = 1_000L, nowMs = 1_180L))
        assertEquals(0L, trackStartLatency(markAtMs = 7L, nowMs = 7L))
    }

    @Test
    fun `a stale mark is discarded instead of reported as a huge latency`() {
        assertNull(trackStartLatency(markAtMs = 0L, nowMs = 60_001L))
        assertEquals(60_000L, trackStartLatency(markAtMs = 0L, nowMs = 60_000L))
    }

    @Test
    fun `a mark from the future is not trusted`() {
        assertNull(trackStartLatency(markAtMs = 5_000L, nowMs = 4_999L))
    }
}
