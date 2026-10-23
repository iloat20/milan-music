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
    companion object {
        /**
         * MediaStore 的哨兵值：行扫描到了，但这个字段读不出来。
         *
         * 它只表示"缺失"，不表示"该怎么显示" —— 兜底文案属于界面，见
         * `ui/text/TrackText.kt`。放在这里是因为筛选与排序也要认这个值。
         */
        const val UNKNOWN = "<unknown>"
    }
}

enum class SortMode {
    TITLE,
    ARTIST,
    ALBUM,
    DATE_ADDED,
    DURATION,
}
