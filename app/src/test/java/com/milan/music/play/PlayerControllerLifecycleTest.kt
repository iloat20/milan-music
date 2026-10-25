package com.milan.music.play

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControllerLifecycleTest {

    @Test
    fun `leaving the UI releases an idle controller with nothing pending`() {
        assertTrue(shouldReleaseOnUiStopped(queueSize = 0, pendingActionCount = 0))
    }

    @Test
    fun `leaving the UI keeps a pending play command alive`() {
        assertFalse(shouldReleaseOnUiStopped(queueSize = 0, pendingActionCount = 1))
    }

    @Test
    fun `leaving the UI keeps a loaded queue alive`() {
        assertFalse(shouldReleaseOnUiStopped(queueSize = 3, pendingActionCount = 0))
    }
}
