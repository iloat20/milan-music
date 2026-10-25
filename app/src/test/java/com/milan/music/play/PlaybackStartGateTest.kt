package com.milan.music.play

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that keeps a background library reconcile away from the first track.
 *
 * Both directions matter. Waiting after playback already started is pure latency, and
 * waiting forever on a device that never plays anything (no permission, empty library,
 * a MediaController that fails to bind) would mean the library never reconciles at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStartGateTest {

    @Test
    fun `an already audible player is not waited for`() = runTest {
        val gate = PlaybackStartGate()
        gate.markAudible()

        val startedAt = currentTime
        assertTrue(gate.awaitAudible(1_000))
        assertEquals(0L, currentTime - startedAt)
    }

    @Test
    fun `the wait ends when the first track becomes audible`() = runTest {
        val gate = PlaybackStartGate()
        launch {
            delay(240)
            gate.markAudible()
        }

        assertTrue(gate.awaitAudible(1_500))
        assertEquals(240L, currentTime)
    }

    @Test
    fun `a player that never starts is given up on`() = runTest {
        val gate = PlaybackStartGate()
        assertFalse(gate.awaitAudible(1_500))
        // Bounded, not infinite: the reconcile still runs, just later.
        assertEquals(1_500L, currentTime)
    }

    @Test
    fun `signalling twice stays audible`() = runTest {
        val gate = PlaybackStartGate()
        gate.markAudible()
        gate.markAudible()

        assertTrue(gate.isAudible)
        assertTrue(gate.awaitAudible(0))
    }
}
