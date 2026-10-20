package com.milan.music.data.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.milan.music.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the device audio library through [MediaStore].
 *
 * Only indexed audio with a real duration is returned; notifications, ringtones
 * and other short assets are filtered out because [MediaStore.Audio.Media.IS_MUSIC]
 * is unreliable on several OEM ROMs.
 */
class MediaStoreRepository(private val context: Context) {

    private val collection: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    private val projection = arrayOf(
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

    private val selection =
        "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= $MIN_DURATION_MS"

    suspend fun loadTracks(): List<Track> = withContext(Dispatchers.IO) {
        val tracks = ArrayList<Track>(256)
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        try {
            context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val yearCol = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
                val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val dateCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val modifiedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.longAt(idCol)
                    if (id <= 0L) continue
                    tracks += Track(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        title = cursor.stringAt(titleCol),
                        artist = cursor.stringAt(artistCol),
                        album = cursor.stringAt(albumCol),
                        albumId = cursor.longAt(albumIdCol),
                        duration = cursor.longAt(durationCol),
                        sizeBytes = cursor.longAt(sizeCol),
                        year = cursor.intAt(yearCol),
                        trackNumber = cursor.intAt(trackCol),
                        dateAdded = cursor.longAt(dateCol),
                        dateModified = cursor.longAt(modifiedCol),
                    )
                }
            }
        } catch (e: SecurityException) {
            // Permission revoked between the check and the query.
            Log.w(TAG, "audio query rejected: permission revoked", e)
        } catch (e: Exception) {
            // The cursor runs against a remote provider, and OEM implementations throw
            // more than the two cases that used to be listed here:
            // IllegalArgumentException for a rejected projection, SQLiteException for a
            // corrupted or rebuilding index, DeadObjectException when MediaProvider is
            // restarted mid-cursor. None of them is actionable at this level and none
            // should reach the caller as a crash on the launch path — the caller treats
            // an empty result as "index unavailable" and keeps the cached snapshot.
            Log.w(TAG, "audio query failed; returning what was read", e)
        }
        tracks
    }

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

