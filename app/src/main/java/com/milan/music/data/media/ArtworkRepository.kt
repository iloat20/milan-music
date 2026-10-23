package com.milan.music.data.media

import android.content.ComponentCallbacks2
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.milan.music.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Loads embedded album artwork.
 *
 * API 29+ uses [android.content.ContentResolver.loadThumbnail] which resolves the
 * embedded picture without probing ID3 tags manually. On API 26-28 the legacy
 * album-art provider is used. Failures degrade to `null` so the UI can draw a
 * deterministic placeholder instead of a broken image.
 *
 * Both caches are keyed by **albumId**, not track id: every track of an album
 * resolves to the same cover, so keying by track id would store one bitmap per song
 * and evict the cache after a handful of tracks.
 *
 * List covers and player covers are decoded at different resolutions. A 52 dp row
 * needs ~182 px at 3.5x density, so decoding 512 px there wasted 7x the memory and
 * capped the LRU at ~24 albums; the player needs the larger source because it
 * renders the cover full-width. The trade-off: a wrongly tagged compilation (one
 * albumId, differing embedded art per file) shows the first decoded cover for all
 * its tracks — the legacy API 26-28 path is album-keyed by design anyway, so this
 * matches its semantics.
 *
 * All four lookups share one request pipeline: a cache hit returns without suspending,
 * concurrent requests for the same album are merged into one decode, and a producer
 * cancelled mid-decode defers to whatever it already cached. That pipeline is
 * [MemoCache] — it exists once rather than four times, and `MemoCacheTest` pins it.
 */
class ArtworkRepository(private val context: Context) {

    /**
     * Total bitmap budget, shared with the notification loader. See [BitmapBudget]
     * for why it is derived from the heap rather than hard-coded.
     *
     * Slices below sum to 14.5/16: list 8, player 2, backdrop 1, reflection 0.5,
     * notification 3. The remaining 1.5/16 is deliberate headroom: decoding a cover
     * holds the source and the scaled result at the same time, and that transient
     * allocation lives outside every cache, so it must not be counted against them.
     */
    private val budget = BitmapBudget.totalBytes(context)

