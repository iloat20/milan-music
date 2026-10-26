package com.milan.music.play

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.milan.music.data.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * What has to survive process death for playback to resume.
 *
 * Only ids travel for the queue; [current] carries the metadata of the item that was
 * playing so the system's "recent media" entry can be built without reading the whole
 * library cache. The queue is stored separately from the position: the position is
 * rewritten every fifteen seconds, and putting a 5 000-id payload in the same DataStore
 * would rewrite tens of kilobytes on every tick.
 */
@Serializable
data class PlaybackSnapshot(
    val trackIds: List<Long> = emptyList(),
    /** Index into the unshuffled queue, matching `Player.currentMediaItemIndex`. */
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val shuffleModeEnabled: Boolean = true,
    val repeatMode: Int = Player.REPEAT_MODE_ALL,
    val wasPlaying: Boolean = false,
    val current: SnapshotTrack? = null,
    val updatedAtMs: Long = 0L,
)

/** The subset of [Track] needed to rebuild a `MediaItem` without the library cache. */
@Serializable
data class SnapshotTrack(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    /**
     * Fields added after the first snapshot format shipped.
     *
     * Defaults keep a 1.0.64 snapshot readable: the restored item then simply has no
     * album artist, track number or year, which is exactly what it showed before.
     */
    val albumArtist: String = "",
    val year: Int = 0,
    val trackNumber: Int = 0,
)

/** A queue that [PlaybackSnapshotStore.restore] successfully mapped onto the library. */
data class RestoredQueue(
    val tracks: List<Track>,
    val startIndex: Int,
    val positionMs: Long,
    val shuffleModeEnabled: Boolean,
    val repeatMode: Int,
    val wasPlaying: Boolean,
)

internal data class RestoredIndex(
    val trackIds: List<Long>,
    val startIndex: Int,
)

/**
 * Maps a persisted queue onto the ids that still exist, without ever coercing the old
 * index onto the new list.
 *
 * A deleted track shortens the queue, and a rescan can reorder rows; the only stable
 * anchor is the current track's id. Returning null means "this snapshot cannot be
 * resumed", which is deliberately distinct from "resume at index 0".
 */
internal fun mapRestoredQueue(
    trackIds: List<Long>,
    currentIndex: Int,
    availableIds: Set<Long>,
): RestoredIndex? {
    if (trackIds.isEmpty()) return null
    val currentId = trackIds.getOrNull(currentIndex) ?: return null
    val restored = trackIds.filter { it in availableIds }
    if (restored.isEmpty()) return null
    val newIndex = restored.indexOf(currentId)
    if (newIndex < 0) return null
    return RestoredIndex(trackIds = restored, startIndex = newIndex)
}

internal fun Int.validRepeatMode(): Int = when (this) {
    Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL -> this
    else -> Player.REPEAT_MODE_ALL
}

/**
 * Both stores replace a corrupt file instead of throwing.
 *
 * A snapshot is an optimization: losing it costs one random start, while an uncaught
 * `CorruptionException` on the main-thread restore path would crash the app on every
 * launch until the user cleared data.
 */
