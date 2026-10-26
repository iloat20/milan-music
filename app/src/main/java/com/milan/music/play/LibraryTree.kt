package com.milan.music.play

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.milan.music.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Base64

/**
 * Media ids for the browsable library tree.
 *
 * Playable items keep the bare numeric track id, exactly like
 * [PlayerController.playQueue] writes it: [PlaybackTrackIndex] is keyed by that string,
 * and a prefix here would silently drop title/artist/artwork from the notification.
 * Container ids use the `lib:` namespace, which can never collide with a numeric id.
 */
internal object LibraryIds {

    const val ROOT = "lib:root"
    const val SONGS = "lib:songs"
    const val ALBUMS = "lib:albums"
    const val ARTISTS = "lib:artists"

    private const val ALBUM_PREFIX = "lib:album:"
    private const val ARTIST_PREFIX = "lib:artist:"

    fun album(albumId: Long): String = ALBUM_PREFIX + albumId

    /**
     * Artist names are user data: they can contain `:`, `/`, emoji and anything else a
     * tag holds. Base64-url keeps the id opaque and round-trips without inventing an
     * escaping scheme; it is available on API 26, the app's minSdk.
     */
    fun artist(name: String): String =
        ARTIST_PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(name.toByteArray(Charsets.UTF_8))

    fun albumId(id: String): Long? = id
        .takeIf { it.startsWith(ALBUM_PREFIX) }
        ?.removePrefix(ALBUM_PREFIX)
        ?.toLongOrNull()

    fun artistName(id: String): String? = id
        .takeIf { it.startsWith(ARTIST_PREFIX) }
        ?.removePrefix(ARTIST_PREFIX)
        ?.let { encoded ->
            runCatching { String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8) }
                .getOrNull()
        }
}

/**
 * Half-open slice of [total] items for a zero-based [page].
 *
 * `page` starts at 0 in Media3's library callbacks, not 1. The multiplication is done
 * in `Long`: `page * pageSize` overflows to a negative number near `Int.MAX_VALUE`, and
 * the resulting `until` either throws or silently returns the wrong window.
 */
internal fun pageSlice(total: Int, page: Int, pageSize: Int): IntRange {
    if (total <= 0 || pageSize <= 0 || page < 0) return IntRange.EMPTY
    val from = page.toLong() * pageSize
    if (from >= total.toLong()) return IntRange.EMPTY
    val toExclusive = (from + pageSize).coerceAtMost(total.toLong())
    return from.toInt() until toExclusive.toInt()
}

/**
 * The `root → songs / albums / artists → tracks` tree served by
 * [androidx.media3.session.MediaLibraryService].
 *
 * The tree is grouped once per library revision and kept in memory: browsing is a
 * low-frequency operation, but `onGetChildren` is called for every page, and decoding
 * the library cache on each call would turn a scroll in Android Auto into repeated
 * megabyte JSON parses. `MediaItem` objects are built per page and never retained, so
 * the resident cost is the `Track` objects plus the grouping indices, not 5 000
 * metadata bundles.
 */
