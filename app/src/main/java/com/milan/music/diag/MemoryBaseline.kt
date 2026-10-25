package com.milan.music.diag

import android.os.Debug
import android.util.Log

/**
 * 内存基线采样：在内存压力档位变化时记一行堆用量与进程 PSS。
 *
 * 这不是监控，是留证。Android 17 的内存限制器杀进程时既没有崩溃栈也不会回调
 * `onTrimMemory` 的最后一档，所以"被杀前那一刻用了多少"只能靠 trim 序列推：基线
 * （离开前台）+ 后台压力升级。没有这两个数，所有"播放器吃内存"的判断都只是猜。
 */
internal object MemoryBaseline {

    private const val TAG = "MemoryBaseline"

    fun record(level: Int) {
        val pressure = memoryPressureFor(level)
        if (pressure == MemoryPressure.NONE) return

        val runtime = Runtime.getRuntime()
        val heapUsedKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024
        val heapMaxKb = runtime.maxMemory() / 1024

        // Debug.getMemoryInfo 读的是 /proc/self，进程内调用、不走 Binder，可以在
        // onTrimMemory 里同步执行。totalPss 覆盖位图与解码器缓冲所在的 native 部分，
        // 那正好是 Java 堆看不见的那一块。
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)

        Log.i(TAG, memoryLine(pressure, heapUsedKb, heapMaxKb, info.totalPss.toLong()))
    }
}
