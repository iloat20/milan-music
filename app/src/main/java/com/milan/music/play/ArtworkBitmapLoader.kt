package com.milan.music.play

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

    private val executor: ListeningExecutorService = MoreExecutors.listeningDecorator(
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "milan-artwork").apply { isDaemon = true }
        },
    )

    /**
     * The notification re-resolves artwork on every metadata refresh, and switching
     * between tracks of one album repeats the same decode. Bitmaps are counted by
     * byte size, not entry count, so a handful of large covers cannot exhaust it.
     */
    private val cache = object : LruCache<Uri, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Uri, value: Bitmap): Int = value.byteCount
    }

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        executor.submit(
            Callable {
                BitmapFactory.decodeByteArray(data, 0, data.size)
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

    private fun resolve(uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(THUMB_SIZE, THUMB_SIZE), null)
        } else {
            embeddedPicture(uri)?.let(::scaleDown)
        }
    }.getOrNull()

    private fun embeddedPicture(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            runCatching { retriever.release() }
        }
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
        const val CACHE_BYTES = 8 * 1024 * 1024
    }
}
