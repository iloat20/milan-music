package com.milan.music.widget

/**
 * What the widget card draws right now.
 *
 * `hasTrack = false` means there is nothing to show: the card falls back to the app
 * name and a plain play icon.
 */
internal data class WidgetDisplay(
    val hasTrack: Boolean,
    val isPlaying: Boolean,
)

/**
 * Resolves the widget's content from the live session first and the persisted snapshot
 * second.
 *
 * A bound [androidx.media3.session.MediaController] is always the more recent truth, so
 * the snapshot is only consulted when the process has no current track at all. That is
 * the ordinary cold start: nothing connects the session from
 * [com.milan.music.MilanMusicApp] — the library screen does — so the launcher's
 * `onUpdate` after a reboot, a launcher restart or a low-memory kill used to draw the
 * bare app name over a play icon while a queue was waiting in the snapshot to be
 * resumed.
 *
 * The icon follows the source it came from: a paused snapshot restores as a paused
 * session ([com.milan.music.play.PlaybackSnapshot.wasPlaying]), so the card has to show
 * the play triangle rather than the pause bars.
 *
 * Kept as a free function so the precedence is pinned by a unit test: the decision
 * itself is two lines, and "just read the snapshot too" is exactly the shortcut that
 * would make a stale snapshot overwrite live playback.
 */
internal fun resolveWidgetDisplay(
    hasLiveTrack: Boolean,
    livePlaying: Boolean,
    snapshotAvailable: Boolean,
    snapshotWasPlaying: Boolean,
): WidgetDisplay = when {
    hasLiveTrack -> WidgetDisplay(hasTrack = true, isPlaying = livePlaying)
    snapshotAvailable -> WidgetDisplay(hasTrack = true, isPlaying = snapshotWasPlaying)
    else -> WidgetDisplay(hasTrack = false, isPlaying = false)
}
