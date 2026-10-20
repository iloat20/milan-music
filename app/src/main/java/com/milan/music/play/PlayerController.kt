package com.milan.music.play

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.milan.music.data.model.Track
import com.milan.music.widget.MusicWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val currentTrack: Track? = null,
    /**
     * Neighbouring tracks in the *playback* order, already resolved through
     * shuffle. Used by the player screen to mount the covers on both sides of
     * the current one; `null` when the queue has no other track.
     */
    val previousTrack: Track? = null,
    val nextTrack: Track? = null,
    val isPlaying: Boolean = false,
    val queueSize: Int = 0,
) {
    val hasQueue: Boolean get() = queueSize > 0
}

/**
 * Millisecond-level playback position, kept in its own flow.
 *
 * The progress loop ticks twice a second; folding it into [PlayerUiState] would
 * recompose every screen that observes playback state, including the library
 * list, several times per second for no reason.
 */
data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * Thin Compose-facing wrapper around a [MediaController] bound to [PlaybackService].
 *
 * All controller calls must run on the main thread, therefore the injected scope
 * uses `Dispatchers.Main` and the connection callback is dispatched through the
 * main executor.
 */
class PlayerController(
    private val context: Context,
    private val scope: CoroutineScope,
    /**
     * Shared with [PlaybackService]: the queue is handed to the session as bare
     * `mediaId` + `uri` items, and the session rebuilds their metadata from here.
     * See [PlaybackTrackIndex] for why.
     */
    private val trackIndex: PlaybackTrackIndex,
) {

    private var controller: MediaController? = null
    private var connection: ListenableFuture<MediaController>? = null
    private var progressJob: Job? = null
    private var queue: List<Track> = emptyList()
    private var pendingAction: (() -> Unit)? = null
    private var lastWidgetSnapshot: Pair<Long?, Boolean>? = null

    /**
     * Guards the connect callback against a [release] that happened while the
     * controller was still being built. The completion of the future and the
     * dispatch of its listener are two separate main-thread messages, so a
     * lifecycle callback can be queued between them; without this token the
     * callback would install a controller that [release] already released, and
     * [connect] would then short-circuit on `controller != null` forever.
     */
    private var connectEpoch = 0

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    /**
     * Whether anything is currently collecting [progress].
     *
     * Gating the ticker on `isPlaying` alone was the wrong dimension: the player
     * sheet is the only consumer and it is only composed while open
     * ([com.milan.music.ui.MilanApp]), yet the loop kept waking the main thread twice
     * a second while the screen was off — 7 200 wake-ups an hour at a 500 ms
     * interval, which is enough to keep the CPU out of the deep C-states. The
     * lifecycle-aware collection inside `AppleMusicProgress` makes `subscriptionCount`
     * drop to zero on screen-off and on sheet-close, so both gates now match reality.
     */
    private var progressObserved = false

    init {
        // Main.immediate: the first emission (0 collectors) settles before the
        // constructor returns, so no ticker can start before this gate is known.
        scope.launch {
            _progress.subscriptionCount.collect { count ->
                progressObserved = count > 0
                if (progressObserved && controller?.isPlaying == true) {
                    startProgressLoop()
                } else {
                    stopProgressLoop()
                }
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
            // The progress ticker only has work to do while playback advances. It
            // used to run from connect() to release(), so a paused or backgrounded
            // player still woke the main thread twice a second forever.
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                if (player.isPlaying) startProgressLoop() else stopProgressLoop()
            }
        }
    }

    fun connect() {
        if (controller != null || connection != null) return

        val epoch = ++connectEpoch
        val token = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )
        val future = MediaController.Builder(context, token).buildAsync()
        connection = future

        future.addListener(
            {
                // release() ran after this connect started: the future has been
                // cancelled or its controller released already. Do not install it.
                if (epoch != connectEpoch) {
                    runCatching { future.get().release() }
                    return@addListener
                }
                // The whole body stays inside runCatching: a throwing getter or
                // listener call would otherwise escape into the main executor.
                runCatching {
                    future.get().also { mediaController ->
                        controller = mediaController
                        // The future is complete; keeping it would make
                        // release() release the same instance twice.
                        connection = null
                        mediaController.addListener(listener)
                        refresh()
                        if (mediaController.isPlaying) startProgressLoop()
                        pendingAction?.invoke()
                        pendingAction = null
                    }
                }.onFailure {
                    connection = null
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /**
     * Detaches from the playback service and clears the playback state.
     *
     * Only call this when nothing has to be preserved: the queue lives in
     * [PlaybackService], so releasing with a queue loaded drops the resume path
     * (see [onUiStopped]). When there is nothing bound the call is a no-op.
     */
    fun release() {
        if (controller == null && connection == null) return
        // Invalidate any in-flight connect: its callback must not install a
        // controller after this point (see [connectEpoch]).
        connectEpoch++
        Log.d(TAG, "release: detaching from playback service (queue=${_state.value.queueSize})")
        progressJob?.cancel()
        progressJob = null
        pendingAction = null
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        connection?.let { MediaController.releaseFuture(it) }
        connection = null
        queue = emptyList()
        // [trackIndex] is deliberately not cleared: with a queue still loaded in the
        // session, the notification keeps resolving metadata for it. It is replaced
        // wholesale by the next playQueue().
        // A later reconnect must be able to push a widget update even when the first
        // snapshot matches the last one before the release.
        lastWidgetSnapshot = null
        _state.value = PlayerUiState()
        _progress.value = PlaybackProgress()
    }

    /**
     * Called when the last UI leaves the foreground.
     *
     * A [MediaController] is a *bound client*, and Android keeps a service alive as
     * long as any client stays bound — media3's own `stopSelf()` (in
     * `MediaSessionService.onTaskRemoved`) cannot destroy it on its own. Without
     * this hook the binding was never dropped: `release()` had no caller at all, so
     * a session created at cold start lived until the process died.
     *
     * Releasing is only safe when there is nothing to lose, i.e. no queue: the user
     * opened the app, browsed the library and left without playing. That drops the
     * binding, the bind-only service is destroyed, and the [androidx.media3.exoplayer.ExoPlayer]
     * it owns goes with it.
     *
     * With a queue loaded the binding stays on purpose: the session state lives in
     * the service, so releasing the client would also throw away a paused queue and
     * the notification's resume path.
     */
    fun onUiStopped() {
        if (_state.value.queueSize == 0) release()
    }

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val mediaController = controller
        if (mediaController == null) {
            pendingAction = { playQueue(tracks, startIndex) }
            connect()
            return
        }
        val index = startIndex.coerceIn(0, tracks.lastIndex)
        queue = tracks
        // Written before the queue reaches the session: onAddMediaItems resolves
        // against this map, and setMediaItems is asynchronous, so a later write can
        // race an earlier resolution.
        trackIndex.replace(tracks)
        val items = tracks.map { it.toMediaItem() }
        mediaController.setMediaItems(items, index, 0L)
        // Playback always starts in shuffle mode; the user can still turn it off
        // from the player screen.
        mediaController.shuffleModeEnabled = true
        // The mode button is hidden from the UI, so looping is pinned on: without it
        // playback would simply stop at the end of the shuffled queue.
        mediaController.repeatMode = Player.REPEAT_MODE_ALL
        mediaController.prepare()
        mediaController.play()
        refresh()
    }

    fun togglePlayPause() {
        val mediaController = controller
        if (mediaController == null) {
            // Triggered from the widget before the session is bound: queue the action
            // and let the connection callback replay it.
            pendingAction = { togglePlayPause() }
            connect()
            return
        }
        if (mediaController.isPlaying) {
            mediaController.pause()
        } else {
            if (mediaController.playbackState == Player.STATE_IDLE) {
                mediaController.prepare()
            }
            mediaController.play()
        }
        refresh()
    }

    fun next() {
        val mediaController = controller ?: return
        if (mediaController.hasNextMediaItem()) {
            mediaController.seekToNextMediaItem()
        } else if (mediaController.mediaItemCount > 0) {
            mediaController.seekTo(0, 0L)
        }
    }

    fun previous() {
        val mediaController = controller ?: return
        if (mediaController.currentPosition > RESUME_POSITION_MS) {
            mediaController.seekTo(0L)
        } else if (mediaController.hasPreviousMediaItem()) {
            mediaController.seekToPreviousMediaItem()
        } else {
            mediaController.seekTo(0L)
        }
    }

    /**
     * Always moves to the previous queue item, ignoring [previous]'s restart window.
     *
     * The cover swipe already revealed the previous cover under the finger; jumping
     * back to 0:00 of the *current* track there would contradict what the gesture
     * showed. The transport button keeps the standard 3-second behaviour.
     */
    fun skipToPrevious() {
        val mediaController = controller ?: return
        if (mediaController.hasPreviousMediaItem()) {
            mediaController.seekToPreviousMediaItem()
        } else {
            mediaController.seekTo(0L)
        }
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        controller?.seekTo(clamped)
        _progress.update { it.copy(positionMs = clamped) }
    }

    private fun refresh() {
        val mediaController = controller ?: return
        val mediaItem = mediaController.currentMediaItem
        val metadata = mediaItem?.mediaMetadata
        // Shuffle reorders the playback sequence, so `currentMediaItemIndex` is a
        // position in the shuffled order — not an index into `queue`. The only
        // correct lookup is by mediaId; the index fallback fires only before any
        // media item has been set.
        val currentTrack = mediaItem?.mediaId
            ?.let(trackIndex::get)
            ?: queue.getOrNull(mediaController.currentMediaItemIndex)

        // The player's own indices, not queue[current ± 1]: shuffle reorders the
        // timeline, and REPEAT_MODE_ALL is pinned on, so the neighbours wrap around
        // exactly the way the transport buttons do. A one-track queue reports the
        // current item as its own neighbour; drop it so the cover swipe cannot
        // offer a transition that goes nowhere.
        fun neighborAt(index: Int): Track? {
            if (index !in 0 until mediaController.mediaItemCount) return null
            return trackIndex.get(mediaController.getMediaItemAt(index).mediaId)
                ?.takeIf { it.id != currentTrack?.id }
        }

        val previousTrack = neighborAt(mediaController.previousMediaItemIndex)
        val nextTrack = neighborAt(mediaController.nextMediaItemIndex)
        Log.d(
            TAG,
            "refresh: mediaId=${mediaItem?.mediaId} " +
                "shuffledIndex=${mediaController.currentMediaItemIndex} " +
                "mappedTrackId=${currentTrack?.id}",
        )

        _state.update { previous ->
            PlayerUiState(
                currentTrack = currentTrack ?: previous.currentTrack,
                previousTrack = previousTrack,
                nextTrack = nextTrack,
                isPlaying = mediaController.isPlaying,
                queueSize = mediaController.mediaItemCount,
            ).let { next ->
                if (next.currentTrack == null && metadata != null) {
                    next.copy(currentTrack = previous.currentTrack)
                } else {
                    next
                }
            }
        }

        _progress.update { previous ->
            PlaybackProgress(
                positionMs = mediaController.currentPosition.coerceAtLeast(0L),
                durationMs = mediaController.duration.takeIf { it > 0L } ?: previous.durationMs,
            )
        }

        maybeRefreshWidget()
    }

    /**
     * Media3 fires `onEvents` for many internal transitions, several of them within the
     * same frame. The widget renders only cover + title + play state, so a broadcast
     * is sent only when the current track or the playing flag actually changes —
     * seeks, buffer transitions and metadata refreshes no longer reach the launcher.
     */
    private fun maybeRefreshWidget() {
        val mediaController = controller ?: return
        val snapshot = mediaController.currentMediaItem?.mediaId?.toLongOrNull() to
            mediaController.isPlaying
        if (snapshot == lastWidgetSnapshot) return
        lastWidgetSnapshot = snapshot
        runCatching { MusicWidgetProvider.refresh(context) }
    }

    /**
     * Ticks the position while playback advances. Started on the transition into
     * playing and cancelled on pause, so an idle player costs nothing.
     */
    private fun startProgressLoop() {
        // Both gates have to hold: playback advancing *and* somebody reading the
        // value. See [progressObserved].
        if (!progressObserved) return
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                val mediaController = controller ?: break
                if (!mediaController.isPlaying) break
                _progress.update { current ->
                    PlaybackProgress(
                        positionMs = mediaController.currentPosition.coerceAtLeast(0L),
                        durationMs = mediaController.duration.takeIf { it > 0L }
                            ?: current.durationMs,
                    )
                }
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun stopProgressLoop() {
        progressJob?.cancel()
        progressJob = null
        // The final position after a pause or a stop still has to reach the UI;
        // `refresh()` in the same event pass covers it when the player reports one.
        val mediaController = controller ?: return
        _progress.update { current ->
            PlaybackProgress(
                positionMs = mediaController.currentPosition.coerceAtLeast(0L),
                durationMs = mediaController.duration.takeIf { it > 0L } ?: current.durationMs,
            )
        }
    }

    /**
     * A queue entry carries identity and location only — no [MediaMetadata].
     *
     * The metadata (title, artist, album, artwork URI) is rebuilt by
     * [PlaybackService]'s `MediaSession.Callback.onAddMediaItems` from
     * [PlaybackTrackIndex]. Attaching it here instead meant every tap on a row built
     * one `MediaMetadata` per track in the library — three strings and a `Uri` each —
     * for every item except the handful the session actually displays. Those objects
     * were then written into a Parcel and read back, so the cost was paid twice, on
     * the main thread, before the first sample of the tapped track was requested.
     *
     * The artwork URI the session publishes is the audio item itself, not the retired
     * `content://media/external/audio/albumart/<id>` provider (deprecated in API 29
     * and unreliable above it). [ArtworkBitmapLoader] resolves it through
     * `ContentResolver.loadThumbnail`, which returns the embedded picture — the same
     * path the in-app artwork loader already uses.
     */
    private fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .build()

    companion object {
        private const val TAG = "PlayerController"
        private const val RESUME_POSITION_MS = 3_000L
        private const val PROGRESS_INTERVAL_MS = 500L
    }
}
