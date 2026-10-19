package com.milan.music.play

import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
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
