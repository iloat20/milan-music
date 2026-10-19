package com.milan.music.data.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
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
                    )
                }
            }
        } catch (_: SecurityException) {
            // Permission revoked between the check and the query.
        } catch (_: IllegalArgumentException) {
            // Some OEM providers reject the projection; return what we have.
        }
        tracks
    }

    private fun Cursor.stringAt(index: Int): String =
        if (index >= 0 && !isNull(index)) getString(index).orEmpty() else ""

    private fun Cursor.longAt(index: Int): Long =
        if (index >= 0 && !isNull(index)) getLong(index) else 0L

    private fun Cursor.intAt(index: Int): Int =
        if (index >= 0 && !isNull(index)) getInt(index) else 0

    companion object {
        private const val MIN_DURATION_MS = 60_000L
    }
}