internal class LibraryTree(
    private val loadTracks: suspend () -> List<Track>,
    private val revision: () -> Long,
    private val labels: Labels,
) {

    data class Labels(
        val root: String,
        val songs: String,
        val albums: String,
        val artists: String,
        val untitled: String,
        val unknownArtist: String,
        val unknownAlbum: String,
    )

    private data class AlbumEntry(val id: Long, val title: String, val artist: String)

    private class Snapshot(
        val byId: Map<Long, Track>,
        val songs: List<Track>,
        val albums: List<AlbumEntry>,
        val artists: List<String>,
        val albumTracks: Map<Long, List<Track>>,
        val artistTracks: Map<String, List<Track>>,
    )

    private val mutex = Mutex()

    @Volatile
    private var cached: Snapshot? = null

    @Volatile
    private var cachedRevision = Long.MIN_VALUE

    /** The root is static and must be answerable before the library is read. */
    fun rootItem(): MediaItem = containerItem(LibraryIds.ROOT, labels.root)

    /**
     * A track the tree has already grouped, without reading or rebuilding anything.
     *
     * The library callbacks run on the session's coroutine scope, but
     * `onAddMediaItems` is a plain callback. A browser that just read the children has
     * populated [cached]; a miss (first call, revision changed) is fine and simply
     * leaves the item's own metadata in place.
     */
    fun cachedTrack(mediaId: String): Track? {
        val snapshot = cached ?: return null
        if (cachedRevision != revision()) return null
        return mediaId.toLongOrNull()?.let(snapshot.byId::get)
    }

    /**
     * One page of [parentId]'s children, or null when the parent is not part of this
     * tree (the caller maps that to `RESULT_ERROR_BAD_VALUE`).
     */
    suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem>? {
        // The root is three static containers. Reading the library to answer it made
        // the first browse pay a megabyte of JSON for a screen that shows no tracks.
        if (parentId == LibraryIds.ROOT) {
            val containers = listOf(
                containerItem(LibraryIds.SONGS, labels.songs),
                containerItem(LibraryIds.ALBUMS, labels.albums),
                containerItem(LibraryIds.ARTISTS, labels.artists),
            )
            return containers.slice(pageSlice(containers.size, page, pageSize))
        }

        val snapshot = snapshot()
        return when (parentId) {
            LibraryIds.SONGS -> snapshot.songs
                .slice(pageSlice(snapshot.songs.size, page, pageSize))
                .map { it.toMediaItem() }

            LibraryIds.ALBUMS -> snapshot.albums
                .slice(pageSlice(snapshot.albums.size, page, pageSize))
                .map { containerItem(LibraryIds.album(it.id), it.title, it.artist) }

            LibraryIds.ARTISTS -> snapshot.artists
                .slice(pageSlice(snapshot.artists.size, page, pageSize))
                .map { containerItem(LibraryIds.artist(it), it) }

            else -> albumChildren(snapshot, parentId, page, pageSize)
                ?: artistChildren(snapshot, parentId, page, pageSize)
        }
    }

    /** Resolves a container or a track by id, used by `onGetItem`. */
    suspend fun item(id: String): MediaItem? {
        // Same reasoning as children(): static containers must not read the library.
        when (id) {
            LibraryIds.ROOT -> return rootItem()
            LibraryIds.SONGS -> return containerItem(LibraryIds.SONGS, labels.songs)
            LibraryIds.ALBUMS -> return containerItem(LibraryIds.ALBUMS, labels.albums)
            LibraryIds.ARTISTS -> return containerItem(LibraryIds.ARTISTS, labels.artists)
        }

        val snapshot = snapshot()
        val albumId = LibraryIds.albumId(id)
        if (albumId != null) {
            return snapshot.albums.firstOrNull { it.id == albumId }
                ?.let { containerItem(id, it.title, it.artist) }
        }
        val artistName = LibraryIds.artistName(id)
        if (artistName != null) {
            return snapshot.artists.firstOrNull { it == artistName }
                ?.let { containerItem(id, it) }
        }
        return id.toLongOrNull()?.let { snapshot.byId[it] }?.toMediaItem()
    }

    private fun albumChildren(
        snapshot: Snapshot,
        parentId: String,
        page: Int,
        pageSize: Int,
    ): List<MediaItem>? {
        val albumId = LibraryIds.albumId(parentId) ?: return null
        val tracks = snapshot.albumTracks[albumId] ?: return null
        return tracks.slice(pageSlice(tracks.size, page, pageSize)).map { it.toMediaItem() }
    }

    private fun artistChildren(
        snapshot: Snapshot,
        parentId: String,
        page: Int,
        pageSize: Int,
    ): List<MediaItem>? {
        val artistName = LibraryIds.artistName(parentId) ?: return null
        val tracks = snapshot.artistTracks[artistName] ?: return null
        return tracks.slice(pageSlice(tracks.size, page, pageSize)).map { it.toMediaItem() }
    }

    private suspend fun snapshot(): Snapshot {
        val currentRevision = revision()
        cached?.let { if (cachedRevision == currentRevision) return it }
        return mutex.withLock {
            val again = cached
            if (again != null && cachedRevision == currentRevision) {
                return@withLock again
            }
            // The revision is read *before* the rows, mirroring LibraryViewModel's
            // stamp-before-scan rule: a save landing in between labels the rows with
            // an older revision, which costs one extra rebuild instead of serving
            // stale rows under a current label.
            val built = withContext(Dispatchers.Default) { build(loadTracks()) }
            cached = built
            cachedRevision = currentRevision
            built
        }
    }

    private fun build(tracks: List<Track>): Snapshot {
        val byId = tracks.associateBy { it.id }

        val songs = tracks.sortedWith(
            compareBy({ it.titleLabel().lowercase() }, { it.artistLabel().lowercase() }, { it.id }),
        )

        // `albumId = 0` is MediaStore's "no album tag" value. Grouping on it alone put
        // every untagged track in one container with one cover; split those by their
        // album/artist text instead. The synthetic id is negative so it can never
        // collide with a real MediaStore album id, and keys are assigned in sorted
        // order so a rescan with a different row order keeps the same id.
        val albumKey: (Track) -> String = { track ->
            if (track.albumId > 0L) {
                "id:${track.albumId}"
            } else {
                "meta:${track.albumLabel()}\u0000${track.artistLabel()}"
            }
        }
        val groupedByAlbum = tracks.groupBy(albumKey)
        val syntheticIds = HashSet<Long>()
        val albumIds = HashMap<String, Long>(groupedByAlbum.size)
        for (key in groupedByAlbum.keys.sorted()) {
            albumIds[key] = if (key.startsWith("id:")) {
                key.removePrefix("id:").toLong()
            } else {
                var candidate = -((key.hashCode().toLong() and 0x7FFF_FFFFL) + 1L)
                while (!syntheticIds.add(candidate)) candidate--
                candidate
            }
        }
        val albumTracks = groupedByAlbum.entries.associate { (key, list) ->
            albumIds.getValue(key) to list.sortedWith(
                compareBy({ it.trackNumber }, { it.titleLabel().lowercase() }, { it.id }),
            )
        }
        val albums = albumTracks.map { (albumId, list) ->
            AlbumEntry(
                id = albumId,
                title = list.first().albumLabel(),
                artist = list.first().artistLabel(),
            )
        }.sortedWith(compareBy({ it.title.lowercase() }, { it.id }))

        val artistTracks = tracks
            .groupBy { it.artistLabel() }
            .mapValues { (_, list) ->
                list.sortedWith(
                    compareBy(
                        { it.albumLabel().lowercase() },
                        { it.trackNumber },
                        { it.titleLabel().lowercase() },
                        { it.id },
                    ),
                )
            }
        val artists = artistTracks.keys.sortedBy { it.lowercase() }

        return Snapshot(
            byId = byId,
            songs = songs,
            albums = albums,
            artists = artists,
            albumTracks = albumTracks,
            artistTracks = artistTracks,
        )
    }

    private fun Track.titleLabel(): String = title.ifBlank { labels.untitled }

    private fun Track.artistLabel(): String =
        artist.takeIf { it.isKnownTag() } ?: labels.unknownArtist

    private fun Track.albumLabel(): String =
        album.takeIf { it.isKnownTag() } ?: labels.unknownAlbum

    private fun String.isKnownTag(): Boolean = isNotBlank() && this != Track.UNKNOWN

    private fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(titleLabel())
                .setArtist(artistLabel())
                .setAlbumArtist(albumArtist.takeIf { it.isKnownTag() } ?: artistLabel())
                .setAlbumTitle(albumLabel())
                .setDurationMs(duration)
                .setTrackNumber(trackNumber.takeIf { it > 0 })
                .setReleaseYear(year.takeIf { it > 0 })
                .setArtworkUri(uri)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()

    private fun containerItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(subtitle)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build(),
        )
        .build()
}
