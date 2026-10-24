package com.milan.music.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The now-playing bars are an infinite 60 fps animation. HomeScreen stays composed
 * under the player sheet, so without this gate the animation keeps waking the
 * Choreographer for a row that is completely covered.
 */
class NowPlayingIndicatorTest {

    @Test
    fun `bars animate for the visible playing row`() {
        assertTrue(shouldAnimatePlayingBars(isPlaying = true, uiCovered = false))
    }

    @Test
    fun `bars stop while the player sheet covers the library`() {
        assertFalse(shouldAnimatePlayingBars(isPlaying = true, uiCovered = true))
    }

    @Test
    fun `bars do not animate while paused`() {
        assertFalse(shouldAnimatePlayingBars(isPlaying = false, uiCovered = false))
        assertFalse(shouldAnimatePlayingBars(isPlaying = false, uiCovered = true))
    }
}
