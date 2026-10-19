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
 * The ceiling started at 48 MB and is now 32 MB, and the list slice went from 10/16
 * to 8/16 of the total. Concretely, a 192 MB heap (24 MB total, below the cap) gives
 * the list 12 MB instead of 15 MB, and a 512 MB heap that used to sit at the 48 MB cap
 * now stops at 32 MB. The list slice is the only consumer that grows with scroll depth,
 * and 12 MB still spans ~80 albums at 192 px — more scroll-back than a session
 * performs. See [com.milan.music.data.media.ArtworkRepository] for the slice split.
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
     * 12 MB). The ceiling is derived from what covers on screen can consume, not from
     * what the heap would allow: at 192 px a list thumbnail is ~147 KB, so even the
     * reduced 8/16 list slice holds ~80 albums at a 192 MB heap — several screens of
     * scroll-back. Raising the cap past 32 MB only raises the resident footprint; it
     * does not save a single decode, because no user scrolls back further than the
     * cache already reaches.
     */
    private const val MIN_BYTES = 16 * 1024 * 1024
    private const val MAX_BYTES = 32 * 1024 * 1024

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
