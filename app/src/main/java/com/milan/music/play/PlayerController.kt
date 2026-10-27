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
 * Whether leaving the UI may detach from the session.
 *
 * A queued action is not visible in [PlayerUiState.queueSize] until the controller
 * binds. Releasing during that window clears the action and cancels the connection,
 * which permanently loses the startup request.
 */
internal fun shouldReleaseOnUiStopped(queueSize: Int, pendingActionCount: Int): Boolean =
    queueSize == 0 && pendingActionCount == 0

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

    /**
     * Requests that arrived before the controller finished connecting, in arrival
     * order.
     *
     * A single slot used to hold them, which silently dropped the earlier request:
     * a widget tap that starts a queue and a row tap landing inside the same connect
     * window left only the second one, with nothing in the log. The queue replays
     * them in order instead — a `playQueue` followed by a `togglePlayPause` ends up
     * exactly where the user left it.
     */
    private val pendingActions = ArrayDeque<() -> Unit>()
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
                // Same released-controller hazard as everywhere else, except that this
                // collector must never die: it is the only thing that starts the ticker
                // again after the player sheet is reopened. Swallowing the read is safe
                // because `onDisconnected` does the actual teardown.
                val playing = runCatching { controller?.isPlaying == true }.getOrDefault(false)
                if (progressObserved && playing) {
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

    /**
     * The session went away: the service was killed, unbound, or stopped by the user.
     *
     * media3 releases the controller itself in that case, so [controller] must stop
     * pointing at it. Every call on a released controller throws `IllegalStateException`
     * from `checkNotReleased()` — on the main thread, with no try/catch above it, so a
     * low-memory kill of the service followed by a tap on the mini player was enough to
     * take the process down.
     *
     * Nothing is reconnected here: the queue lives in the service, so a fresh session
     * starts empty. The next user action binds again through the `pendingActions` path
     * in [togglePlayPause] / [play], and the cleared state is the honest description of
     * "nothing is playing".
     */
    private val sessionListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            Log.i(TAG, "session disconnected; detaching from playback service")
            detachSession()
        }
    }

    fun connect() {
        if (controller != null || connection != null) return

        val epoch = ++connectEpoch
        val token = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )
        val future = MediaController.Builder(context, token)
            .setListener(sessionListener)
            .buildAsync()
        connection = future

        future.addListener(
            {
                // release() ran after this connect started: the future has been
                // cancelled or its controller released already. Do not install it.
                if (epoch != connectEpoch) {
                    runCatching { future.get().release() }
                    return@addListener
                }
                // Explicit try/catch instead of runCatching: this body makes a dozen
                // calls into Media3, and collapsing them into one Throwable says
                // nothing about which one failed. The catch stays broad on purpose —
                // anything escaping a main-executor listener takes the process down.
                try {
                    future.get().also { mediaController ->
                        controller = mediaController
                        // The future is complete; keeping it would make
                        // release() release the same instance twice.
                        connection = null
                        mediaController.addListener(listener)
                        refresh()
                        if (mediaController.isPlaying) startProgressLoop()
                        // Replay in request order. Each action sees a non-null
                        // controller, so none of them can enqueue again. One failure
                        // must not swallow the actions behind it.
                        while (pendingActions.isNotEmpty()) {
                            runCatching { pendingActions.removeFirst().invoke() }
                                .onFailure { Log.w(TAG, "queued player action failed", it) }
                        }
                    }
                } catch (e: Exception) {
                    // Includes the case where release() cancelled the future while it
                    // was still connecting; there is no controller to install then.
                    Log.w(TAG, "session connect failed", e)
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
        val mediaController = controller
        val future = connection
        if (mediaController == null && future == null) return
        Log.d(TAG, "release: detaching from playback service (queue=${_state.value.queueSize})")
        detachSession()
        // [trackIndex] is deliberately not cleared: with a queue still loaded in the
        // session, the notification keeps resolving metadata for it. It is replaced
        // wholesale by the next playQueue().
        //
        // Both calls below can throw when the session died first: media3 has released
        // the controller already and the `onDisconnected` callback may still be in
        // flight. Tearing down is best effort, never a reason to take the process down.
        mediaController?.let { detached ->
            runCatching { detached.removeListener(listener) }
                .onFailure { Log.w(TAG, "removeListener on a lost session", it) }
            runCatching { detached.release() }
                .onFailure { Log.w(TAG, "release on a lost session", it) }
        }
        future?.let { pending ->
            runCatching { MediaController.releaseFuture(pending) }
                .onFailure { Log.w(TAG, "releaseFuture on a lost session", it) }
        }
    }

    /**
     * Drops every reference to the session and republishes an empty state.
     *
     * Shared by [release] (we chose to detach) and [sessionListener] (the session died
     * under us) because `controller == null` is the only state [connect] will bind from,
     * and both paths have to reach it identically.
     *
     * Pending actions are dropped too: they were addressed to the session that no longer
     * exists, and replaying them into a fresh, empty one would carry out commands the
     * user issued against a different queue. [lastWidgetSnapshot] is cleared so a later
     * connect still pushes a widget update for the same track.
     */
    private fun detachSession() {
        // Invalidate any in-flight connect: its callback must not install a controller
        // after this point (see [connectEpoch]).
        connectEpoch++
        progressJob?.cancel()
        progressJob = null
        pendingActions.clear()
        controller = null
        connection = null
        lastWidgetSnapshot = null
        _state.value = PlayerUiState()
        _progress.value = PlaybackProgress()
    }

    /**
     * Runs [block] against the live controller, treating a released one as a lost session.
     *
     * [sessionListener] arrives as an ordinary main-thread message, so a tap that lands
     * between the session dying and that message being dispatched still sees a non-null
     * field and hits `checkNotReleased()`. Catching here keeps that window from reaching
     * the uncaught exception handler; anything else that raises `IllegalStateException`
     * (a call made off the main thread) is logged at error level instead of swallowed.
     *
     * @return whether the call ran, so callers can skip publishing a result that no
     * session produced.
     */
    private fun sessionCall(action: String, block: (MediaController) -> Unit): Boolean {
        val mediaController = controller ?: return false
        return try {
            block(mediaController)
            true
        } catch (e: IllegalStateException) {
            Log.e(TAG, "$action failed on a released controller; detaching", e)
            detachSession()
            false
        }
    }

    /**
     * Called when the last UI leaves the foreground.
     *
     * A [MediaController] is a *bound client*, and Android keeps a service alive as
     * long as any client stays bound — media3's own `stopSelf()` (in
     * `MediaSessionService.onTaskRemoved`) cannot destroy it on its own.
     *
     * With a queue loaded the binding stays on purpose: the session state lives in
     * the service, so releasing the client would also throw away a paused queue and
     * the notification's resume path.
     *
     * That is the normal case, not the exception. [com.milan.music.ui.library.HomeScreen]
     * starts playback as soon as the library loads, so `queueSize` is non-zero on
     * essentially every launch that has audio permission, and the service lives for
     * as long as the process does. That is the price of "opening the app starts the
     * music", not an oversight — and it is why an earlier comment here, which
     * described a release path for the ordinary launch, was wrong: the ordinary
     * launch never reaches this branch.
     *
     * The branch below is still correct for what remains: a process that goes to the
     * background without ever playing (no permission, empty library). It is reached
     * with `controller == null`, where [release] is already a no-op, so the condition
     * guards the invariant rather than producing an effect. It stays because the
     * invariant is what makes [release] safe to call from anywhere later.
     *
     * Pending actions are the exception: during the connect window the queue is not
     * visible in [PlayerUiState] yet, so releasing on `queueSize == 0` would cancel
     * the startup command and the connection that was about to execute it.
     */
    fun onUiStopped() {
        if (shouldReleaseOnUiStopped(_state.value.queueSize, pendingActions.size)) release()
    }

    /**
     * Replaces the session queue and starts (or prepares) it.
     *
     * The defaults preserve the original "open the app and start playing" behaviour.
     * [startPositionMs], [shuffle], [repeatMode] and [autoPlay] exist for the cold-start
     * restore: the last queue and timestamp come back unchanged, and a user who had
     * paused before the process died does not get sound on launch.
     *
     * [autoPlay] = false still calls `prepare()` so the notification, the mini player
     * and the artwork are live; it only leaves `playWhenReady` false.
     */
    fun playQueue(
        tracks: List<Track>,
        startIndex: Int,
        startPositionMs: Long = 0L,
        shuffle: Boolean = true,
        repeatMode: Int = Player.REPEAT_MODE_ALL,
        autoPlay: Boolean = true,
    ) {
        if (tracks.isEmpty()) return
        if (controller == null) {
            pendingActions.addLast {
                playQueue(tracks, startIndex, startPositionMs, shuffle, repeatMode, autoPlay)
            }
            connect()
            return
        }
        val index = startIndex.coerceIn(0, tracks.lastIndex)
        // Written before the queue reaches the session: onAddMediaItems resolves
        // against this map, and setMediaItems is asynchronous, so a later write can
        // race an earlier resolution.
        trackIndex.replace(tracks)
        val items = tracks.map { it.toMediaItem() }
        sessionCall("playQueue") { mediaController ->
            mediaController.setMediaItems(items, index, startPositionMs.coerceAtLeast(0L))
            // Playback always starts in shuffle mode; the user can still turn it off
            // from the player screen.
            mediaController.shuffleModeEnabled = shuffle
            // The mode button is hidden from the UI, so looping is pinned on: without it
            // playback would simply stop at the end of the shuffled queue.
            mediaController.repeatMode = repeatMode
            mediaController.prepare()
            if (autoPlay) {
                mediaController.play()
            } else {
                mediaController.pause()
            }
        }
        refresh()
    }

    fun togglePlayPause() {
        if (controller == null) {
            // Triggered from the widget before the session is bound: queue the action
            // and let the connection callback replay it.
            pendingActions.addLast { togglePlayPause() }
            connect()
            return
        }
        sessionCall("togglePlayPause") { mediaController ->
            if (mediaController.isPlaying) {
                mediaController.pause()
            } else {
                if (mediaController.playbackState == Player.STATE_IDLE) {
                    mediaController.prepare()
                }
                mediaController.play()
            }
        }
        refresh()
    }

    /**
     * Starts transport without toggling an already-playing queue.
     *
     * Used for the widget's explicit play request after it has opened the app. A
     * toggle would pause if the snapshot restore had already started playback.
     */
    fun play() {
        if (controller == null) {
            pendingActions.addLast { play() }
            connect()
            return
        }
        sessionCall("play") { mediaController ->
            if (mediaController.playbackState == Player.STATE_IDLE) {
                mediaController.prepare()
            }
            mediaController.play()
        }
        refresh()
    }

    fun next() {
        if (controller == null) return
        sessionCall("next") { mediaController ->
            when {
                mediaController.hasNextMediaItem() -> mediaController.seekToNextMediaItem()
                mediaController.mediaItemCount > 0 -> mediaController.seekTo(0, 0L)
                else -> return@sessionCall
            }
            resumeIfPaused(mediaController)
        }
    }

    fun previous() {
        if (controller == null) return
        sessionCall("previous") { mediaController ->
            if (mediaController.currentPosition > RESUME_POSITION_MS) {
                // 只是回到当前曲的开头，不是换曲意图，播放状态保持原样。
                mediaController.seekTo(0L)
            } else if (mediaController.hasPreviousMediaItem()) {
                mediaController.seekToPreviousMediaItem()
                resumeIfPaused(mediaController)
            } else {
                mediaController.seekTo(0L)
            }
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
        if (controller == null) return
        sessionCall("skipToPrevious") { mediaController ->
            if (mediaController.hasPreviousMediaItem()) {
                mediaController.seekToPreviousMediaItem()
            } else {
                mediaController.seekTo(0L)
            }
            resumeIfPaused(mediaController)
        }
    }

    /**
     * 「换曲即播」：暂停时点下一首，会话只会把队列指针移过去，`playWhenReady` 仍是
     * false，表现就是"点了没反应"。
     *
     * 只在真正换曲的分支调用；[previous] 的 3 秒重播、以及队列到头的 `seekTo(0)`
     * 都不动播放状态，因为那不是换曲意图。判断用 `playWhenReady` 而不是
     * `isPlaying`：缓冲期间 `isPlaying` 也会是 false，那样会重复发 `play()`。
     */
    private fun resumeIfPaused(mediaController: MediaController) {
        if (mediaController.playWhenReady) return
        if (mediaController.playbackState == Player.STATE_IDLE) {
            mediaController.prepare()
        }
        mediaController.play()
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        // Publishing the position is only meaningful if the session took it: after a
        // lost session [detachSession] already reset the flow to empty, and writing the
        // seek target there would describe a playback that no longer exists.
        if (sessionCall("seekTo") { it.seekTo(clamped) }) {
            _progress.update { it.copy(positionMs = clamped) }
        }
    }

    private fun refresh() {
        sessionCall("refresh") { mediaController -> publishState(mediaController) }
    }

    /** Fills [state] and [progress] from a controller known to be live. */
    private fun publishState(mediaController: MediaController) {
        val mediaItem = mediaController.currentMediaItem
        // Shuffle reorders the playback sequence, so `currentMediaItemIndex` is a
        // position in the shuffled order — it is **not** an index into the list that
        // was handed to `setMediaItems`. The only correct lookup is by mediaId.
        //
        // Playback can start without this controller: a library browser sends its own
        // items, and the system can resume a queue after process death. In both cases
        // the index has no entry, so fall back to the item's own metadata and seed the
        // index; otherwise the player screen would show no title, artwork or
        // neighbours while the session is audibly playing.
        fun resolve(item: MediaItem): Track? =
            trackIndex.get(item.mediaId)
                ?: item.toFallbackTrack()?.also(trackIndex::put)

        // No index fallback. There used to be `?: queue[currentMediaItemIndex]`, which
        // is wrong twice over: under shuffle that index addresses a different track,
        // and it also fires when there is no current item at all (between
        // `setMediaItems` and the session materialising it), where the index means
        // nothing and the queue's first entry is not necessarily the one starting.
        // Both cases showed a *different* song than the one playing. When nothing
        // resolves, the update below keeps the previous track — stale, but never wrong.
        val currentTrack = mediaItem?.let { resolve(it) }

        // The player's own indices, not queue[current ± 1]: shuffle reorders the
        // timeline, and REPEAT_MODE_ALL is pinned on, so the neighbours wrap around
        // exactly the way the transport buttons do. A one-track queue reports the
        // current item as its own neighbour; drop it so the cover swipe cannot
        // offer a transition that goes nowhere.
        fun neighborAt(index: Int): Track? {
            if (index !in 0 until mediaController.mediaItemCount) return null
            return resolve(mediaController.getMediaItemAt(index))
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
            )
        }

        _progress.update {
            PlaybackProgress(
                positionMs = mediaController.currentPosition.coerceAtLeast(0L),
                // Never carry the previous track's duration across a transition. While
                // the new item is still preparing `duration` is unset; the player
                // screen's fallback (the track's own metadata) is then the honest
                // value, whereas an old duration makes the bar show the wrong length.
                durationMs = mediaController.duration.coerceAtLeast(0L),
            )
        }

        maybeRefreshWidget(mediaController)
    }

    /**
     * Media3 fires `onEvents` for many internal transitions, several of them within the
     * same frame. The widget renders only cover + title + play state, so a broadcast
     * is sent only when the current track or the playing flag actually changes —
     * seeks, buffer transitions and metadata refreshes no longer reach the launcher.
     */
    private fun maybeRefreshWidget(mediaController: MediaController) {
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
                // The session can die between two ticks; `onDisconnected` does the
                // teardown, this loop only has to stop instead of throwing out of a
                // coroutine the app scope would have to catch.
                val next = try {
                    if (!mediaController.isPlaying) break
                    PlaybackProgress(
                        positionMs = mediaController.currentPosition.coerceAtLeast(0L),
                        durationMs = mediaController.duration.coerceAtLeast(0L),
                    )
                } catch (e: IllegalStateException) {
                    Log.d(TAG, "progress loop stopped: session gone", e)
                    break
                }
                _progress.value = next
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun stopProgressLoop() {
        progressJob?.cancel()
        progressJob = null
        // The final position after a pause or a stop still has to reach the UI;
        // `refresh()` in the same event pass covers it when the player reports one.
        sessionCall("stopProgressLoop") { mediaController ->
            _progress.update { current ->
                PlaybackProgress(
                    positionMs = mediaController.currentPosition.coerceAtLeast(0L),
                    durationMs = mediaController.duration.takeIf { it > 0L } ?: current.durationMs,
                )
            }
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
