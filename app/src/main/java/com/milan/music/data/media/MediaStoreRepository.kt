package com.milan.music.data.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.milan.music.data.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Reads the device audio library through [MediaStore].
 *
 * Only indexed audio with a real duration is returned; notifications, ringtones
 * and other short assets are filtered out because [MediaStore.Audio.Media.IS_MUSIC]
 * is unreliable on several OEM ROMs.
 */
class MediaStoreRepository(private val context: Context) {

    private val collection: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    /** Columns every supported provider exposes. */
    private val baseProjection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.YEAR,
        MediaStore.Audio.Media.TRACK,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.DATE_MODIFIED,
    )

    /** `ALBUM_ARTIST` arrived with the R media columns, and not every OEM backport kept it. */
    private val richProjection = baseProjection + MediaStore.Audio.Media.ALBUM_ARTIST

    private fun projection(includeAlbumArtist: Boolean): Array<String> =
        if (includeAlbumArtist) richProjection else baseProjection

    private val selection =
        "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= $MIN_DURATION_MS"

    /**
     * Reads the indexed audio library, telling "empty" apart from "interrupted".
     *
     * [TrackScan.complete] is the part the caller must honour. The previous shape
     * returned a plain list, so a cursor that threw halfway looked like a successful
     * scan of a shorter library: the partial rows replaced the cached snapshot and
     * were stamped with the current generation, hiding the missing tracks until the
     * next index change. [complete] is only true after the cursor has been walked to
     * the end, so a partial read can never be persisted as authoritative.
     *
     * Failures still do not crash the launch path: the rows read so far are returned
     * for diagnostics, with `complete = false`.
     */
    internal suspend fun scanTracks(mode: ScanMode = ScanMode.INTERACTIVE): TrackScan =
        withContext(scanDispatcher) {
            // A dedicated thread, not `Dispatchers.IO`: the priority below belongs to
            // this walk. On a shared pool it would follow the thread into whatever runs
            // next on it (artwork decode, snapshot writes) and outlive the scan.
            val restore = Process.getThreadPriority(Process.myTid())
            Process.setThreadPriority(scanThreadPriority(mode))
            val startedAt = SystemClock.elapsedRealtime()
            val scan = try {
                readAllIndex()
            } finally {
                // Back to this thread's own priority, so the next walk — which may be an
                // interactive one — does not inherit the background priority of the
                // previous reconcile.
                Process.setThreadPriority(restore)
            }
            // The only evidence that separates "the reconcile cost nothing" from
            // "the reconcile stalled behind playback" on a real device.
            Log.i(
                TAG,
                "scan mode=$mode rows=${scan.tracks.size} complete=${scan.complete} " +
                    "ms=${SystemClock.elapsedRealtime() - startedAt}",
            )
            scan
        }

    /** The cursor walk itself. Runs on the scan thread; see [scanTracks]. */
    private fun readAllIndex(): TrackScan {
        val tracks = ArrayList<Track>(256)
        var complete = false
        var includeAlbumArtist = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        try {
            val cursor = try {
                query(includeAlbumArtist)
            } catch (e: IllegalArgumentException) {
                // Some OEM providers reject the optional column outright. Retry only
                // before any row has been read: retrying after a partial walk could
                // duplicate rows, and the outer catch already marks that case
                // incomplete.
                if (!includeAlbumArtist) throw e
                Log.w(TAG, "provider rejected ALBUM_ARTIST; retrying without it", e)
                includeAlbumArtist = false
                query(false)
            }
            if (cursor == null) {
                Log.w(TAG, "audio provider returned no cursor after ${tracks.size} rows")
            } else {
                cursor.use { reader ->
                    val idCol = reader.getColumnIndex(MediaStore.Audio.Media._ID)
                    val titleCol = reader.getColumnIndex(MediaStore.Audio.Media.TITLE)
                    val artistCol = reader.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                    val albumArtistCol = if (includeAlbumArtist) {
                        reader.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
                    } else {
                        -1
                    }
                    val albumCol = reader.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = reader.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                    val durationCol = reader.getColumnIndex(MediaStore.Audio.Media.DURATION)
                    val sizeCol = reader.getColumnIndex(MediaStore.Audio.Media.SIZE)
                    val yearCol = reader.getColumnIndex(MediaStore.Audio.Media.YEAR)
                    val trackCol = reader.getColumnIndex(MediaStore.Audio.Media.TRACK)
                    val dateCol = reader.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                    val modifiedCol = reader.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)

                    while (reader.moveToNext()) {
                        val id = reader.longAt(idCol)
                        if (id <= 0L) continue
                        val artist = reader.stringAt(artistCol)
                        tracks += Track(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            title = reader.stringAt(titleCol),
                            artist = artist,
                            album = reader.stringAt(albumCol),
                            albumId = reader.longAt(albumIdCol),
                            duration = reader.longAt(durationCol),
                            sizeBytes = reader.longAt(sizeCol),
                            year = reader.intAt(yearCol),
                            trackNumber = reader.intAt(trackCol),
                            dateAdded = reader.longAt(dateCol),
                            dateModified = reader.longAt(modifiedCol),
                            albumArtist = reader.stringAt(albumArtistCol).ifEmpty { artist },
                        )
                    }
                }
                complete = true
            }
        } catch (e: SecurityException) {
            // Permission revoked between the check and the query.
            Log.w(TAG, "audio query rejected: permission revoked", e)
        } catch (e: CancellationException) {
            // Cancellation is not a provider failure, and this catch must never turn it
            // into one: a returned list could otherwise be written to the cache, so a
            // swallowed cancellation would persist a half-read library that the next
            // launch trusts because it is stamped as current.
            throw e
        } catch (e: Exception) {
            // The cursor runs against a remote provider, and OEM implementations throw
            // more than the two cases that used to be listed here:
            // IllegalArgumentException for a rejected projection, SQLiteException for a
            // corrupted or rebuilding index, DeadObjectException when MediaProvider is
            // restarted mid-cursor. None of them is actionable at this level; the
            // caller keeps the cached snapshot because `complete` stays false.
            Log.w(TAG, "audio query failed after ${tracks.size} rows", e)
        }
        if (!complete) {
            Log.w(
                TAG,
                "audio scan incomplete after ${tracks.size} rows; " +
                    "the cached snapshot must not be replaced",
            )
        }
        return TrackScan(tracks = tracks, complete = complete)
    }

    private fun query(includeAlbumArtist: Boolean): Cursor? =
        context.contentResolver.query(
            collection,
            projection(includeAlbumArtist),
            selection,
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )

    /**
     * Watermark of the audio index, taken without reading a single track row.
     *
     * On API 30+ this is [MediaStore.getGeneration]: MediaProvider bumps that
     * counter on every insert, update and delete, so comparing two readings is a
     * single binder call and covers re-tagging (which a row count would miss).
     *
     * Older releases used an aggregate projection — `COUNT(*)`, `MAX(DATE_MODIFIED)`.
     * That is kept only as the API 26–29 path: the Android 17 MediaProvider rejects
     * `COUNT(*) AS track_count` with `IllegalArgumentException: Invalid column`, and
     * because the failure was swallowed the caller silently fell back to a full
     * rescan on every launch. Aggregates over MediaStore are not portable, so they
     * are never used where a supported alternative exists.
     *
     * Never throws: an empty stamp simply means "unknown", which the caller treats
     * as "changed" and answers with a full scan.
     */
    suspend fun stamp(): IndexStamp = withContext(Dispatchers.IO) {
        generationOrNull()?.let { return@withContext IndexStamp(generation = it) }

        runCatching {
            val columns = arrayOf(
                "COUNT(*) AS track_count",
                "MAX(${MediaStore.Audio.Media.DATE_MODIFIED}) AS newest_modified",
            )
            context.contentResolver.query(collection, columns, selection, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    IndexStamp(
                        count = cursor.getLong(0),
                        newestModified = if (cursor.isNull(1)) 0L else cursor.getLong(1),
                    )
                }
            }
        }.getOrNull() ?: IndexStamp()
    }

    /**
     * Generation counter of the shared media volume, or null when the platform does
     * not expose one (API < 30) or the provider refuses the volume name.
     *
     * [MediaStore.VOLUME_EXTERNAL] spans every mounted volume and matches the
     * collection queried above, but not every provider implements it; the primary
     * volume is the fallback. The order is fixed so that repeated reads hit the same
     * counter — mixing the two would compare unrelated sequences.
     */
    private fun generationOrNull(): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        for (volume in volumeCandidates()) {
            runCatching { MediaStore.getGeneration(context, volume) }
                .getOrNull()
                ?.let { return it }
        }
        return null
    }

    private fun Cursor.stringAt(index: Int): String =
        if (index >= 0 && !isNull(index)) getString(index).orEmpty() else ""

    private fun Cursor.longAt(index: Int): Long =
        if (index >= 0 && !isNull(index)) getLong(index) else 0L

    private fun Cursor.intAt(index: Int): Int =
        if (index >= 0 && !isNull(index)) getInt(index) else 0

    companion object {
        private const val TAG = "MediaStoreRepo"
        private const val MIN_DURATION_MS = 60_000L

        /**
         * Single thread that owns every library cursor walk.
         *
         * It exists so that [scanThreadPriority] can be scoped to the scan: setting it
         * on a `Dispatchers.IO` thread would apply to whatever that thread ran next. A
         * daemon thread is correct here — nothing needs to be flushed on exit, and the
         * process should not be kept alive for a reconcile.
         */
        private val scanDispatcher: CoroutineDispatcher =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "library-scan").apply { isDaemon = true }
            }.asCoroutineDispatcher()

        /**
         * Volume names probed for a generation counter, in a fixed order so that
         * repeated reads always hit the same counter.
         *
         * `VOLUME_EXTERNAL` and `VOLUME_EXTERNAL_PRIMARY` are API 29 constants while the
         * only caller runs on API 30+. The annotation states that contract directly;
         * without it lint re-derives the API level from the `Build.VERSION` check one
         * frame up and reports both fields as `InlinedApi` warnings.
         */
        @RequiresApi(Build.VERSION_CODES.Q)
        private fun volumeCandidates(): List<String> = listOf(
            MediaStore.VOLUME_EXTERNAL,
            MediaStore.VOLUME_EXTERNAL_PRIMARY,
        )
    }
}

