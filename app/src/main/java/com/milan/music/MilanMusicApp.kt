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
}
