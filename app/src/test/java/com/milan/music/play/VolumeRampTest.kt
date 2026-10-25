package com.milan.music.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Player.volume` applies immediately, so setting it once on a track change is a step.
 *
 * Across a gapless boundary that step lands in the middle of continuous audio: the next
 * track starts under the previous track's correction, then jumps to its own a few
 * milliseconds later. These assertions describe the sequence that turns the step into a
 * short slide, and every one of them is a property of the numbers, so none of this needs
 * a player to verify.
 */
class VolumeRampTest {

    @Test
    fun `the last step is exactly the target`() {
        // Interpolating the final step arithmetically drifts: `start + (end - start)`
        // is not guaranteed to be bit-identical to `end`. The target is the one value
        // that must be exact, because it is what the track is supposed to play at.
        assertEquals(TARGET, volumeRamp(from = 0.71f, to = TARGET).last(), 0f)
    }

    @Test
    fun `the first step has already moved`() {
        // Starting the sequence with the current value means one redundant main-thread
        // write and a 15 ms window where nothing happens.
        val values = volumeRamp(from = 0.71f, to = 0.40f)
        assertTrue("first=${values.first()}", values.first() < 0.71f)
    }

    @Test
    fun `a decrease is monotonic`() {
        volumeRamp(from = 1f, to = 0.25f).zipWithNext().forEach { (a, b) ->
            assertTrue("$a then $b", a >= b)
        }
    }

    @Test
    fun `an increase is monotonic`() {
        volumeRamp(from = 0.25f, to = 1f).zipWithNext().forEach { (a, b) ->
            assertTrue("$a then $b", a <= b)
        }
    }

    @Test
    fun `the sequence is long enough to be heard as a slide`() {
        assertEquals(VOLUME_RAMP_STEPS, volumeRamp(from = 1f, to = 0.25f).size)
    }

    @Test
    fun `a negligible difference is applied in one step`() {
        // Ten wakeups to move the gain by a thousandth is overhead with no audible
        // benefit; the caller uses the length to decide whether to wait at all.
        assertEquals(1, volumeRamp(from = 0.500f, to = 0.503f).size)
    }

    @Test
    fun `an identical target is a single write`() {
        assertEquals(listOf(0.5f), volumeRamp(from = 0.5f, to = 0.5f))
    }

    @Test
    fun `steps never leave the player range`() {
        // `setVolume` would clamp these anyway, but a clamped step is a step that does
        // not move, which breaks monotonicity in the caller's eyes.
        volumeRamp(from = -1f, to = 2f).forEach { assertTrue("$it", it in 0f..1f) }
    }

    @Test
    fun `the ramp spans the configured duration`() {
        // The caller waits between steps, not after the last one, so the real duration
        // is `step * (steps - 1)`. Both constants are published as a contract; if the
        // spacing is derived from the step count without that subtraction, the ramp is
        // one interval shorter than [VOLUME_RAMP_MS] says it is.
        assertEquals(VOLUME_RAMP_MS, VOLUME_RAMP_STEP_MS * (VOLUME_RAMP_STEPS - 1))
    }

    @Test
    fun `a difference of exactly the threshold is applied in one step`() {
        // At the boundary the change is still inaudible, so it belongs on the cheap
        // side: eleven main-thread writes to move the gain by one percent is waste.
        assertEquals(1, volumeRamp(from = 1f, to = 1f - VOLUME_RAMP_MIN_DELTA).size)
    }

    @Test
    fun `a missing bound falls back to unity`() {
        // `coerceIn` passes NaN through, and `setVolume(NaN)` would leave the sink with
        // an undefined gain. Neither bound can be NaN today, but a silent full-scale
        // fallback is the only safe answer if one ever is.
        assertEquals(listOf(1f), volumeRamp(from = Float.NaN, to = 0.5f))
        assertEquals(listOf(1f), volumeRamp(from = 0.5f, to = Float.NaN))
    }
}

private const val TARGET = 0.40f
