package com.milan.music

import android.app.Application
import com.milan.music.di.AppContainer

class MilanMusicApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // The media session is bound on demand, not here. An eager connect at start
        // created the service, its ExoPlayer and its MediaSession for every launch —
        // including launches where the user only browses the library — and because a
        // MediaController is a bound client the service could never be destroyed
        // again. PlayerController.playQueue() and togglePlayPause() connect and
        // replay the request through `pendingAction`, which costs one service bind
        // on the first tap and nothing on any launch that never plays.
    }

    /**
     * Forwards the platform's memory-pressure signal to the bitmap caches.
     *
     * This is the only trimming entry point the list/player caches get: they are
     * plain [android.util.LruCache]s that never shrink on their own, so without the
     * forward a backgrounded app keeps ~30 MB of covers resident until the process
     * is killed. Trimming only drops bitmaps — every one of them is re-decodable
     * from local storage.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        container.artwork.onTrimMemory(level)
    }

    /** Legacy signal (no level): free everything. */
    override fun onLowMemory() {
        super.onLowMemory()
        container.artwork.evictAll()
    }
}
