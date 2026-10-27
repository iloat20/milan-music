package com.milan.music.play

import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.milan.music.MainActivity
import com.milan.music.MilanMusicApp
import com.milan.music.R
import com.milan.music.data.model.Track
import com.milan.music.di.AppContainer
import com.milan.music.diag.AudioPipeline
import com.milan.music.diag.classifyPlaybackFailure
import com.milan.music.diag.stuckDetailOf
import com.milan.music.ui.text.albumArtistText
import com.milan.music.ui.text.albumText
import com.milan.music.ui.text.artistText
import com.milan.music.ui.text.titleText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var artworkLoader: ArtworkBitmapLoader? = null
    private var libraryTree: LibraryTree? = null

    /**
     * Owns the snapshot ticker and the asynchronous library callbacks.
     *
     * Main.immediate because every `Player` call the snapshot code makes has to run on
     * the application looper; the DataStore writes inside [PlaybackSnapshotStore] switch
     * to IO themselves.
     *
     * The handler is a last line of defence. Playback must not be killed because a tag
     * or a cache write failed on a background coroutine; individual writes that can be
     * recovered already catch their own failures.
     */
    private val serviceScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, throwable ->
                Log.e(TAG, "service coroutine failed", throwable)
            },
    )
    private var positionJob: Job? = null

    private val container: AppContainer?
        get() = (application as? MilanMusicApp)?.container

    private val snapshotStore: PlaybackSnapshotStore?
        get() = container?.playbackSnapshot

    /**
     * The sample-buffer pool handed to [DefaultLoadControl].
     *
     * Held here so memory-pressure callbacks can return its segments before the
     * service dies; see [releaseBufferPool].
     */
    private var bufferAllocator: DefaultAllocator? = null

    /**
     * 换曲/起播到真正出声的采样器。
     *
     * 起点由 [TrackStartProbe] 决定，而不是每次转场无条件打点；暂停快照的准备
     * 不算启动，见该类的 KDoc。
     */
    private val trackStartProbe = TrackStartProbe()

    /**
     * Decides whether a snapshot write has to carry the id list.
     *
     * Lives next to [trackStartProbe] because both exist for the same reason: the
     * player events are frequent enough that the naive implementation is measurable.
     * See [QueueSnapshotPolicy].
     */
    private val queueSnapshotPolicy = QueueSnapshotPolicy()

    /**
     * Resolves the playing track's ReplayGain, lazily and off the main thread.
     *
     * Created in `onCreate` rather than per transition so the cache survives a skip
     * back to a track that was already measured.
     */
    private var replayGainReader: ReplayGainReader? = null

    /**
     * Identifies the transition that owns the gain ramp.
     *
     * Bumped on every [Player.Listener.onMediaItemTransition], not when the replacement
     * gain is known: a ramp already in flight must stop as soon as the track changes,
     * including when the new track cannot be resolved at all and nothing else would
     * invalidate it. A late ramp that kept writing would leave the new track at the old
     * track's level until the next skip.
     */
    private var replayGainGeneration = 0

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
    private val sessionCallback = object : MediaLibrarySession.Callback {
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
                // The app's own queue is already in the index. Items that came from a
                // library browser are not: the tree built them, and the controller has
                // never seen a playQueue call. Seeding the index here is what lets the
                // in-app player screen show the same title/artwork/neighbours.
                val track = trackIndex?.get(item.mediaId)
                    ?: libraryTree?.cachedTrack(item.mediaId)
                if (track == null) {
                    item
                } else {
                    hits++
                    trackIndex?.put(track)
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

        /**
         * Restores the persisted queue when the platform asks for it.
         *
         * Two callers, two shapes. `isForPlayback == false` builds the "recent media"
         * entry at boot: one item, complete metadata, complete as fast as possible.
         * `isForPlayback == true` is the actual resume (media key, Bluetooth, system
         * UI): a window around the current item, because the returned items bypass
         * `onAddMediaItems` and must carry their own metadata, and because building
         * 5 000 metadata objects at once is the cost `PlaybackTrackIndex` exists to
         * avoid. The window truncates the queue by design; the alternative — all ids
         * with metadata only around the current item — is a P1 experiment.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                val result = try {
                    buildResumption(mediaSession, isForPlayback)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "playback resumption failed", e)
                    EMPTY_RESUMPTION
                }
                future.set(result)
            }
            return future
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val tree = libraryTree
                ?: return Futures.immediateFuture(
                    LibraryResult.ofError(SessionError.ERROR_INVALID_STATE),
                )
            return Futures.immediateFuture(LibraryResult.ofItem(tree.rootItem(), params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            val tree = libraryTree
            if (tree == null) {
                future.set(LibraryResult.ofError(SessionError.ERROR_INVALID_STATE))
                return future
            }
            serviceScope.launch {
                try {
                    val items = tree.children(parentId, page, pageSize)
                    future.set(
                        if (items == null) {
                            LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                        } else {
                            LibraryResult.ofItemList(items, params)
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "library children failed for $parentId", e)
                    future.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
                }
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            val tree = libraryTree
            if (tree == null) {
                future.set(LibraryResult.ofError(SessionError.ERROR_INVALID_STATE))
                return future
            }
            serviceScope.launch {
                try {
                    val item = tree.item(mediaId)
                    future.set(
                        if (item == null) {
                            LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                        } else {
                            LibraryResult.ofItem(item, null)
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "library item failed for $mediaId", e)
                    future.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
                }
            }
            return future
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
        get() = container?.trackIndex

    /** Rebuilds one queue item's metadata from the index entry the controller wrote. */
    private fun MediaItem.withMetadata(track: Track): MediaItem = buildUpon()
        .setMediaMetadata(track.toMediaMetadata())
        .build()

    private fun Track.toMediaMetadata(): MediaMetadata = MediaMetadata.Builder()
        .setTitle(titleText(this@PlaybackService))
        .setArtist(artistText(this@PlaybackService))
        .setAlbumTitle(albumText(this@PlaybackService))
        // Album artist is what Android Auto groups by; artist alone merges compilations
        // into whichever performer the file names first. Blank falls back to `artist`.
        .setAlbumArtist(albumArtistText(this@PlaybackService))
        .setArtworkUri(uri)
        .setDurationMs(duration)
        // Zero is MediaStore's "column missing", not a real value. Passing it through
        // would put "track 0" and "year 0" into the system UI and Auto's sort keys.
        .setTrackNumber(trackNumber.takeIf { it > 0 })
        .setReleaseYear(year.takeIf { it > 0 })
        // These items are queue entries, not containers. Stating it explicitly
        // matters when a library browser is the one asking: its items carried
        // isPlayable=true, and replacing the whole metadata object would otherwise
        // reset the flag to its false default.
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .build()

    /**
     * A full item for [onPlaybackResumption], which bypasses `onAddMediaItems`.
     *
     * The queue id and the URI both have to be present: the id is what the UI maps back
     * to a [Track], the URI is what the player actually opens.
     */
    private fun Track.toMediaItemWithMetadata(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(toMediaMetadata())
        .build()

    /**
     * The metadata fallback for a current item whose [Track] is no longer in the index
     * (the platform can hand the session items after process death). The URI comes from
     * `localConfiguration`, so a snapshot can still be written for an item that was
     * queued by another client.
     */
    private fun MediaItem.toSnapshotTrack(): SnapshotTrack? {
        trackIndex?.get(mediaId)?.let { return it.toSnapshotTrack() }
        return toFallbackTrack()?.toSnapshotTrack()
    }

    /**
     * Builds the resumption result, or [EMPTY_RESUMPTION] when there is nothing to
     * resume.
     *
     * Empty is the right "no" value here: Media3 treats an empty list as
     * `RESULT_ERROR_NOT_SUPPORTED` on the recent-item path and as a no-op on the
     * playback path. A failed future would log an error on every cold media button
     * press for a device that has never played anything.
     */
    private suspend fun buildResumption(
        mediaSession: MediaSession,
        isForPlayback: Boolean,
    ): MediaSession.MediaItemsWithStartPosition {
        val store = snapshotStore ?: return EMPTY_RESUMPTION
        val snapshot = store.load() ?: return EMPTY_RESUMPTION
        val currentId = snapshot.trackIds.getOrNull(snapshot.currentIndex)
            ?: return EMPTY_RESUMPTION

        if (!isForPlayback) {
            // The boot-time "recent media" entry only reads the item at startIndex.
            // Prefer the metadata already in the snapshot: the system asks for this
            // during boot, and decoding the whole library cache just to read one title
            // is the exact cost this store exists to avoid. The library is a fallback
            // for a snapshot written before `current` was populated.
            val currentTrack = snapshot.current?.toTrack()
                ?: container?.libraryCache?.load()?.firstOrNull { it.id == currentId }
            val item = currentTrack?.toMediaItemWithMetadata()
                ?: MediaItem.Builder().setMediaId(currentId.toString()).build()
            // The process may have just started: seed the controller's index so the
            // player screen can resolve the item it is about to show.
            currentTrack?.let { trackIndex?.put(it) }
            Log.i(
                TAG,
                "resumption recent id=$currentId positionMs=${snapshot.positionMs}",
            )
            return MediaSession.MediaItemsWithStartPosition(
                listOf(item),
                /* startIndex = */ 0,
                clampedPosition(snapshot.positionMs, currentTrack),
            )
        }

        // Current item ± 100. The window is the deliberate P0 trade-off: 201 metadata
        // objects stay well under the Binder limit. The session now owns the shortened
        // queue, so later snapshots persist that length too; restoring the full queue
        // needs the P1 "all ids + metadata window" shape, not a hidden copy of the old
        // ids. Items that no longer exist in the library are skipped, and startIndex is
        // recomputed rather than carried over from the snapshot.
        val library = container?.libraryCache?.load().orEmpty()
        val byId = library.associateBy { it.id }
        // A loaded library that no longer contains the current track means the file was
        // deleted or moved. Resuming it would start the service only to fail on open;
        // the in-app restore path rejects the same case. An empty cache is different:
        // the process may have been started before the first scan, so the snapshot is
        // the only source available and is allowed to stand in.
        if (library.isNotEmpty() && currentId !in byId) return EMPTY_RESUMPTION
        val currentTrack = byId[currentId] ?: snapshot.current?.toTrack()
        val from = (snapshot.currentIndex - RESUMING_WINDOW).coerceAtLeast(0)
        val to = (snapshot.currentIndex + RESUMING_WINDOW)
            .coerceAtMost(snapshot.trackIds.lastIndex)
        val items = ArrayList<MediaItem>(to - from + 1)
        var startIndex = -1
        for (index in from..to) {
            val id = snapshot.trackIds[index]
            val track = byId[id] ?: if (id == currentId) currentTrack else null
            if (track == null) continue
            // Same reason as the single-item branch: after process death this index
            // is empty, and the resumed queue bypasses onAddMediaItems entirely.
            trackIndex?.put(track)
            if (id == currentId) startIndex = items.size
            items += track.toMediaItemWithMetadata()
        }
        if (startIndex < 0) return EMPTY_RESUMPTION

        // Media3 sets the items only after this future completes; the modes have to be
        // in place before that, or the restored queue starts in the previous order.
        mediaSession.player.shuffleModeEnabled = snapshot.shuffleModeEnabled
        mediaSession.player.repeatMode = snapshot.repeatMode.validRepeatMode()
        Log.i(
            TAG,
            "resumption items=${items.size} startIndex=$startIndex " +
                "positionMs=${snapshot.positionMs} window=$from..$to",
        )
        return MediaSession.MediaItemsWithStartPosition(
            items,
            startIndex,
            clampedPosition(snapshot.positionMs, currentTrack),
        )
    }

    /**
     * Keeps a restored timestamp inside the item it belongs to.
     *
     * The snapshot can outlive the file: re-encoding a track shortens it, and a saved
     * position near the old end would restore already finished. The in-app path clamps
     * in [PlaybackSnapshotStore.restore]; the platform resumption path builds the item
     * list itself and needs the same guard.
     */
    private fun clampedPosition(positionMs: Long, track: Track?): Long {
        val position = positionMs.coerceAtLeast(0L)
        val duration = track?.duration ?: 0L
        return if (duration > 0L) position.coerceAtMost(duration) else position
    }

    /**
     * Writes the queue, the current item and the position as one snapshot.
     *
     * Called from player events, not from the UI's 500 ms progress ticker: the ticker
     * only runs while the player sheet is visible and is ten times faster than the
     * snapshot needs.
     *
     * [scope] is what keeps a track change cheap. `CURSOR` skips the id list entirely —
     * see [QueueSnapshotPolicy] — so skipping through a 1 233-track library no longer
     * re-encodes a 12 KB string and writes two files per track.
     *
     * Runs under [snapshotWriteLock]: see there for why the read-then-write pair has to
     * be atomic.
     */
    private suspend fun persistQueue(
        player: Player,
        scope: QueueSnapshotScope = QueueSnapshotScope.FULL,
    ): Unit = snapshotWriteLock.withLock { persistQueueLocked(player, scope) }

    private suspend fun persistQueueLocked(
        player: Player,
        scope: QueueSnapshotScope,
    ) {
        val store = snapshotStore ?: return
        if (scope == QueueSnapshotScope.CURSOR) {
            persistCursor(player, store)
            return
        }
        val count = player.mediaItemCount
        if (count == 0) {
            // A cleared queue must not leave the old ids behind: the next cold start
            // would resurrect a queue the session no longer owns.
            if (saveSnapshotSafely("clear", ::logSnapshotFailure) { store.clear() }) {
                Log.d(TAG, "snapshot cleared (empty queue)")
            }
            return
        }
        val ids = ArrayList<Long>(count)
        var current = -1
        for (index in 0 until count) {
            val id = player.getMediaItemAt(index).mediaId.toLongOrNull() ?: return
            if (index == player.currentMediaItemIndex) current = ids.size
            ids += id
        }
        if (current < 0) return
        val saved = saveSnapshotSafely("queue", ::logSnapshotFailure) {
            store.saveQueue(
                PlaybackSnapshot(
                    trackIds = ids,
                    currentIndex = current,
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                    shuffleModeEnabled = player.shuffleModeEnabled,
                    repeatMode = player.repeatMode,
                    wasPlaying = player.playWhenReady &&
                        player.playbackState != Player.STATE_ENDED,
                    current = player.currentMediaItem?.toSnapshotTrack(),
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
        }
        if (saved) {
            // Acknowledgement, not attempt: a failed write leaves the structure dirty
            // so the next transition retries the full form.
            queueSnapshotPolicy.onIdsPersisted()
            Log.d(TAG, "snapshot saved tracks=${ids.size} index=$current")
        }
    }

    /**
     * Track change: move the index and the position, leave the id list untouched.
     *
     * Guarded on `mediaItemCount` because `load()` rejects a snapshot with no ids — a
     * cursor on top of an empty queue store would look like a corrupt snapshot rather
     * than a missing one. When that happens the next event is forced to be full.
     *
     * Only called from [persistQueueLocked]: it reads three player values and assumes
     * nothing else writes a snapshot in between.
     */
    private suspend fun persistCursor(player: Player, store: PlaybackSnapshotStore) {
        val count = player.mediaItemCount
        val index = player.currentMediaItemIndex
        if (count == 0 || index < 0 || index >= count) return
        val saved = saveSnapshotSafely("cursor", ::logSnapshotFailure) {
            store.saveCursor(
                currentIndex = index,
                current = player.currentMediaItem?.toSnapshotTrack(),
                positionMs = player.currentPosition.coerceAtLeast(0L),
                shuffleModeEnabled = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                wasPlaying = player.playWhenReady &&
                    player.playbackState != Player.STATE_ENDED,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        if (saved) Log.d(TAG, "snapshot cursor saved index=$index")
    }

    private suspend fun persistPosition(player: Player) {
        val store = snapshotStore ?: return
        snapshotWriteLock.withLock {
            saveSnapshotSafely("position", ::logSnapshotFailure) {
                store.savePlaybackState(
                    wasPlaying = player.playWhenReady &&
                        player.playbackState != Player.STATE_ENDED,
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                    // Read under the same lock as the queue writers, so the item this
                    // position belongs to cannot change between the read and the write.
                    currentIndex = player.currentMediaItemIndex,
                )
            }
        }
    }

    /**
     * 快照写只有一个入口，一次只跑一个。
     *
     * 每个写入方都是"读播放器状态 → 写队列 store → 写位置 store"。三条路径
     * （切歌的 CURSOR、时间轴变化的 FULL、十五秒一次的 ticker）各自 `launch`，
     * 落到 `Dispatchers.IO` 上并行执行，完成顺序与发起顺序无关。两种后果都出现过：
     *
     *  - 旧的写入后落地，把新的 index 覆盖回去（冷启动回到**上一首**）；
     *  - ticker 带着上一首的位置，后落地但时间戳更新，于是那一秒被算作"属于新的一首"
     *    （冷启动从新歌的第 37 秒开始播）。
     *
     * 加锁之后"读到的状态"与"写下的状态"之间没有别的写入方插入，最后落地的一定是
     * 最后发起的。位置侧的 `position_index` 是第二道闸：进程在两次 edit 之间被杀时
     * 锁已经不存在了，只有落盘的数据能证明位置属于谁。
     */
    private val snapshotWriteLock = Mutex()

    /**
     * A snapshot is an optimization; storage failure must not escape into [serviceScope].
     *
     * These writes run from player listeners and the ticker on `Dispatchers.Main.immediate`
     * with no exception handler. An uncaught `IOException` there takes down the process
     * while it is playing in the background — the exact opposite of what persisting a
     * resume point is for.
     */
    private fun logSnapshotFailure(label: String, failure: Throwable) {
        Log.w(TAG, "snapshot $label write failed; playback continues", failure)
    }

    /**
     * Ticks the position every fifteen seconds **while playback advances**.
     *
     * 早先是 `onCreate` 里无条件启动、循环里再 `if (player.isPlaying)` 判断。那样即使
     * 一直暂停着、服务只是被系统留着，也每十五秒唤醒一次主线程去问一个已知为 false 的
     * 状态。现在由 [Player.Listener.onIsPlayingChanged] 启停：不播就没有这个循环，
     * "暂停期间不写快照"也不再依赖循环体内的判断。
     *
     * 幂等：`isPlaying` 可能连续报两次 true（例如切歌时缓冲），重复启动会留下两个循环，
     * 于是写入频率翻倍，而 `snapshotWriteLock` 只会让它们排队而不是互相抵消。
     */
    private fun startSnapshotTicker() {
        if (snapshotStore == null) return
        if (positionJob?.isActive == true) return
        positionJob = serviceScope.launch {
            while (isActive) {
                delay(SNAPSHOT_INTERVAL_MS)
                val player = mediaSession?.player ?: continue
                if (player.isPlaying) persistPosition(player)
            }
        }
    }

    private fun stopSnapshotTicker() {
        positionJob?.cancel()
        positionJob = null
    }

    /**
     * The notification/lock-screen tap target.
     *
     * `DefaultMediaNotificationProvider` passes `session.getSessionActivity()` straight
     * to `setContentIntent()`; without it, tapping the notification body does nothing.
     * `MainActivity` is `singleTop`, so the flags reuse the existing task instead of
     * stacking a second instance on top of the player sheet.
     */
    private fun sessionActivity(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_SESSION_ACTIVITY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

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
            //
            // Media3 1.9 release notes say this is now the default and the call can
            // be dropped. By value that is true in 1.11.1 — but only as a side
            // effect: with `wakeModeSet == false`, `ExoPlayerImpl` enables the wake
            // lock iff `Builder.experimentalEnableStuckPlayingDetection` is true,
            // because the flag is what decides whether the four stuck-detection
            // timeouts are finite. That is an *experimental* default standing in for
            // a power contract: flipping it, or setting any single stuck timeout to
            // MAX_VALUE, turns the wake lock off silently and the only symptom is
            // playback stalling in doze. So the value stays explicit. This is also
            // the app's only wake lock handling — no WakeLockManager, no manual
            // acquire/release.
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

                /**
                 * The format the renderer actually hands to `AudioTrack`.
                 *
                 * `setEnableAudioFloatOutput(true)` is a request; this line is the
                 * result. `pcm=s16` on a 24-bit source means the flag was ignored on
                 * this device, which is otherwise indistinguishable from it working.
                 */
                override fun onAudioInputFormatChanged(
                    eventTime: AnalyticsListener.EventTime,
                    format: Format,
                    decoderReuseEvaluation: DecoderReuseEvaluation?,
                ) {
                    AudioPipeline.recordInput(
                        sampleRate = format.sampleRate,
                        channelCount = format.channelCount,
                        pcmEncoding = format.pcmEncoding,
                    )
                }

                /**
                 * The only measurement behind the buffer parameters.
                 *
                 * `bufferMs` separates a short decoder hiccup from a storage stall, and
                 * that distinction is what decides whether `MIN_BUFFER_MS` or the byte
                 * cap is the term to change. Fires only on an actual underrun.
                 */
                override fun onAudioUnderrun(
                    eventTime: AnalyticsListener.EventTime,
                    bufferSize: Int,
                    bufferSizeMs: Long,
                    elapsedSinceLastFeedMs: Long,
                ) {
                    AudioPipeline.recordUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs)
                }
            },
        )

        // Playback errors used to be silent: ExoPlayer stopped and the UI just
        // looked "paused". The classification keeps them attributable: a stuck player
        // arrives as ERROR_CODE_UNEXPECTED with a StuckPlayerException in its cause,
        // and logging only the code would file it under "unknown" forever.
        //
        // Nothing here retries. ExoPlayer does not retry by itself either, and the one
        // case where a retry would be actively harmful is exactly the one this
        // classification exists for (see PlaybackFailure.autoRetryAllowed).
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val failure = classifyPlaybackFailure(
                    errorCodeName = error.errorCodeName,
                    stuck = stuckDetailOf(error),
                )
                Log.e(
                    TAG,
                    "playback error ${failure.describe()} " +
                        "autoRetry=${failure.autoRetryAllowed} " +
                        "pos=${player.currentPosition}ms " +
                        "media=${player.currentMediaItem?.mediaId} " +
                        "cause=${error.cause}",
                )
            }
        })

        // Snapshot writes. Only a timeline change can move the item list itself, so only
        // that path re-encodes every id; a track change writes the cursor. Before 1.0.67
        // both did the full form, which on a 1 233-track library meant a ~12 KB string
        // and two atomic file rewrites per transition — for an unchanged queue.
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                queueSnapshotPolicy.onTimelineChanged()
                serviceScope.launch { persistQueue(player) }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                trackStartProbe.onTransition(
                    nowMs = SystemClock.elapsedRealtime(),
                    playWhenReady = player.playWhenReady,
                    isPlaying = player.isPlaying,
                )?.let { reportTrackStart(it, player) }
                // Evaluated on the callback thread, but persistCursor re-reads the index
                // when the write actually runs: a queued full write from a timeline
                // change arriving afterwards therefore cannot be undone by a stale cursor.
                val scope = queueSnapshotPolicy.scopeForTransition()
                serviceScope.launch {
                    persistQueue(player, scope)
                    persistPosition(player)
                }
                // A ramp for the previous track must not keep writing.
                val gainGeneration = ++replayGainGeneration
                serviceScope.launch { applyReplayGain(player, gainGeneration) }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                trackStartProbe.onPlayWhenReadyChanged(
                    playWhenReady = playWhenReady,
                    nowMs = SystemClock.elapsedRealtime(),
                )
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    trackStartProbe.onIsPlayingChanged(SystemClock.elapsedRealtime())
                        ?.let { reportTrackStart(it, player) }
                    // The one signal the library waits for before it starts a background
                    // reconcile: from here on the first track is audible and its buffer
                    // must not be made to compete with a cursor walk.
                    container?.playbackStartGate?.markAudible()
                    startSnapshotTicker()
                } else {
                    stopSnapshotTicker()
                }
                serviceScope.launch { persistPosition(player) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                    serviceScope.launch {
                        persistQueue(player)
                        persistPosition(player)
                    }
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                serviceScope.launch { persistQueue(player) }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                serviceScope.launch { persistQueue(player) }
            }
        })

        // Media3's default artwork loader reads the URI over a DataSource, which
        // cannot serve embedded cover art; the session therefore needs a loader that
        // goes through ContentResolver. Without it the notification, lock screen and
        // Bluetooth metadata show no artwork at all.
        val loader = ArtworkBitmapLoader(this)
        artworkLoader = loader

        // The browser tree reads the persisted library, not the UI's in-memory list:
        // the service can be started by a media key long before a LibraryViewModel
        // exists. LibraryRevision tells it when a rescan has replaced those rows.
        libraryTree = LibraryTree(
            loadTracks = { container?.libraryCache?.load().orEmpty() },
            revision = { container?.libraryRevision?.current() ?: 0L },
            labels = LibraryTree.Labels(
                root = getString(R.string.library_root_title),
                songs = getString(R.string.library_songs_title),
                albums = getString(R.string.library_albums_title),
                artists = getString(R.string.library_artists_title),
                untitled = getString(R.string.track_untitled),
                unknownArtist = getString(R.string.track_unknown_artist),
                unknownAlbum = getString(R.string.track_unknown_album),
            ),
        )

        mediaSession = MediaLibrarySession.Builder(this, player, sessionCallback)
            .setSessionActivity(sessionActivity())
            .setBitmapLoader(loader)
            .build()

        // 只有已经在播时才启动：暂停着创建服务不该凭空开一个每十五秒醒一次的循环。
        // 之后由 `onIsPlayingChanged` 启停。
        if (player.isPlaying) startSnapshotTicker()
        replayGainReader = ReplayGainReader(this)
    }

    /**
     * Applies the current track's ReplayGain correction as a scalar volume.
     *
     * Only ever attenuates (see [gainToVolume]), so this cannot clip and needs no
     * limiter. Untagged tracks are explicitly set back to unity: `Player.volume` is
     * sticky across `setMediaItems`, so leaving the previous track's correction in
     * place would keep a quiet track quiet for the whole album.
     *
     * The generation check after the read is load-bearing. Reading a tag takes a file
     * open on the IO dispatcher, and the user can skip again inside that window. It
     * compares generations rather than media ids because skipping A -> B -> A would let
     * the first A's value through an id comparison.
     */
    private suspend fun applyReplayGain(player: Player, generation: Int) {
        val reader = replayGainReader ?: return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val track = trackIndex?.get(mediaId) ?: libraryTree?.cachedTrack(mediaId)
        if (track == null) {
            // No correction to apply for this item, but the previous track's ramp
            // stopped part-way when the generation moved on. Sliding to unity keeps the
            // recovery consistent with the normal path: setting it in one write would
            // jump the level in the middle of whatever is already audible.
            if (generation == replayGainGeneration) rampVolumeTo(player, 1f, generation)
            return
        }
        // Skipping the read when the transition is already stale: `gainFor` opens the
        // file, and a skip inside this window would otherwise pay for a tag that can no
        // longer be applied.
        if (generation != replayGainGeneration) return
        val startedAt = SystemClock.elapsedRealtime()
        val gain = reader.gainFor(track)
        if (generation != replayGainGeneration) return
        val readMs = SystemClock.elapsedRealtime() - startedAt
        val target = if (gain == null) 1f else gainToVolume(gain)
        if (gain == null) {
            Log.i(TAG, "replaygain id=$mediaId untagged readMs=$readMs")
        } else {
            // Volume is logged in thousandths: a float printout varies in length and the
            // only thing worth comparing between runs is the third decimal.
            Log.i(TAG, "replaygain id=$mediaId gain=${gain}dB volume=${(target * 1000).toInt()} readMs=$readMs")
        }
        rampVolumeTo(player, target, generation)
    }

    /**
     * Slides the player gain to [target] over [VOLUME_RAMP_MS] instead of setting it.
     *
     * Setting it once is a step, and across a gapless boundary that step lands in the
     * middle of continuous audio. The sequence comes from [volumeRamp], which also
     * collapses to a single value when the difference is inaudible — in that case this
     * does no waiting at all, which is the common case for an untagged track following
     * another untagged track.
     *
     * Stopping mid-ramp is safe: the transition that bumped [replayGainGeneration] also
     * launched a replacement, which starts from whatever level the last write left.
     */
    private suspend fun rampVolumeTo(player: Player, target: Float, generation: Int) {
        val steps = volumeRamp(player.volume, target)
        steps.forEachIndexed { index, volume ->
            if (generation != replayGainGeneration) return
            player.volume = volume
            if (index != steps.lastIndex) delay(VOLUME_RAMP_STEP_MS)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    /**
     * Writes the last position before the task disappears, then stops the service if
     * nothing is playing.
     *
     * The write is awaited before `stopSelf()`: `onDestroy` cancels [serviceScope], and
     * a fire-and-forget write launched from here would be cancelled exactly when the
     * user swiped the app away — the one moment the snapshot matters most.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null) {
            stopSelf()
            return
        }
        val shouldStop = !player.playWhenReady || player.mediaItemCount == 0
        serviceScope.launch {
            // The stop belongs in `finally`: a failed DataStore write must not leave a
            // paused service alive after the task is gone.
            try {
                persistQueue(player)
                persistPosition(player)
            } finally {
                if (shouldStop) stopSelf()
            }
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
     * 记一行「起播请求/缓冲开始 → 出声」的耗时。
     *
     * 这是「要不要上分级预加载」的前置证据，也是唯一能证伪「切歌零缓冲」的数字。
     * 本地文件的理论下限是几毫秒读盘加 10–50 ms 解码器初始化；实测贴着这个量级就
     * 说明瓶颈已经不在数据供给，再叠一个预加载 player 只会多一份缓冲和一条回放线程。
     */
    private fun reportTrackStart(latencyMs: Long, player: Player) {
        Log.i(TAG, "track start latency=${latencyMs}ms media=${player.currentMediaItem?.mediaId}")
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
        stopSnapshotTicker()
        serviceScope.cancel()
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        libraryTree = null
        replayGainReader = null
        releaseBufferPool()
        bufferAllocator = null
        artworkLoader?.release()
        artworkLoader = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PlaybackService"

        /**
         * How far either side of the current item the resumption window reaches.
         *
         * 100 is a starting point, not a verdict: 201 items with metadata are well
         * under the Binder limit, and the queue truncation is the accepted P0 cost.
         */
        const val RESUMING_WINDOW = 100

        /**
         * Position write cadence while playback advances.
         *
         * Fifteen seconds, not five: every write rewrites a DataStore file and fsyncs it,
         * which is 720 disk writes an hour for a value that only matters if the process
         * dies. Track changes, pauses and service teardown still write immediately, so
         * the worst case is losing the last 15 s of a killed session.
         */
        const val SNAPSHOT_INTERVAL_MS = 15_000L

        /** Request code for [sessionActivity]; the app has only one PendingIntent. */
        const val REQUEST_SESSION_ACTIVITY = 200

        /**
         * "Nothing to resume".
         *
         * Media3 treats an empty list as `RESULT_ERROR_NOT_SUPPORTED` for the
         * recent-item query and as a no-op for playback, so this avoids the error log
         * a failed future would produce on every cold media button press.
         */
        val EMPTY_RESUMPTION = MediaSession.MediaItemsWithStartPosition(
            emptyList(),
            /* startIndex = */ 0,
            /* startPositionMs = */ 0L,
        )

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
