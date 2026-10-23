package com.milan.music.play

import android.content.ComponentCallbacks2
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.milan.music.MilanMusicApp
import com.milan.music.data.model.Track
import com.milan.music.ui.text.albumText
import com.milan.music.ui.text.artistText
import com.milan.music.ui.text.titleText

/**
 * Hosts the ExoPlayer instance in a foreground service so playback survives
 * when the activity is backgrounded, and exposes a MediaSession for the system
 * notification, lock screen and Bluetooth controls.
 *
 * Opted into `@UnstableApi` at class level: this service exists to configure ExoPlayer
 * internals (`setPreloadConfiguration`, `DefaultAllocator`, the renderers factory), so
 * every such reference here is deliberate. Member-level `@OptIn` was tried first and
 * does not satisfy lint's UnsafeOptInUsageError check for a private property.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var artworkLoader: ArtworkBitmapLoader? = null

    /**
     * The sample-buffer pool handed to [DefaultLoadControl].
     *
     * Held here so memory-pressure callbacks can return its segments before the
     * service dies; see [releaseBufferPool].
     */
    private var bufferAllocator: DefaultAllocator? = null

    /**
     * Queue metadata, resolved on demand instead of shipped with every item.
     *
     * [PlayerController.playQueue] hands the session bare `mediaId` + `uri` items.
     * Attaching a `MediaMetadata` to all of them meant building one per track in the
     * library — three strings and a `Uri` each — for every item the session never
     * displays, on the thread the user tapped on, and then writing it into a Parcel.
     * Only the current and the preloaded item are ever read back.
     *
     * `onSetMediaItems` is deliberately not overridden: media3's default implementation
     * resolves through this method, so one override covers `setMediaItems` and
     * `addMediaItems` alike.
     *
     * A miss returns the item unchanged instead of failing: the queue and the index
     * are written together in `playQueue`, but a session restored by the platform
     * after process death can legitimately hold items the index no longer knows. The
     * player then plays them without title/artist rather than rejecting the whole
     * `setMediaItems` call.
     */
    private val sessionCallback = object : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            // One lookup per item, counted while resolving rather than re-probing
            // `mediaMetadata` afterwards: a MediaItem always carries a (possibly
            // empty) metadata object, so null-checking it proves nothing.
            var hits = 0
            val resolved = mediaItems.map { item ->
                val track = trackIndex?.get(item.mediaId)
                if (track == null) {
                    item
                } else {
                    hits++
                    item.withMetadata(track)
                }
            }
            // The counter is the only evidence that the index lookup works. A miss is
            // otherwise invisible: the items still play, they just show no title,
            // artist or artwork in the notification and lock screen. `resolved=0/N` is
            // the signature of a broken same-process assumption (see PlaybackTrackIndex).
            Log.i(TAG, "queue metadata resolved $hits/${mediaItems.size}")
            return Futures.immediateFuture(resolved)
        }
    }

    /**
     * The shared `mediaId` → [Track] map, read from the application container.
     *
     * Resolved through a safe cast rather than a hard cast: a service created with a
     * plain [android.app.Application] must still start and play, just without queue
     * metadata.
     */
    private val trackIndex: PlaybackTrackIndex?
        get() = (application as? MilanMusicApp)?.container?.trackIndex

    /** Rebuilds one queue item's metadata from the index entry the controller wrote. */
    private fun MediaItem.withMetadata(track: Track): MediaItem = buildUpon()
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.titleText(this@PlaybackService))
                .setArtist(track.artistText(this@PlaybackService))
                .setAlbumTitle(track.albumText(this@PlaybackService))
                .setArtworkUri(track.uri)
                .build(),
        )
        .build()

    override fun onCreate() {
        super.onCreate()

        // Float output keeps 24-bit sources at full precision instead of truncating
        // to 16-bit integers. Devices that cannot take float PCM fall back on their
        // own; this only removes the artificial ceiling.
        //
        // Offload is off (see below), so ExoPlayer always builds a MediaCodec and this
        // ranking runs on every track instead of on a fallback path.
        // See InProcessCodecSelector for why only the software tier gets reordered.
        //
        // Offload is *not* configured here: since Media3 1.11 the renderers factory
        // no longer owns that switch and the old setEnableAudioOffload() is gone — the
        // mode lives in the player's TrackSelectionParameters, left at its default.
        // Decoder fallback is off by default in media3, and with it off
        // MediaCodecRenderer only ever attempts `decoderInfos.get(0)`. Combined with a
        // selector that reorders the platform list, that turns a *preference* into the
        // only candidate: one `configure()` failure on the promoted in-process decoder
        // ends in ExoPlaybackException with no retry, where the stock ordering would
        // have walked on to the next decoder. Turning it on restores that walk.
        val renderersFactory = DefaultRenderersFactory(this)
            .setMediaCodecSelector(InProcessCodecSelector)
            .setEnableDecoderFallback(true)
            .setEnableAudioFloatOutput(true)

        // Sample buffers are pooled inside DefaultAllocator, but the instance that
        // DefaultLoadControl builds for itself uses `trimOnReset = true`, and
        // `reset()` with that flag drives the target size to zero:
        //
        //   DefaultAllocator.reset()      -> if (trimOnReset) setTargetBufferSize(0)
        //   setTargetBufferSize(0)        -> 0 < current, so trim()
        //   trim()                        -> availableAllocationCount = 0
        //
        // So every track change releases the whole pool and the next track allocates
        // it again — 30 s of FLAC at 1411 kbps is ~5.3 MB, i.e. 82 segments of 64 KB,
        // freed and re-created on each skip. Passing `trimOnReset = false` keeps the
        // pool across skips; [releaseBufferPool] returns it once the app is in the
        // background with nothing playing, and the GC takes it with the service.
        //
        // The segment size is not a free parameter: it must stay at the platform
        // default, because trim() works in whole segments and a different size would
        // make the pool's byte accounting drift from what DefaultLoadControl expects.
        val allocator = DefaultAllocator(
            /* trimOnReset = */ false,
            /* individualAllocationSize = */ C.DEFAULT_BUFFER_SEGMENT_SIZE,
        )
        bufferAllocator = allocator

        // Time-based buffering is unchanged; [ByteCappedLoadControl] layers a byte
        // ceiling on top of it so a 30-second window cannot mean 17 MB on a 24-bit
        // file. The floor is shared with the delegate on purpose: the cap is not
        // allowed to shrink the buffer below it.
        val loadControl = ByteCappedLoadControl(
            delegate = DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(
                    /* minBufferMs = */ MIN_BUFFER_MS,
                    /* maxBufferMs = */ MAX_BUFFER_MS,
                    /* bufferForPlaybackMs = */ 120,
                    /* bufferForPlaybackAfterRebufferMs = */ 500,
                )
                .build(),
            allocator = allocator,
            byteCap = ByteCappedLoadControl.DEFAULT_BYTE_CAP,
            minBufferUs = MIN_BUFFER_MS * 1_000L,
        )

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            // Default buffer targets streaming; local files can start almost
            // immediately, so the rebuffer threshold is cut from 2.5 s to 120 ms.
            // This is the only term in first-play latency that is actually ours:
            // reading a local file costs a few ms and decoder init 10-50 ms, while
            // everything below this number is pure waiting before the first frame.
            // It costs no memory, only slack against a slow storage read.
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            // Hold a partial wake lock (WAKE_LOCK is declared) so playback keeps
            // running while the screen is off; without it some OEMs stall the
            // decoder thread once the device dozes.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Warm the head of the next queue item so a skip does not have to wait for
        // its first samples. The unit is microseconds; the default value is
        // Long.MIN_VALUE + 1, a sentinel that disables preloading entirely, so this
        // has to be set explicitly. Five seconds of *compressed* samples is a few
        // hundred KB (FLAC ~0.6 MB) — it is the prep work only, no decoder is built.
        // This is deliberately not PreloadMediaSource: that one keeps a second
        // MediaPeriod plus its own looper per preloaded item and exists for list
        // preview / audition, which this app does not do.
        player.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(5_000_000L))

        // Audio offload is deliberately left at its default, which is
        // AUDIO_OFFLOAD_MODE_DISABLED (mode 0 in the builder's field initialiser).
        // It is the biggest power win a local player can get — decoding moves to the
        // DSP and the CPU only feeds the buffer — but it only pays out when the
        // platform actually accepts the track: media3's support check requires bit0
        // (OFFLOAD_SUPPORTED) of AudioManager.getDirectPlaybackSupport, and a device
        // that reports only DIRECT_PLAYBACK_BITSTREAM_SUPPORTED (value 4) never hits
        // it. Every format probed on the reference device returned 4, so the mode was
        // enabled in name only: the MediaCodec path ran anyway.
        //
        // What turning it on costs, on the devices where it does hit:
        //   * the sink owns the timeline, so end padding and position reporting follow
        //     the DSP instead of the file — a swallowed first sample or a progress bar
        //     lagging by one buffer, and only on the offload path;
        //   * offload bypasses ExoPlayer's audio post-processing, so any future
        //     equalizer, crossfade or loudness gain would have to switch the mode off
        //     while it is active;
        //   * an album can mix both paths (WAV and ALAC have no platform encoding and
        //     can never offload), which makes the two defects above hard to reproduce.
        //
        // Re-enabling means restoring the mode *and* a hard gapless requirement
        // (setIsGaplessSupportRequired), otherwise the hardware flushes a silence
        // frame between tracks of a continuous album.

        // Which decoder actually ran is not observable from the UI, and with offload
        // off there is no second path to confuse it with — this single callback is the
        // decisive evidence that the ranking above took effect on a given device. It
        // fires at most once per state change, never per frame.
        player.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onAudioDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    Log.i(TAG, "audio decoder=$decoderName initMs=$initializationDurationMs")
                }
            },
        )

        // Playback errors used to be silent: ExoPlayer stopped and the UI just
        // looked "paused". Log the error code and position so recurring stops at
        // a fixed timestamp can be attributed to a damaged spot in the file.
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(
                    TAG,
                    "playback error at pos=${player.currentPosition}ms " +
                        "media=${player.currentMediaItem?.mediaId} " +
                        "code=${error.errorCodeName} cause=${error.cause}",
                )
            }
        })

        // Media3's default artwork loader reads the URI over a DataSource, which
        // cannot serve embedded cover art; the session therefore needs a loader that
        // goes through ContentResolver. Without it the notification, lock screen and
        // Bluetooth metadata show no artwork at all.
        val loader = ArtworkBitmapLoader(this)
        artworkLoader = loader

        mediaSession = MediaSession.Builder(this, player)
            .setBitmapLoader(loader)
            .setCallback(sessionCallback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    /**
     * Forwards trim signals to the session's artwork cache.
     *
     * The service owns that cache, so the [android.app.Application] hook cannot reach
     * it; services receive [android.content.ComponentCallbacks2] callbacks directly
     * and this is the only way the notification bitmaps ever shrink while a paused
     * queue keeps the service alive.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        artworkLoader?.onTrimMemory(level)
        // The buffer pool is only worth holding while playback is alive. Once the app
        // is backgrounded *and* nothing is playing, keeping 1-5 MB of 64 KB segments
        // reserves memory for a restart that may never come — and a restart is a
        // deliberate user action that can afford the reallocation.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && !isPlaybackActive()) {
            releaseBufferPool()
        }
    }

    /**
     * True while the queue is loaded and playback is actually going or about to start.
     *
     * `playWhenReady` alone is not enough: it stays `true` after the queue runs out
     * (`STATE_ENDED`), and it also stays `true` when the user pauses and the player
     * falls back to `STATE_READY`. The first case is the common one — an album ends
     * with the app backgrounded — and it is exactly when the pool is worth
     * releasing: nothing is decoding, and a restart re-selects tracks and restores the
     * target size anyway.
     */
    private fun isPlaybackActive(): Boolean {
        val player = mediaSession?.player ?: return false
        val state = player.playbackState
        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) return false
        return player.playWhenReady || player.isPlaying
    }

    /**
     * Returns every free segment to the heap.
     *
     * `setTargetBufferSize(0)` both lowers the target and triggers `trim()`, which
     * drops the pool to zero segments. [DefaultLoadControl] sets the real target
     * again on the next `onTracksSelected`, so this is a reversible release rather
     * than a permanent shutdown.
     */
    private fun releaseBufferPool() {
        bufferAllocator?.setTargetBufferSize(0)
    }

    override fun onDestroy() {
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        releaseBufferPool()
        bufferAllocator = null
        artworkLoader?.release()
        artworkLoader = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PlaybackService"

        /**
         * Buffer floor, in both dimensions: the time form feeds the delegate, the
         * microsecond form is what keeps the byte cap from starving the buffer.
         */
        const val MIN_BUFFER_MS = 15_000

        /**
         * Upper bound of the time-based window. The byte ceiling in
         * [ByteCappedLoadControl] usually closes first on high-bitrate files; this
         * stays as the bound for low-bitrate ones, where 30 s costs almost nothing.
         */
        const val MAX_BUFFER_MS = 30_000
    }
}
