package com.milan.music

import android.app.Application
import com.milan.music.di.AppContainer
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeFeatureFlags

class MilanMusicApp : Application() {

    lateinit var container: AppContainer
        private set

    @OptIn(ExperimentalHazeApi::class)
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Glass 的平台 backdrop 是实验性却唯一"完整"的渲染路径：折射、模糊、色散、
        // 边缘光、交互光照都保留。不开它时 `HazeInput.Backdrop(hazeState)` 会回落成
        // 抓取 source 渲染，而回落路径按文档的表格会**丢掉折射与模糊**——那就正好是
        // 用户反馈的"只有一层平的面板"。
        //
        // 注意它只是"有资格"，不是"保证"：原生路径要求 Android 16 QPR2 级别的完整
        // SDK 37.2 与硬件加速，低了仍然回落，所以这里必须在 Glass 挂载前设置——
        // 这个开关只影响之后挂载的节点，放在 Application 里是唯一不会晚的位置。
        HazeFeatureFlags.isPlatformBackdropEnabled = true

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
