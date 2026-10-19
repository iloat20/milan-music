package com.milan.music.data.media

import android.app.ActivityManager
import android.content.Context

/**
 * Heap-derived byte budgets for every bitmap cache in the app.
 *
 * The caches used to carry hard-coded ceilings of 24 + 6 + 8 MB. On a device with a
 * 192 MB heap that is 19.8% of everything the process may allocate — well past the
 * conventional one-eighth watermark, and the first thing the low-memory killer
 * reclaims. Deriving the total from [ActivityManager.getMemoryClass] keeps the budget
 * proportional both ways: a 512 MB heap is not served by the same 38 MB, and older
 * hardware should not be asked for it.
 *
 * `getMemoryClass()` reports the per-process heap limit, so no `largeHeap` is needed
 * and none is requested — the manifest stays default.
 */
internal object BitmapBudget {

    /** Conventional cache watermark: one eighth of the heap. */
    private const val DIVISOR = 8

    /** `getMemoryClass()` is non-null on every supported API level; keep a sane default. */
    private const val FALLBACK_MEMORY_CLASS_MB = 192

    /**
     * The floor keeps the caches useful on tiny heaps (96 MB would otherwise yield
     * 12 MB); the ceiling exists because a screenful of covers is bounded by the
     * display, not by the heap, so headroom past ~50 MB buys nothing.
     */
    private const val MIN_BYTES = 16 * 1024 * 1024
    private const val MAX_BYTES = 48 * 1024 * 1024

    /** Whole-app bitmap budget: list + player + backdrop + notification artwork. */
    fun totalBytes(context: Context): Int {
        val memoryClassMb = context.getSystemService(ActivityManager::class.java)
            ?.memoryClass
            ?: FALLBACK_MEMORY_CLASS_MB
        // memoryClass tops out near 1024 MB, so the product stays far inside Int.
        return (memoryClassMb * 1024 * 1024 / DIVISOR).coerceIn(MIN_BYTES, MAX_BYTES)
    }

    /**
     * A slice of [total] expressed as `numerator / denominator`, floored at one byte.
     *
     * The floor is defensive: an LruCache with `maxSize == 0` still works but evicts
     * every entry immediately, which would turn a clamping mistake into a silent
     * "caching does not work" bug rather than a visible one.
     */
    fun slice(total: Int, numerator: Int, denominator: Int): Int =
        (total.toLong() * numerator / denominator).coerceAtLeast(1L).toInt()
}
