package com.milan.music.data.media

import android.content.ComponentCallbacks2
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.milan.music.data.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

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
 */
class ArtworkRepository(private val context: Context) {

    /**
     * List thumbnails, keyed by albumId.
     *
     * Stored as [Bitmap] rather than [ImageBitmap] so `sizeOf` can report
     * `allocationByteCount`: MediaProvider is free to answer `loadThumbnail` with a
     * HARDWARE bitmap, whose real footprint bears no relation to `w * h * 4`.
     */
    private val cache = object : LruCache<Long, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
    }

    /** Trimmed player-screen artwork, keyed by albumId. Few albums are viewed at once. */
    private val playerCache = object : LruCache<Long, Bitmap>(PLAYER_CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
    }

    /** De-duplicates concurrent requests for the same album while scrolling fast. */
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<ImageBitmap?>>()
    private val pendingPlayer = ConcurrentHashMap<Long, CompletableDeferred<ImageBitmap?>>()

    suspend fun load(track: Track): ImageBitmap? {
        cache.get(track.albumId)?.let { return it.asImageBitmap() }

        val own = CompletableDeferred<ImageBitmap?>()
        pending.putIfAbsent(track.albumId, own)?.let { inFlight -> return inFlight.await() }

        var result: ImageBitmap? = null
        try {
            val decoded = withContext(Dispatchers.IO) { decodeBitmap(track, LIST_THUMB_SIZE) }
            if (decoded != null) cache.put(track.albumId, decoded)
            result = decoded?.asImageBitmap()
            return result
        } finally {
            pending.remove(track.albumId)
            own.complete(result)
        }
    }

    /**
     * Artwork for the player screen, with uniform light margins cropped away.
     *
     * Many album covers are a white square with a portrait poster pasted in the
     * middle; scaling alone cannot remove those side bands because they are part of
     * the bitmap itself. The trimmed result is cached — re-entering the player or
     * switching tracks within one album must not re-decode and re-scan 512×512 px.
     */
    suspend fun loadPlayerArtwork(track: Track): ImageBitmap? {
        playerCache.get(track.albumId)?.let { return it.asImageBitmap() }

        val own = CompletableDeferred<ImageBitmap?>()
        pendingPlayer.putIfAbsent(track.albumId, own)?.let { inFlight -> return inFlight.await() }

        var result: ImageBitmap? = null
        try {
            val decoded = withContext(Dispatchers.IO) {
                decodeBitmap(track, PLAYER_THUMB_SIZE)?.let(::trimLightMargins)
            }
            if (decoded != null) playerCache.put(track.albumId, decoded)
            result = decoded?.asImageBitmap()
            return result
        } finally {
            pendingPlayer.remove(track.albumId)
            own.complete(result)
        }
    }

    /**
     * Small, uncached decode used as the player backdrop.
     *
     * A 96 px thumbnail scaled to full screen is inherently soft, so the player can
     * skip the expensive real-time `Modifier.blur` over a full-resolution bitmap.
     */
    suspend fun loadBackdrop(track: Track): ImageBitmap? = withContext(Dispatchers.IO) {
        decodeBitmap(track, BACKDROP_SIZE)?.asImageBitmap()
    }

    fun cached(albumId: Long): ImageBitmap? = cache.get(albumId)?.asImageBitmap()

    /**
     * Releases cached covers when the process is told to shed memory.
     *
     * [LruCache] only evicts on `put()`, so without this hook the list cache sits
     * pinned at its 24 MB ceiling for as long as the process lives — including while
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
            }
            // RUNNING_LOW / RUNNING_CRITICAL (API 26-33): still foreground, but the
            // process is under pressure. Shrink both, never empty them.
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                cache.trimToSize(cache.maxSize() / 8)
                playerCache.trimToSize(playerCache.maxSize() / 8)
            }
        }
    }

    /** Drops every cached cover. They are all re-decodable from local storage. */
    fun evictAll() {
        cache.evictAll()
        playerCache.evictAll()
    }

    private fun decodeBitmap(track: Track, size: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(track.uri, Size(size, size), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, albumArtUri(track.albumId))
                ?.let { bitmap ->
                    if (bitmap.width > size) {
                        bitmap.scale(size, size)
                    } else {
                        bitmap
                    }
                }
        }
    }.getOrNull()

    /**
     * Crops rows/columns that are uniformly near-white.
     *
     * Guarded on two fronts so a genuinely pale cover is never destroyed: at most
     * [MAX_TRIM_FRACTION] of each edge may be removed, and the result must retain at
     * least [MIN_KEEP_FRACTION] of the original area in both axes.
     */
    private fun trimLightMargins(source: Bitmap): Bitmap {
        // getPixels() throws on a HARDWARE bitmap and MediaProvider may return one, so
        // the pixel reads below would abort the coroutine. The copy costs one readback
        // per album (the result is cached) and keeps every later consumer legal too.
        val image = source.softwareCopy()
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
     * Returns a bitmap whose pixels are readable from Java.
     *
     * `Bitmap.Config.HARDWARE` (API 26+) keeps pixels in graphics memory: `getPixels`
     * throws `IllegalStateException` on it. MediaProvider may hand one back from
     * `loadThumbnail`, and nothing in the type system says which it chose.
     */
    private fun Bitmap.softwareCopy(): Bitmap =
        if (config == Bitmap.Config.HARDWARE) {
            copy(Bitmap.Config.ARGB_8888, false) ?: this
        } else {
            this
        }

    private fun albumArtUri(albumId: Long): Uri =
        ContentUris.withAppendedId(
            "content://media/external/audio/albumart".toUri(),
            albumId,
        )

    companion object {
        /** 52 dp rows at 3.5x density need 182 px; 192 leaves a small margin. */
        private const val LIST_THUMB_SIZE = 192
        /** The player renders the cover full-width, so it keeps the larger source. */
        private const val PLAYER_THUMB_SIZE = 512
        private const val BACKDROP_SIZE = 96
        private const val CACHE_BYTES = 24 * 1024 * 1024
        private const val PLAYER_CACHE_BYTES = 6 * 1024 * 1024
        private const val LIGHT_THRESHOLD = 236
        private const val MAX_TRIM_FRACTION = 0.22f
        private const val MIN_KEEP_FRACTION = 0.45f
    }
}
