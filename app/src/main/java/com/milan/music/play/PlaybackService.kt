package com.milan.music.play

import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Hosts the ExoPlayer instance in a foreground service so playback survives
 * when the activity is backgrounded, and exposes a MediaSession for the system
 * notification, lock screen and Bluetooth controls.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var artworkLoader: ArtworkBitmapLoader? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        // Float output keeps 24-bit sources at full precision instead of truncating
        // to 16-bit integers. Devices that cannot take float PCM fall back on their
        // own; this only removes the artificial ceiling.
        //
        // Offload is *not* configured here: since Media3 1.11 the renderers factory
        // no longer owns that switch, it is driven entirely by the player's
        // TrackSelectionParameters below (the old setEnableAudioOffload() is gone).
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableAudioFloatOutput(true)

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            // Default buffer targets streaming; local files can start almost
            // immediately, so the rebuffer threshold is cut from 2.5 s to 250 ms.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 15_000,
                        /* maxBufferMs = */ 30_000,
                        /* bufferForPlaybackMs = */ 250,
                        /* bufferForPlaybackAfterRebufferMs = */ 500,
                    )
                    .build(),
            )
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

        // Audio offload moves decoding and rendering to the DSP, so the CPU only
        // has to feed the buffer instead of decoding every frame — the largest power
        // win available to a local player. Note the ceiling: the builder above sets
        // WAKE_MODE_LOCAL, which still holds a partial wake lock, so the CPU is not
        // allowed to deep-sleep; the saving comes from dropping decode work, not
        // from idling the core. Two further constraints:
        //   * gapless has to be a hard requirement, otherwise the hardware flushes
        //     its buffer with a silence frame between tracks of a continuous album;
        //   * offload bypasses ExoPlayer's audio post-processing, so any future
        //     equalizer or speed control must switch the mode to DISABLED while it
        //     is active (volume changes are unaffected: they go through AudioTrack).
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(
                AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                    .setIsGaplessSupportRequired(true)
                    .build(),
            )
            .build()

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
    }

    override fun onDestroy() {
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        artworkLoader?.release()
        artworkLoader = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PlaybackService"
    }
}
