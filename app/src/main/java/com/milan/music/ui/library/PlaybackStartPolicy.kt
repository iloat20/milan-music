package com.milan.music.ui.library

internal data class PlaybackStartDecision(
    val proceed: Boolean,
    val explicitPlay: Boolean,
)

/**
 * Decides whether the library-ready effect may (re)start playback.
 *
 * A widget play tap is a one-shot command, not a mode. Once its id has been handled,
 * later library refreshes must not repeat it — otherwise a user pause is undone by
 * the next scan.
 */
internal fun decidePlaybackStart(
    playRequest: Long,
    handledPlayRequest: Long,
    autoStarted: Boolean,
): PlaybackStartDecision {
    val explicit = playRequest > handledPlayRequest
    return PlaybackStartDecision(
        proceed = !autoStarted || explicit,
        explicitPlay = explicit,
    )
}

/**
 * Advances the one-shot id when the current intent carries a play command.
 *
 * The decision deliberately has no `savedInstanceState` input: a new explicit intent
 * remains a new command even when Android recreates the activity from saved state.
 */
internal fun nextPlayRequest(current: Long, requested: Boolean): Long =
    if (requested) current + 1L else current
