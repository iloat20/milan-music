package com.milan.music.data.model

import android.net.Uri

/**
 * A single audio track indexed by the system MediaStore.
 */
data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val sizeBytes: Long,
    val year: Int,
    val trackNumber: Int,
    val dateAdded: Long,
    /**
     * MediaStore `DATE_MODIFIED`, carried as the library-scan watermark.
     *
     * Defaults to 0 so a cache written before this field existed decodes without
     * error; such a snapshot simply fails the watermark comparison once and gets
     * rewritten on the next scan.
     */
    val dateModified: Long = 0L,
) {
    val displayTitle: String
        get() = title.ifBlank { "未知曲目" }

    val displayArtist: String
        get() = artist.takeIf { it.isNotBlank() && it != UNKNOWN } ?: "未知艺人"

    val displayAlbum: String
        get() = album.takeIf { it.isNotBlank() && it != UNKNOWN } ?: "未知专辑"

    companion object {
        private const val UNKNOWN = "<unknown>"
    }
}

enum class SortMode {
    TITLE,
    ARTIST,
    ALBUM,
    DATE_ADDED,
    DURATION,
    ;

    val label: String
        get() = when (this) {
            TITLE -> "标题"
            ARTIST -> "艺人"
            ALBUM -> "专辑"
            DATE_ADDED -> "最近添加"
            DURATION -> "时长"
        }
}
