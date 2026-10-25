package com.milan.music.diag

import android.content.ComponentCallbacks2

/**
 * 内存压力档位，只用于基线采样。
 *
 * 采样的目的不是监控，而是**留证**：Android 17 的 per-app 上限杀进程不留崩溃栈，
 * 唯一的机会是在被杀之前把"当时用了多少"写进 logcat。因此 `UI_HIDDEN` 是基线锚点
 * （进入后台的那一刻），其后的 40/60/80 三档是后台压力的升级顺序。
 */
internal enum class MemoryPressure(val token: String) {
    /** 仍在前台但已经吃紧：缓存该裁的没裁干净。 */
    RUNNING_LOW("running-low"),
    RUNNING_CRITICAL("running-critical"),

    /** 刚离开前台，作为后台播放的内存基线。 */
    BASELINE("baseline"),

    /** 后台压力：系统开始回收缓存进程。 */
    BACKGROUND_LOW("background-low"),

    /** 后台最重一档，通常紧接着就是 LMK / 内存限制器。 */
    CRITICAL("critical"),

    /** 不值得记的档位。 */
    NONE("none"),
}

/**
 * `RUNNING_MODERATE`(5) 有意不记：它是"还有余量"的最轻一档，通常和 10 前后脚到达，
 * 记下来只是把真正的信号稀释成噪声。未知值同样返回 [MemoryPressure.NONE]——把不认识
 * 的档位猜成 `CRITICAL` 会让日志说谎。
 */
/*
 * `TRIM_MEMORY_RUNNING_*` 与 `TRIM_MEMORY_MODERATE/COMPLETE` 在平台常量表里已被标记
 * deprecation，但 `onTrimMemory` 回调本身没有替代品，服务与 Application 收到的仍是
 * 这组数值——抑制是有意的，和 ArtworkRepository 里同一处裁剪逻辑保持一致。
 */
@Suppress("DEPRECATION")
internal fun memoryPressureFor(level: Int): MemoryPressure = when (level) {
    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> MemoryPressure.RUNNING_LOW
    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> MemoryPressure.RUNNING_CRITICAL
    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> MemoryPressure.BASELINE
    ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> MemoryPressure.BACKGROUND_LOW
    ComponentCallbacks2.TRIM_MEMORY_MODERATE -> MemoryPressure.BACKGROUND_LOW
    ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> MemoryPressure.CRITICAL
    else -> MemoryPressure.NONE
}

/**
 * 单行内存采样。
 *
 * `heap` 是 Java 堆（`totalMemory - freeMemory`），`pss` 是整个进程的物理占用——
 * 位图走的是 native/asheme 那部分，只看堆会把封面缓存的实际成本漏掉。
 */
internal fun memoryLine(
    pressure: MemoryPressure,
    heapUsedKb: Long,
    heapMaxKb: Long,
    pssKb: Long,
): String = "mem pressure=${pressure.token} " +
    "heap=${heapUsedKb / 1024}/${heapMaxKb / 1024}MB " +
    "pss=${pssKb / 1024}MB"
