package com.milan.music.play

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.annotation.OptIn
import androidx.core.graphics.scale
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.milan.music.data.media.BitmapBudget
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Resolves session artwork for the media notification, lock screen, Bluetooth
 * metadata and Android Auto.
 *
 * Media3's default loader opens the artwork URI through a [androidx.media3.datasource.DataSource],
 * which cannot serve embedded ID3/FLAC pictures. The URI published by
 * [PlayerController] is the audio item itself, so resolution goes through
 * `ContentResolver.loadThumbnail` (API 29+) — the same call
 * [com.milan.music.data.media.ArtworkRepository] uses for the in-app UI. On API 26-28
 * the provider has no thumbnail pipeline, so the embedded picture is read directly
 * with [MediaMetadataRetriever].
 *
 * Work runs on a single background thread: decodes are serialised (they are rare and
 * short) and the caller — the media session — is never blocked.
 */
@OptIn(UnstableApi::class)
class ArtworkBitmapLoader(private val context: Context) : BitmapLoader {

    /** Heap-derived total; this loader owns the notification's slice of it. */
    private val budget = BitmapBudget.totalBytes(context)

    private val executor: ListeningExecutorService = MoreExecutors.listeningDecorator(
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "milan-artwork").apply { isDaemon = true }
        },
    )

    /**
     * The notification re-resolves artwork on every metadata refresh, and switching
     * between tracks of one album repeats the same decode. Bitmaps are counted by
     * their real allocation, not by `w * h * 4`: `loadThumbnail` may return a
     * HARDWARE bitmap whose footprint that formula does not describe.
     *
     * Sized from the same heap-derived budget as the in-app caches (3/16 of the
     * total): 512 px covers are 1 MB each, so a fixed 8 MB ceiling held eight of them
     * regardless of whether the device could afford it.
     */
    private val cache = object : LruCache<Uri, Bitmap>(BitmapBudget.slice(budget, 3, 16)) {
        override fun sizeOf(key: Uri, value: Bitmap): Int = value.allocationByteCount
    }

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        executor.submit(
            Callable {
                // Same bounds pass as the API 26-28 path: MediaItems here carry an
                // artwork *URI*, never inline data, so this is only a safety net —
                // and it must not be the one place that allocates a full-size cover.
                decodeScaled(data)
                    ?: throw IOException("Unsupported artwork data (${data.size} bytes)")
            },
        )

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        cache.get(uri)?.let { return Futures.immediateFuture(it) }
        return executor.submit(
            Callable {
                val bitmap = resolve(uri) ?: throw IOException("No embedded artwork for $uri")
                cache.put(uri, bitmap)
                bitmap
            },
        )
    }

    fun release() {
        cache.evictAll()
        executor.shutdown()
    }

    /**
     * Shrinks the notification-artwork cache under memory pressure.
     *
     * The media session caches the resolved bitmap for the current item itself, and
     * anything evicted here is one local decode away from coming back, so trimming
     * never costs playback — it only stops a few 512 px covers from being pinned in
     * a backgrounded process.
     *
     * `RUNNING_LOW` is deprecated on API 34+ (never sent any more) and kept only for
     * API 26-33, where the service can be trimmed while still in the foreground.
     */
    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ->
                cache.trimToSize(cache.maxSize() / 4)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
                cache.trimToSize(cache.maxSize() / 8)
        }
    }

    private fun resolve(uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(THUMB_SIZE, THUMB_SIZE), null)
        } else {
            embeddedPicture(uri)
        }
    }.getOrNull()

    private fun embeddedPicture(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture ?: return null
            decodeScaled(bytes)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Decodes embedded art straight to (roughly) [THUMB_SIZE] instead of native size.
     *
     * `decodeByteArray` without `inSampleSize` allocates the whole picture first: a
     * 3000×3000 cover is 36 MB for an instant, which on a 2 GB device is enough for
     * the low-memory killer to take the process before any catch clause runs. The
     * bounds pass costs one header read and caps the allocation at ~4 MB; [scaleDown]
     * then trims the remainder to an exact 512 px.
     */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(longest)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let(::scaleDown)
    }

    /** Largest power-of-two divisor that keeps the longest edge at or above [THUMB_SIZE]. */
    private fun sampleSizeFor(longest: Int): Int {
        var sample = 1
        while (longest / (sample * 2) >= THUMB_SIZE) sample *= 2
        return sample
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= THUMB_SIZE) return bitmap
        val ratio = THUMB_SIZE.toFloat() / longest
        return bitmap.scale(
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
        )
    }

    private companion object {
        /** Notification and lock-screen artwork is shown at most ~512 px wide. */
        const val THUMB_SIZE = 512
    }
}
