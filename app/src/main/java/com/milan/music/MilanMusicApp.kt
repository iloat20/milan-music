package com.milan.music

import android.app.Application
import com.milan.music.diag.ExitReasonReporter
import com.milan.music.diag.MemoryBaseline
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

        // The session is not bound here. It is still bound on every launch that has
        // audio permission — not by this file, but by HomeScreen, which starts
        // playback as soon as the library is ready (`autoStarted`). That is intended:
        // opening the app starts the music.
        //
        // The bill is the one an eager connect here would have carried. A
        // MediaController is a bound client, so the service it binds stays alive for
        // as long as the process does; doing the connect in this method would only
        // have moved the same cost earlier and added a second connect path next to
        // PlayerController's `pendingAction`. What that means for teardown is
        // documented on PlayerController.onUiStopped.

        // 上一次进程是被什么杀掉的。只在 API 30+ 有效，读取与去重都在 IO 线程上，
        // 这里不等结果。见 ExitReasonReporter。
        ExitReasonReporter.reportAsync(this)
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
        // 同一档位顺手记一行基线：Android 17 的内存限制器杀进程不留堆栈，这些行
        // 是事后唯一能说明"被杀前吃了多少"的证据。档位过滤在 record 内部完成。
        MemoryBaseline.record(level)
    }

    /** Legacy signal (no level): free everything. */
    override fun onLowMemory() {
        super.onLowMemory()
        container.artwork.evictAll()
    }
}
