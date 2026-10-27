package com.milan.music.di

import android.content.Context
import android.util.Log
import com.milan.music.data.media.ArtworkRepository
import com.milan.music.data.media.LibraryCacheStore
import com.milan.music.data.media.LibraryRevision
import com.milan.music.data.media.MediaStoreRepository
import com.milan.music.data.prefs.SettingsStore
import com.milan.music.data.userdata.UserDataStore
import com.milan.music.play.PlaybackSnapshotStore
import com.milan.music.play.PlaybackStartGate
import com.milan.music.play.PlaybackTrackIndex
import com.milan.music.play.PlayerController
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container. A DI framework would add build time and
 * indirection that this app size does not justify.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Application-lived scope for the playback controller's tickers and collectors.
     *
     * `SupervisorJob` only stops a failure from cancelling *siblings*; the exception
     * itself still reaches the thread's uncaught handler and takes the process down.
     * Without a handler here every failure inside a `scope.launch` was an app crash,
     * while [com.milan.music.play.PlaybackService] — running the same kind of work —
     * has always had one. The gap is reachable: a released
     * [androidx.media3.session.MediaController] throws from every call, and several of
     * those calls run in this scope's coroutines.
     */
    val scope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, throwable ->
                Log.e(TAG, "app-scope coroutine failed", throwable)
            },
    )

    val mediaStore = MediaStoreRepository(appContext)
    val libraryCache = LibraryCacheStore(appContext)
    val artwork = ArtworkRepository(appContext)
    val userData = UserDataStore(appContext)
    val settings = SettingsStore(appContext)

    /**
     * Bumped after every committed library scan, so the in-memory browser tree in
     * [com.milan.music.play.PlaybackService] knows when its cached grouping is stale.
     */
    val libraryRevision = LibraryRevision()

    /**
     * Set once a track is actually audible, read by the library before it starts a
     * background reconcile. See [com.milan.music.play.PlaybackStartGate].
     */
    val playbackStartGate = PlaybackStartGate()

    /** Queue/position snapshot shared by the controller, the service and the widget. */
    val playbackSnapshot = PlaybackSnapshotStore(appContext)

    /**
     * Built before [player] and handed to both sides of the session: the controller
     * writes it when a queue is set, [com.milan.music.play.PlaybackService] reads it
     * to resolve `MediaItem` metadata on demand.
     */
    val trackIndex = PlaybackTrackIndex()
    val player = PlayerController(appContext, scope, trackIndex)

    private companion object {
        const val TAG = "AppContainer"
    }
}
