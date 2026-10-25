package com.milan.music.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetPlayPolicyTest {

    @Test
    fun `loaded queue toggles playback in place`() {
        assertEquals(WidgetPlayAction.TOGGLE, widgetPlayAction(hasQueue = true))
    }

    @Test
    fun `empty queue opens the app and asks it to play`() {
        assertEquals(WidgetPlayAction.OPEN_APP_AND_PLAY, widgetPlayAction(hasQueue = false))
    }

    @Test
    fun `stale toggle opens the app when the new process has no queue`() {
        assertEquals(WidgetPlayAction.OPEN_APP_AND_PLAY, widgetToggleAction(hasQueue = false))
    }
}