    /**
     * List thumbnails, keyed by albumId.
     *
     * Stored as [Bitmap] rather than [ImageBitmap] so `sizeOf` can report
     * `allocationByteCount`: MediaProvider is free to answer `loadThumbnail` with a
     * HARDWARE bitmap, whose real footprint bears no relation to `w * h * 4`.
     *
     * The 8/16 slice is 12 MB at a 192 MB heap — about 80 albums at 147 KB each, i.e.
     * several screens of scroll-back. It was 10/16; that extra 3 MB never bought a
     * decode that this one misses on any realistic scroll, and it was resident for as
     * long as the process lived.
     */
    private val cache = object : LruCache<Long, Bitmap>(BitmapBudget.slice(budget, 8, 16)) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
    }

    /**
     * Trimmed player-screen artwork, keyed by albumId.
     *
     * The carousel keeps three pages composed at once (previous / current / next), and
     * the one that becomes current re-reads the cache on the frame it is re-composed —
     * a miss there paints the placeholder gradient before the cover, which reads as a
     * flash on every switch. A 512 px cover is ~1 MB, so `2/16` of a 24 MB budget held
     * exactly three and any eviction broke that guarantee; `3/16` leaves one spare.
     */
    private val playerCache = object : LruCache<Long, Bitmap>(BitmapBudget.slice(budget, 3, 16)) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
    }

    /**
     * 96 px player backdrops, keyed by albumId.
     *
     * The player re-runs its backdrop effect whenever it is opened or the track
     * changes, and without a cache each pass paid a `ContentResolver` round trip plus
     * a decode for an image that had not changed. One album's backdrop is ~36 KB, so
     * this slice holds dozens.
     */
    private val backdropCache = object : LruCache<Long, Bitmap>(BitmapBudget.slice(budget, 1, 16)) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
    }

    /**
     * Vertically flipped covers, keyed by albumId. The player draws one under the
     * cover as its reflection.
     *
     * **The slice has to exceed one bitmap or this cache holds nothing.** [LruCache]
     * evicts on insert while `size > maxSize`, so a single entry larger than the cap
     * evicts itself and the next caller pays a full decode-and-flip again. At `1/32` of
     * a 24 MB budget the cap was 768 KB against a ~1 MB 512 px reflection: the cache
     * was a no-op on every device, and every track change re-ran the flip.
     * [REFLECTION_LEGACY_SIZE] on API 30 and below is a few KB, so this slice holds
     * plenty there (see [reflectionOf]).
     */
    private val reflectionCache = object : LruCache<Long, Bitmap>(BitmapBudget.slice(budget, 2, 32)) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
    }

    /**
     * 四个缓存各自的一次「查缓存 → 合并并发请求 → 解码」，见 [MemoCache]。
     *
     * 声明在缓存之后：`peek` 捕获上面对应的 `LruCache`，属性按声明顺序初始化。
     */
    private val listMemo = MemoCache<Long, ImageBitmap>(peek = { cache.get(it)?.asImageBitmap() })
    private val playerMemo =
        MemoCache<Long, ImageBitmap>(peek = { playerCache.get(it)?.asImageBitmap() })
    private val backdropMemo =
        MemoCache<Long, ImageBitmap>(peek = { backdropCache.get(it)?.asImageBitmap() })
    private val reflectionMemo =
        MemoCache<Long, ImageBitmap>(peek = { reflectionCache.get(it)?.asImageBitmap() })

    /** Row thumbnails for the library list, at [LIST_THUMB_SIZE]. */
    suspend fun load(track: Track): ImageBitmap? = listMemo.load(track.albumId) { albumId ->
        val decoded = withContext(Dispatchers.IO) { decodeBitmap(track, LIST_THUMB_SIZE) }
        if (decoded != null) cache.put(albumId, decoded)
        decoded?.asImageBitmap()
    }

    /**
     * Artwork for the player screen, with uniform light margins cropped away.
     *
     * Many album covers are a white square with a portrait poster pasted in the
     * middle; scaling alone cannot remove those side bands because they are part of
     * the bitmap itself. The trimmed result is cached — re-entering the player or
     * switching tracks within one album must not re-decode and re-scan 512×512 px.
     */
    suspend fun loadPlayerArtwork(track: Track): ImageBitmap? =
        playerMemo.load(track.albumId) { albumId ->
            // 解码结果是相邻页与替换页之间的交接物，所以必须活过调用方的取消：
            // 调用的那一方（轮播里退场的页）恰好在这张封面成为当前页时被取消。
            // 把 `put` 一起放进 NonCancellable —— 取消可以在 `withContext` 返回途中
            // 触发，落在 put 之前，那时缓存还是空的，等待方就会拿到 null。
            withContext(Dispatchers.IO + NonCancellable) {
                decodeBitmap(track, PLAYER_THUMB_SIZE)?.let(::trimLightMargins)?.also {
                    playerCache.put(albumId, it)
                }
            }?.asImageBitmap()
        }

    /**
     * Small thumbnail used as the player backdrop, cached per album.
     *
     * A 96 px thumbnail scaled to full screen is inherently soft, so the player can
     * skip the expensive real-time `Modifier.blur` over a full-resolution bitmap.
     * Cached because re-entering the player re-runs the effect for the current track
     * even when nothing changed; the dominant-colour scan still repeats, but the
     * ContentResolver round trip and the decode no longer do.
     */
    suspend fun loadBackdrop(track: Track): ImageBitmap? =
        backdropMemo.load(track.albumId) { albumId ->
            val decoded = withContext(Dispatchers.IO) { decodeBitmap(track, BACKDROP_SIZE) }
            if (decoded != null) backdropCache.put(albumId, decoded)
            decoded?.asImageBitmap()
        }

    /**
     * Vertically flipped cover, for the player's reflection band under the artwork.
     *
     * Sourced from [loadPlayerArtwork] rather than [loadBackdrop]: the reflection has to
     * stay **recognisable as the cover**, and a 96 px thumbnail scaled to half a screen
     * is 11x — no blur radius leaves a pattern in it. The player cover is already
     * decoded and cached, so this costs a flip and no extra MediaProvider round trip.
     *
     * The flip works on pixels rather than a `Matrix` so [reflectRows] stays a plain
     * function that unit tests can drive without a device.
     */
    suspend fun loadReflection(track: Track): ImageBitmap? =
        reflectionMemo.load(track.albumId) { albumId ->
            val source = loadPlayerArtwork(track)
            // `Modifier.blur` is an API 31 RenderEffect; below that the reflection can
            // only be softened by its own resolution, so the bitmap is stored tiny.
            val targetWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                REFLECTION_SIZE
            } else {
                REFLECTION_LEGACY_SIZE
            }
            val flipped = source?.let {
                withContext(Dispatchers.IO) { reflectionOf(it, targetWidth) }
            }
            if (flipped != null) reflectionCache.put(albumId, flipped)
            flipped?.asImageBitmap()
        }

    fun cached(albumId: Long): ImageBitmap? = cache.get(albumId)?.asImageBitmap()

    /**
     * Trimmed player artwork already decoded for this album, or `null`.
     *
     * The player screen mounts the neighbouring covers ahead of the gesture, so by
     * the time one of them becomes the current page its bitmap is already in
     * [playerCache]. Reading it synchronously here keeps that hand-off free of a
     * one-frame placeholder flash.
     */
    fun cachedPlayer(albumId: Long): ImageBitmap? = playerCache.get(albumId)?.asImageBitmap()

    /** The flipped cover already prepared for this album, or `null`. */
    fun cachedReflection(albumId: Long): ImageBitmap? =
        reflectionCache.get(albumId)?.asImageBitmap()

    /**
     * Releases cached covers when the process is told to shed memory.
     *
     * [LruCache] only evicts on `put()`, so without this hook the list cache sits
     * pinned at its slice ceiling for as long as the process lives — including while
     * the app is in the background, where the only artwork on screen is the mini
     * player's and the notification's. Trimming on `UI_HIDDEN` is the cheapest large
     * win available here: the covers rebuild from MediaProvider (local, no network)
     * on the next scroll.
     *
     * Only `UI_HIDDEN` (20) and `BACKGROUND` (40) are still delivered: API 34
     * deprecated `RUNNING_*` and API 35 deprecated `MODERATE`/`COMPLETE`, and the
     * platform stopped sending them. The `RUNNING_LOW` branch is kept for API 26-33
     * devices, where it is the only pre-background warning that exists.
     */
    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        when {
            // BACKGROUND and anything numerically harsher: nothing of ours is visible.
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> evictAll()
            // UI_HIDDEN: the app left the foreground. Keep roughly one screenful of
            // list covers and drop the player-sized ones entirely.
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                cache.trimToSize(cache.maxSize() / 6)
                playerCache.evictAll()
                backdropCache.evictAll()
                reflectionCache.evictAll()
            }
            // RUNNING_LOW / RUNNING_CRITICAL (API 26-33): still foreground, but the
            // process is under pressure. Shrink hard, never empty them.
            //
            // This test has to come before the RUNNING_MODERATE one below: the
            // constants are 10 vs 5 and both branches use `>=`, so a wider `>= 5`
            // placed first would swallow every RUNNING_LOW and RUNNING_CRITICAL
            // event and silently cap the response at the gentler tier.
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                cache.trimToSize(cache.maxSize() / 8)
                playerCache.trimToSize(playerCache.maxSize() / 8)
                backdropCache.trimToSize(backdropCache.maxSize() / 8)
                reflectionCache.trimToSize(reflectionCache.maxSize() / 8)
            }
            // RUNNING_MODERATE: the system is starting to run low while we are still
            // in the foreground. Halving is a cheap first response and costs no
            // visible cover: `trimToSize` evicts least-recently-used first, so the
            // rows on screen and the player cover — by definition the most recently
            // used entries — survive. Only covers the user scrolls back to may need
            // re-decoding.
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                cache.trimToSize(cache.maxSize() / 2)
                playerCache.trimToSize(playerCache.maxSize() / 2)
                backdropCache.trimToSize(backdropCache.maxSize() / 2)
                reflectionCache.trimToSize(reflectionCache.maxSize() / 2)
            }
        }
    }

    /** Drops every cached cover. They are all re-decodable from local storage. */
    fun evictAll() {
        cache.evictAll()
        playerCache.evictAll()
        backdropCache.evictAll()
        reflectionCache.evictAll()
    }

    /**
     * Decoded artwork, or null when the provider cannot serve it.
     *
     * The failure is logged rather than swallowed: "no cover on this album" is
     * indistinguishable from "the provider threw" at the UI level, and the previous
     * silent `getOrNull()` made the difference unrecoverable after the fact.
     */
    private fun decodeBitmap(track: Track, size: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(track.uri, Size(size, size), null)
        } else {
            decodeAlbumArt(albumArtUri(track.albumId), size)
        }
    }.onFailure {
        Log.w(TAG, "artwork decode failed for album ${track.albumId} at ${size}px", it)
    }.getOrNull()

    /**
     * API 26-28 album art: bounds pass, then sample, then trim to [size].
     *
     * `MediaStore.Images.Media.getBitmap` (the previous implementation) opened the
     * stream and decoded at native size, scaling afterwards — a 1200 px album-art
     * row allocated 5.5 MB to produce a 192 px thumbnail. Reading the stream twice,
     * once for the header and once for the pixels, caps the peak at the sampled
     * size instead. The two calls are otherwise identical: `getBitmap` was itself
     * `openInputStream` + `decodeStream`.
     *
     * Sampling alone cannot land on an exact size because `inSampleSize` is a
     * power-of-two divisor: a 1200 px source sampled by 4 still returns 300 px, and
     * a 480 px source must not be sampled at all. The final [scale] closes that
     * remainder and preserves the aspect ratio — the previous square `scale(size,
     * size)` stretched any cover that was not 1:1.
     */
    private fun decodeAlbumArt(uri: Uri, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null

        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(longest, size)
        }
        val sampled = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val sampledLongest = max(sampled.width, sampled.height)
        if (sampledLongest <= size) return sampled
        val ratio = size.toFloat() / sampledLongest
        return sampled.scale(
            (sampled.width * ratio).toInt().coerceAtLeast(1),
            (sampled.height * ratio).toInt().coerceAtLeast(1),
        )
    }

    /**
     * Largest power-of-two divisor that keeps the longest edge at or above [target].
     *
     * Mirrors `ArtworkBitmapLoader.sampleSizeFor`, kept local rather than shared:
     * the two call sites differ in where the target comes from (a constant there, a
     * parameter here) and six lines do not justify a common module.
     */
    private fun sampleSizeFor(longest: Int, target: Int): Int {
        // A non-positive target keeps the loop condition true forever and overflows
        // the divisor into a divide-by-zero. Every caller passes a positive constant,
        // so this clamp is defensive only.
        val floor = target.coerceAtLeast(1)
        var sample = 1
        while (longest / (sample * 2) >= floor) sample *= 2
        return sample
    }

    /**
     * Crops rows/columns that are uniformly near-white.
     *
     * Guarded on two fronts so a genuinely pale cover is never destroyed: at most
     * [MAX_TRIM_FRACTION] of each edge may be removed, and the result must retain at
     * least [MIN_KEEP_FRACTION] of the original area in both axes.
     */
    private fun trimLightMargins(source: Bitmap): Bitmap {
        // 读不到像素就跳过修剪：同一张封面，只是浅色边距留着。也不能让它抛 ——
        // 这段跑在调用方的 NonCancellable 块里，异常会一路到 recomposer。
        val image = source.softwareCopy() ?: return source
        val width = image.width
        val height = image.height
        if (width < 32 || height < 32) return image

        val pixels = IntArray(width * height)
        image.getPixels(pixels, 0, width, 0, 0, width, height)

        fun isLight(x: Int, y: Int): Boolean {
            val color = pixels[y * width + x]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            return r >= LIGHT_THRESHOLD && g >= LIGHT_THRESHOLD && b >= LIGHT_THRESHOLD
        }

        fun rowIsLight(y: Int): Boolean {
            for (x in 0 until width) if (!isLight(x, y)) return false
            return true
        }

        fun columnIsLight(x: Int): Boolean {
            for (y in 0 until height) if (!isLight(x, y)) return false
            return true
        }

        val maxTrimY = (height * MAX_TRIM_FRACTION).toInt()
        val maxTrimX = (width * MAX_TRIM_FRACTION).toInt()

        var top = 0
        while (top < maxTrimY && rowIsLight(top)) top++

        var bottom = height - 1
        while (bottom > height - 1 - maxTrimY && rowIsLight(bottom)) bottom--

        var left = 0
        while (left < maxTrimX && columnIsLight(left)) left++

        var right = width - 1
        while (right > width - 1 - maxTrimX && columnIsLight(right)) right--

        val trimmedWidth = right - left + 1
        val trimmedHeight = bottom - top + 1
        if (trimmedWidth < width * MIN_KEEP_FRACTION || trimmedHeight < height * MIN_KEEP_FRACTION) {
            return image
        }
        if (trimmedWidth == width && trimmedHeight == height) return image

        return runCatching {
            Bitmap.createBitmap(image, left, top, max(1, trimmedWidth), max(1, trimmedHeight))
        }.getOrDefault(image)
    }

    /**
     * [source] scaled down to [targetWidth] and flipped top-to-bottom.
     *
     * Returns `null` rather than throwing when the pixels cannot be read: the reflection
     * is decorative, and this runs inside the caller's `NonCancellable` hand-off, where an
     * exception would reach the recomposer. `null` degrades to the flat colour field.
     */
    private fun reflectionOf(source: ImageBitmap, targetWidth: Int): Bitmap? = runCatching {
        val software = source.asAndroidBitmap().softwareCopy() ?: return@runCatching null
        val scaled = if (software.width > targetWidth) {
            val ratio = targetWidth.toFloat() / software.width
            software.scale(
                targetWidth,
                (software.height * ratio).roundToInt().coerceAtLeast(1),
            )
        } else {
            software
        }
        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        Bitmap.createBitmap(
            reflectRows(pixels, width, height),
            width,
            height,
            Bitmap.Config.ARGB_8888,
        )
    }.onFailure {
        Log.w(TAG, "reflection flip failed for ${source.width}px artwork", it)
    }.getOrNull()

    private fun albumArtUri(albumId: Long): Uri =
        ContentUris.withAppendedId(
            "content://media/external/audio/albumart".toUri(),
            albumId,
        )

    companion object {
        private const val TAG = "ArtworkRepository"

        /** 52 dp rows at 3.5x density need 182 px; 192 leaves a small margin. */
        private const val LIST_THUMB_SIZE = 192
        /** The player renders the cover full-width, so it keeps the larger source. */
        private const val PLAYER_THUMB_SIZE = 512
        private const val BACKDROP_SIZE = 96

        /**
         * Reflection width where `Modifier.blur` is available (API 31+).
         *
         * Matches [PLAYER_THUMB_SIZE]: the reflection is drawn at the same size as the
         * cover it mirrors, so scaling it down first would only lose detail the 14 dp
         * blur does not hide.
         */
        private const val REFLECTION_SIZE = PLAYER_THUMB_SIZE

        /**
         * Reflection width on API 26-30, where `Modifier.blur` is a no-op.
         *
         * Softness has to come from resolution alone there. 96 px was tried and rejected
         * on a portrait cover: an 11x upscale still resolves a face clearly, and a
         * face hanging upside down under the title reads as a rendering bug. 48 px
         * degrades it to colour patches.
         */
        private const val REFLECTION_LEGACY_SIZE = 48

        private const val LIGHT_THRESHOLD = 236
        private const val MAX_TRIM_FRACTION = 0.22f
        private const val MIN_KEEP_FRACTION = 0.45f
    }
}

/**
 * Flips an ARGB pixel buffer top-to-bottom.
 *
 * Pure so unit tests can pin the row order without a `Bitmap`. `System.arraycopy` per
 * row rather than an element-wise loop: the buffers are 512*512 ints on API 31+, and a
 * per-pixel Kotlin loop costs noticeably more than a native block copy.
 */
internal fun reflectRows(pixels: IntArray, width: Int, height: Int): IntArray {
    require(width > 0 && height > 0) { "reflection needs a non-empty image" }
    require(pixels.size >= width * height) { "pixel buffer is smaller than the image" }

    val flipped = IntArray(width * height)
    for (row in 0 until height) {
        System.arraycopy(
            pixels,
            (height - 1 - row) * width,
            flipped,
            row * width,
            width,
        )
    }
    return flipped
}
