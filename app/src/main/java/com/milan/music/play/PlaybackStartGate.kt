package com.milan.music.play

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-way signal: "a track is actually audible".
 *
 * Filled in by [PlaybackService] the first time `isPlaying` goes true, read by the
 * library so that a background reconcile does not compete with the first decode and the
 * first read of the audio file.
 *
 * Process-scoped and never reset. Playback starting again later is not an event the
 * library needs: once something has been audible, the cold-start window this protects
 * is over.
 */
class PlaybackStartGate {

    private val audible = MutableStateFlow(false)

    val isAudible: Boolean get() = audible.value

    fun markAudible() {
        audible.value = true
    }

    /**
     * Returns true if playback was or became audible within [timeoutMs].
     *
     * False means the caller should stop waiting and get on with its work; it is not a
     * failure, and nothing here is retried.
     */
    suspend fun awaitAudible(timeoutMs: Long): Boolean {
        if (audible.value) return true
        return withTimeoutOrNull(timeoutMs) { audible.first { it } } ?: false
    }
}
