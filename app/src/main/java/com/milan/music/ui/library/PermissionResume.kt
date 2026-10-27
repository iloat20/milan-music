package com.milan.music.ui.library

/** What the library screen must do after re-reading the audio permission on resume. */
internal enum class PermissionResume {
    /** The grant did not change: the screen already matches it. */
    UNCHANGED,

    /** Granted from the system settings page: load the library and drop the gate. */
    GRANTED,

    /** Revoked from the system settings page: show the gate again. */
    REVOKED,
}

/**
 * Decides how a re-read of the audio permission on `ON_RESUME` changes the screen.
 *
 * Only the [GRANTED] direction is visible from the gate: a user who grants the
 * permission in the system settings comes back expecting a library, and leaving the
 * gate up makes the app look broken.
 *
 * [REVOKED] is the quieter failure and the worse one. The library list stays on screen
 * because the rows are already in memory, but every one of them points at a
 * `content://` URI the app may no longer read. Opening one fails inside
 * [com.milan.music.play.PlaybackService] with a single log line, so from the user's
 * side the row simply does not respond — there is no error, no dialog, nothing to
 * retry.
 *
 * A free function so both directions are pinned by a unit test: the screen cannot be
 * exercised without an Activity, and a `when` over two booleans is exactly the shape
 * that gets "simplified" back to one branch.
 */
internal fun permissionResumeAction(granted: Boolean, wasGranted: Boolean): PermissionResume = when {
    granted && !wasGranted -> PermissionResume.GRANTED
    !granted && wasGranted -> PermissionResume.REVOKED
    else -> PermissionResume.UNCHANGED
}