private val Context.playbackQueueStore: DataStore<Preferences> by preferencesDataStore(
    name = "milan_playback_queue",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

private val Context.playbackPositionStore: DataStore<Preferences> by preferencesDataStore(
    name = "milan_playback_position",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Persists the queue, the current item and the playback position across process death.
 *
 * Kept separate from [com.milan.music.data.media.LibraryCacheStore] on purpose: that
 * store re-encodes the whole library (~1 MB at 5 000 tracks) on every write, while the
 * position is written every fifteen seconds. Two small DataStores keep the frequent
 * write cheap and let the queue snapshot change only when the queue actually changes.
 */
class PlaybackSnapshotStore(context: Context) {

    private val context = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    private val idsKey = stringPreferencesKey("track_ids")
    private val currentIndexKey = intPreferencesKey("current_index")
    private val currentTrackKey = stringPreferencesKey("current_track")
    private val shuffleKey = booleanPreferencesKey("shuffle")
    private val repeatKey = intPreferencesKey("repeat_mode")
    private val updatedAtKey = longPreferencesKey("updated_at")
    private val positionKey = longPreferencesKey("position_ms")
    private val positionUpdatedAtKey = longPreferencesKey("position_updated_at")
    private val wasPlayingKey = booleanPreferencesKey("was_playing")

    suspend fun load(): PlaybackSnapshot? = withContext(Dispatchers.IO) {
        val queue = queuePreferences()
        val rawIds = queue[idsKey] ?: return@withContext null
        val ids = rawIds.split(',').mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return@withContext null
        val position = positionPreferences()
        val queueUpdatedAt = queue[updatedAtKey] ?: 0L
        // The two stores are written in sequence, not atomically. A newer position is
        // the normal ticker case; an older one means the queue edit landed and the
        // position edit did not — the index belongs to the new track while the position
        // belongs to the track it replaced. Starting the new track at that timestamp is
        // worse than starting at zero. A snapshot written before this key existed has
        // no timestamp: treat it as in sync so upgrading does not drop the position.
        val positionUpdatedAt = position[positionUpdatedAtKey] ?: queueUpdatedAt
        val positionIsCurrent = positionUpdatedAt >= queueUpdatedAt
        PlaybackSnapshot(
            trackIds = ids,
            currentIndex = queue[currentIndexKey] ?: 0,
            positionMs = if (positionIsCurrent) position[positionKey] ?: 0L else 0L,
            shuffleModeEnabled = queue[shuffleKey] ?: true,
            repeatMode = queue[repeatKey] ?: Player.REPEAT_MODE_ALL,
            wasPlaying = if (positionIsCurrent) position[wasPlayingKey] ?: false else false,
            current = queue[currentTrackKey]?.let(::decodeTrack),
            updatedAtMs = queue[updatedAtKey] ?: 0L,
        )
    }

    /**
     * Writes the queue and the position in one logical step.
     *
     * The position must move with the queue: a track change resets it to 0, and leaving
     * the old value behind would make the next cold start seek into the new track at
     * the previous track's timestamp.
     */
    suspend fun saveQueue(snapshot: PlaybackSnapshot) = withContext(Dispatchers.IO) {
        val ids = snapshot.trackIds.joinToString(",")
        val current = snapshot.current?.let { json.encodeToString(SnapshotTrack.serializer(), it) }
        context.playbackQueueStore.edit { preferences ->
            preferences[idsKey] = ids
            preferences[currentIndexKey] = snapshot.currentIndex
            if (current == null) {
                preferences.remove(currentTrackKey)
            } else {
                preferences[currentTrackKey] = current
            }
            preferences[shuffleKey] = snapshot.shuffleModeEnabled
            preferences[repeatKey] = snapshot.repeatMode
            preferences[updatedAtKey] = snapshot.updatedAtMs
        }
        context.playbackPositionStore.edit { preferences ->
            preferences[positionKey] = snapshot.positionMs.coerceAtLeast(0L)
            preferences[wasPlayingKey] = snapshot.wasPlaying
            preferences[positionUpdatedAtKey] = snapshot.updatedAtMs
        }
    }

    /**
     * Cursor-only write for a track change.
     *
     * The queue itself did not change, so `track_ids` is deliberately left alone: on a
     * 1 233-track library re-encoding that string is ~12 KB per transition, and every
     * DataStore edit rewrites the whole file atomically. Only the index, the current
     * item's metadata and the position move.
     *
     * The index is meaningless without the ids it indexes, so this must never run
     * before [saveQueue] has landed at least once — [QueueSnapshotPolicy] guarantees
     * that ordering by forcing the first write to be [QueueSnapshotScope.FULL].
     */
    suspend fun saveCursor(
        currentIndex: Int,
        current: SnapshotTrack?,
        positionMs: Long,
        shuffleModeEnabled: Boolean,
        repeatMode: Int,
        wasPlaying: Boolean,
        updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        val encoded = current?.let { json.encodeToString(SnapshotTrack.serializer(), it) }
        context.playbackQueueStore.edit { preferences ->
            preferences[currentIndexKey] = currentIndex
            if (encoded == null) {
                preferences.remove(currentTrackKey)
            } else {
                preferences[currentTrackKey] = encoded
            }
            preferences[shuffleKey] = shuffleModeEnabled
            preferences[repeatKey] = repeatMode
            preferences[updatedAtKey] = updatedAtMs
        }
        context.playbackPositionStore.edit { preferences ->
            preferences[positionKey] = positionMs.coerceAtLeast(0L)
            preferences[wasPlayingKey] = wasPlaying
            preferences[positionUpdatedAtKey] = updatedAtMs
        }
    }

    /**
     * Position plus the play/pause intent.
     *
     * Called on every `isPlaying` transition and on the fifteen-second ticker while
     * playback advances. The ticker writes the flag too: it is one small preference in
     * the same edit, and a position-only write would leave a stale `wasPlaying` behind.
     */
    suspend fun savePlaybackState(wasPlaying: Boolean, positionMs: Long) =
        withContext(Dispatchers.IO) {
            context.playbackPositionStore.edit { preferences ->
                preferences[positionKey] = positionMs.coerceAtLeast(0L)
                preferences[wasPlayingKey] = wasPlaying
                // The ticker only moves the position; its timestamp is newer than the
                // queue's, which `load` reads as "position still matches this queue".
                preferences[positionUpdatedAtKey] = System.currentTimeMillis()
            }
        }

    /**
     * Removes the snapshot entirely.
     *
     * Used when the queue becomes empty. Leaving the old ids behind would make the next
     * cold start restore a queue the session no longer owns. Both stores are cleared so
     * a later partial write cannot pair new ids with the old position.
     */
    suspend fun clear() = withContext(Dispatchers.IO) {
        context.playbackQueueStore.edit { it.clear() }
        context.playbackPositionStore.edit { it.clear() }
    }

    /**
     * Maps the persisted queue onto [library] and clamps the position to the current
     * track's duration.
     *
     * The clamp matters after re-tagging or replacing a file: the old timestamp can be
     * past the new end, and `setMediaItems` would otherwise start the restored track
     * already finished.
     */
    suspend fun restore(library: List<Track>): RestoredQueue? {
        val snapshot = load() ?: return null
        val byId = library.associateBy { it.id }
        val mapped = mapRestoredQueue(snapshot.trackIds, snapshot.currentIndex, byId.keys)
            ?: return null
        val tracks = mapped.trackIds.mapNotNull(byId::get)
        if (tracks.isEmpty()) return null
        val duration = tracks.getOrNull(mapped.startIndex)?.duration ?: 0L
        val position = if (duration > 0L) {
            snapshot.positionMs.coerceIn(0L, duration)
        } else {
            snapshot.positionMs.coerceAtLeast(0L)
        }
        return RestoredQueue(
            tracks = tracks,
            startIndex = mapped.startIndex,
            positionMs = position,
            shuffleModeEnabled = snapshot.shuffleModeEnabled,
            repeatMode = snapshot.repeatMode.validRepeatMode(),
            wasPlaying = snapshot.wasPlaying,
        )
    }

    private suspend fun queuePreferences(): Preferences = context.playbackQueueStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .first()

    private suspend fun positionPreferences(): Preferences = context.playbackPositionStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .first()

    private fun decodeTrack(raw: String): SnapshotTrack? =
        runCatching { json.decodeFromString(SnapshotTrack.serializer(), raw) }
            .onFailure { Log.w(TAG, "playback snapshot current track unreadable", it) }
            .getOrNull()

    private companion object {
        const val TAG = "PlaybackSnapshot"
    }
}

internal fun SnapshotTrack.toTrack(): Track = Track(
    id = id,
    uri = uri.toUri(),
    title = title,
    artist = artist,
    album = album,
    albumId = albumId,
    duration = durationMs,
    // Playback and notification metadata read the album artist, track number and year
    // below; the rest exist on the model for the library list and sort. A snapshot
    // restored without the library cache shows the right text and artwork and nothing
    // else.
    sizeBytes = 0L,
    year = year,
    trackNumber = trackNumber,
    dateAdded = 0L,
    albumArtist = albumArtist,
)

internal fun Track.toSnapshotTrack(): SnapshotTrack = SnapshotTrack(
    id = id,
    uri = uri.toString(),
    title = title,
    artist = artist,
    album = album,
    albumId = albumId,
    durationMs = duration,
    albumArtist = albumArtist,
    year = year,
    trackNumber = trackNumber,
)

/**
 * Runs one snapshot write and reports failure instead of throwing it.
 *
 * The service performs these writes from listeners and its fifteen-second ticker.
 * Letting a storage failure escape that coroutine kills the process while it is
 * playing in the background, which turns "the cache could not be saved" into a
 * playback crash. Cancellation is not a storage failure and must still propagate.
 */
internal suspend fun saveSnapshotSafely(
    label: String,
    onFailure: (String, Throwable) -> Unit,
    block: suspend () -> Unit,
): Boolean = try {
    block()
    true
} catch (e: CancellationException) {
    // The service is being destroyed. This is structured concurrency, not a failed
    // write; swallowing it would keep a cancelled scope alive.
    throw e
} catch (e: Exception) {
    // IOException ("no space left"), IllegalStateException from a DataStore that
    // cannot be opened, SQLite-style provider failures: losing the snapshot costs one
    // resume position, crashing the playback service costs the user their music.
    onFailure(label, e)
    false
}

/**
 * Rebuilds enough of a [Track] from a session item to render the UI.
 *
 * Playback can start without [PlayerController.playQueue]: a library browser sends its
 * own items, and the system can resume a persisted queue after process death. In both
 * cases the controller's index is empty, so the player screen would have no title,
 * artwork or neighbours even though the session is playing. The URI and the metadata
 * are already on the item; this only turns them back into the model the UI consumes.
 *
 * The metadata carries everything the notification, lock screen and Auto show — title,
 * artist, album artist, album, duration, track number, year and artwork. Fields it never
 * carries (size, album id, date added) stay at their neutral values: this is a display
 * fallback, never a source for sorting or rescanning. The audio URI is required; falling
 * back to `artworkUri` would hand the UI a playable-looking track that opens a picture.
 */
internal fun MediaItem.toFallbackTrack(): Track? {
    val id = mediaId.toLongOrNull() ?: return null
    val metadata = mediaMetadata
    val uri = localConfiguration?.uri ?: return null
    return Track(
        id = id,
        uri = uri,
        title = metadata.title?.toString().orEmpty(),
        artist = metadata.artist?.toString().orEmpty(),
        album = metadata.albumTitle?.toString().orEmpty(),
        albumId = 0L,
        duration = metadata.durationMs ?: 0L,
        sizeBytes = 0L,
        year = metadata.releaseYear ?: 0,
        trackNumber = metadata.trackNumber ?: 0,
        dateAdded = 0L,
        albumArtist = metadata.albumArtist?.toString().orEmpty(),
    )
}
