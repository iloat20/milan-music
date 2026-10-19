package com.milan.music.play

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Adds a byte ceiling to [DefaultLoadControl]'s time-based buffer.
 *
 * ## Why
 *
 * `DefaultLoadControl` bounds buffering by *duration* — `maxBufferMs = 30 s` here. A
 * 30-second window is not a fixed amount of memory: at 1 411 kbps (CD FLAC) it is
 * 5.3 MB, at 4 600 kbps (24-bit/96 kHz FLAC) it is 17 MB, at 320 kbps MP3 it is 1.2 MB.
 * The time bound is the right one for stall protection and the wrong one for memory,
 * and the difference is only visible on high-bitrate files.
 *
 * The cap below is a **soft** ceiling in the strict sense: it is never allowed to
 * starve the buffer below `minBufferMs`. ExoPlayer's own logic is consulted first
 * (`delegate.shouldContinueLoading`), and the byte check only applies once the buffer
 * already holds the minimum. The effect is self-selecting by bitrate, without reading
 * a single `Format`:
 *
 * - MP3 320: 30 s is 1.2 MB — the cap is never reached, behaviour is unchanged.
 * - CD FLAC: stops near 23 s / 4 MB instead of 30 s / 5.3 MB.
 * - 24-bit FLAC: the 15 s floor costs 8.6 MB, so it stops at the floor — 8.6 MB
 *   instead of 17 MB.
 *
 * ## Why a delegating wrapper rather than a subclass
 *
 * `DefaultLoadControl` exposes no public constructor that takes a pre-built
 * `Builder`; the only options are the no-argument constructor (which creates its own
 * allocator and loses the `trimOnReset = false` pool) and a protected 13-argument
 * constructor. Delegation keeps the existing `Builder` configuration intact.
 *
 * ## The signature that must be overridden
 *
 * Media3 1.11 routes `shouldContinueLoading(LoadControl.Parameters)` and leaves the
 * three-argument overload as a default method on the interface. `DefaultLoadControl`
 * overrides the `Parameters` form only. Overriding the three-argument form here would
 * therefore compile, look correct, and never be called — the cap would be inert. The
 * `Parameters` overload is the one that matters; the three-argument one is deliberately
 * left at its interface default so this wrapper cannot disagree with the delegate.
 */
@OptIn(UnstableApi::class)
class ByteCappedLoadControl(
    private val delegate: DefaultLoadControl,
    private val allocator: DefaultAllocator,
    private val byteCap: Int,
    private val minBufferUs: Long,
) : LoadControl {

    /** Latched so the probe logs on transition only, not on every load evaluation. */
    private var gateClosed = false

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        // The delegate first: its answer encodes minBufferMs, the allocator's target
        // size and the ramp-up heuristics. The byte cap only ever takes load away
        // from an already-satisfied buffer, never adds it.
        if (!delegate.shouldContinueLoading(parameters)) return false

        // Below the floor the buffer is not yet able to survive one slow read, so the
        // cap must not apply — otherwise a high-bitrate file could stall here.
        if (parameters.bufferedDurationUs < minBufferUs) return true

        val allocated = allocator.totalBytesAllocated
        // Note what this number is: every segment the allocator holds, including
        // segments that were released back into the pool and are currently unused.
        // Because the pool is created with `trimOnReset = false` (see PlaybackService)
        // it survives track changes, so right after switching from a 24-bit file to a
        // lossy one it can still hold the larger pool. The gate may therefore read
        // "closed" for a moment even though the buffer holds less than the cap; the
        // minBuffer floor above covers that window, and DefaultLoadControl trims the
        // pool to the new track's target on `onTracksSelected`.
        val closed = allocated >= byteCap
        if (closed != gateClosed) {
            gateClosed = closed
            // The only evidence this cap does anything. Without it, "the gate never
            // closed" and "the gate closed on every track" look identical from outside.
            Log.i(TAG, "buffer byte gate ${if (closed) "closed" else "open"} at $allocated/$byteCap bytes")
        }
        return !closed
    }

    companion object {
        private const val TAG = "ByteCappedLoadControl"

        /**
         * 4 MB — above the 15 s floor for CD-rate FLAC (2.6 MB) with ~50% headroom,
         * and far above what a lossy track needs for the full 30 s (1.2 MB at 320
         * kbps), so lossy playback is unaffected.
         */
        const val DEFAULT_BYTE_CAP = 4 * 1024 * 1024
    }
}
