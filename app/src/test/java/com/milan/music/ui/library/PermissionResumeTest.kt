package com.milan.music.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionResumeTest {

    @Test
    fun `granting from the settings page loads the library`() {
        assertEquals(
            PermissionResume.GRANTED,
            permissionResumeAction(granted = true, wasGranted = false),
        )
    }

    /**
     * The direction that used to be missing: `hasAudioPermission` stayed `true`, the
     * list kept showing rows that could no longer be opened, and every tap was silent.
     */
    @Test
    fun `revoking from the settings page brings the gate back`() {
        assertEquals(
            PermissionResume.REVOKED,
            permissionResumeAction(granted = false, wasGranted = true),
        )
    }

    @Test
    fun `an unchanged grant does nothing`() {
        assertEquals(
            PermissionResume.UNCHANGED,
            permissionResumeAction(granted = true, wasGranted = true),
        )
        assertEquals(
            PermissionResume.UNCHANGED,
            permissionResumeAction(granted = false, wasGranted = false),
        )
    }
}
