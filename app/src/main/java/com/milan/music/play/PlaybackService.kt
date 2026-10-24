package com.milan.music.play

import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
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
import com.milan.music.ui.text.albumArtistText
import com.milan.music.ui.text.albumText
import com.milan.music.ui.text.artistText
import com.milan.music.ui.text.titleText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
                snapshot.positionMs.coerceAtLeast(0L),
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
            snapshot.positionMs.coerceAtLeast(0L),
        )
    }

    /**
     * Writes the queue, the current item and the position as one snapshot.
     *
     * Called from player events, not from the UI's 500 ms progress ticker: the ticker
     * only runs while the player sheet is visible and is ten times faster than the
     * snapshot needs.
     */
    private suspend fun persistQueue(player: Player) {
        val store = snapshotStore ?: return
        val count = player.mediaItemCount
        if (count == 0) return
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
        if (saved) Log.d(TAG, "snapshot saved tracks=${ids.size} index=$current")
    }

    private suspend fun persistPosition(player: Player) {
        val store = snapshotStore ?: return
        saveSnapshotSafely("position", ::logSnapshotFailure) {
            store.savePlaybackState(
                wasPlaying = player.playWhenReady &&
                    player.playbackState != Player.STATE_ENDED,
                positionMs = player.currentPosition.coerceAtLeast(0L),
            )
        }
    }

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

    /** Every fifteen seconds while playback advances; see [persistPosition]. */
    private fun startSnapshotTicker() {
        if (snapshotStore == null) return
        positionJob = serviceScope.launch {
            while (isActive) {
                delay(SNAPSHOT_INTERVAL_MS)
                val player = mediaSession?.player ?: continue
                if (player.isPlaying) persistPosition(player)
            }
        }
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

        // Snapshot writes. Queue changes are rare (a tap, a scan-driven restore), so
        // writing the whole id list there is cheap; the position gets its own five
        // second ticker and a pause/play write, which is what survives a kill that
        // arrives between track changes.
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                serviceScope.launch { persistQueue(player) }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                serviceScope.launch {
                    persistQueue(player)
                    persistPosition(player)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
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

        startSnapshotTicker()
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
        positionJob?.cancel()
        positionJob = null
        serviceScope.cancel()
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        libraryTree = null
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