/**
 * One pass over the audio index.
 *
 * [complete] is false when the provider failed before the cursor reached its end. The
 * rows in [tracks] are then a prefix, not a library: callers must keep the cached
 * snapshot rather than publishing or stamping this result.
 */
internal data class TrackScan(
    val tracks: List<Track>,
    val complete: Boolean,
)

/**
 * Identity of a MediaStore snapshot.
 *
 * Either [generation] is set (API 30+, authoritative) or the legacy pair is
 * ([count] + [newestModified]); a default-constructed stamp means "unknown" and
 * never matches anything.
 */
data class IndexStamp(
    val generation: Long? = null,
    val count: Long = -1L,
    val newestModified: Long = -1L,
) {

    /**
     * Whether [other] describes the same index state as this stamp.
     *
     * Stamps taken from different sources are never comparable: a cached stamp
     * written before this field existed, or read on a device where the generation
     * counter is unavailable, forces one rescan and then heals.
     */
    fun sameAs(other: IndexStamp?): Boolean {
        if (other == null) return false
        if (generation != null || other.generation != null) {
            return generation != null && generation == other.generation
        }
        return count >= 0 && count == other.count && newestModified == other.newestModified
    }

    fun describe(): String = if (generation != null) {
        "generation=$generation"
    } else {
        "count=$count newest=$newestModified"
    }
}

