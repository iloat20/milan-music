package com.milan.music.play

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
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
 * library cache. The queue is stored separately from the cursor and the position: the
 * latter two move on every track change and every fifteen seconds, and putting a
 * 5 000-id payload in the same DataStore would rewrite tens of kilobytes on each of
 * those writes.
 */
@Serializable
data class PlaybackSnapshot(
    val trackIds: List<Long> = emptyList(),
    /**
     * Index into [trackIds] — that is, into the timeline order the queue was saved in,
     * which shuffle had already applied. Same value as `Player.currentMediaItemIndex`
     * at write time.
     */
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
 * Whether a cursor written by the position store still describes the queue on disk.
 *
 * The queue and the cursor are two files: a FULL write touches the ids first and the
 * cursor second. A process killed in between leaves new ids with the previous cursor,
 * whose index may point at a different track. The cursor therefore carries the
 * revision of the queue it was written for; only an exact match is usable.
 *
 * A null queue revision is the older layout, where the cursor lived in the queue file
 * itself and was atomic with the ids. There is nothing to compare, so it counts as
 * a match.
 */
internal fun cursorBelongsToQueue(queueRevision: Long?, cursorRevision: Long?): Boolean =
    queueRevision == null || queueRevision == cursorRevision

/**
 * A revision strictly greater than [previous].
 *
 * Equality is what [cursorBelongsToQueue] tests. It still has to increase: if the clock
 * moves backwards and the same value is reused, a cursor from the previous queue would
 * validate against the new one when only the cursor edit landed.
 */
internal fun bumpRevision(previous: Long, now: Long): Long =
    if (now > previous) now else previous + 1

/**
 * 持久化的位置是否仍然属于队列指着的那一项。
 *
 * 队列和位置分居两个 DataStore，每个写入方都是"先写队列、再写位置"两步。两种交叠
 * 会留下**位置属于上一首**的快照：
 *
 *  - 进程在两次 edit 之间被杀；
 *  - 两个写入方的 IO 完成顺序与发起顺序相反（切歌撞上十五秒一次的 ticker）。
 *
 * `load()` 原先只有时间戳比较，而它比的是 `System.currentTimeMillis()`：一次时钟
 * 回拨就能让更旧的写入看起来更新。`position_index` 是**精确**判据 —— 它与位置写在
 * 同一批里，天然同属一首；时间戳退化成第二道闸，挡"同一个 index 但队列被换过"
 * 的情况（重扫后同位置换了歌）。
 *
 * `positionIndex == null` 是最早的格式：退回纯时间戳比较，升级时不能把位置丢掉。
 */
internal fun positionMatchesQueue(
    queueIndex: Int,
    queueUpdatedAtMs: Long,
    positionIndex: Int?,
    positionUpdatedAtMs: Long,
): Boolean {
    if (positionIndex != null && positionIndex != queueIndex) return false
    return positionUpdatedAtMs >= queueUpdatedAtMs
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
 * The queue file also carries a copy of the cursor, written atomically with the ids, so
 * an interrupted full write is detectable instead of silently resuming the wrong track.
 */
class PlaybackSnapshotStore(context: Context) {

    private val context = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    private val idsKey = stringPreferencesKey("track_ids")
    /** When [idsKey] last changed; paired with `position_updated_at` by [load]. */
    private val updatedAtKey = longPreferencesKey("updated_at")
    /** Monotonic id of the queue file; matched against [cursorRevisionKey]. */
    private val queueRevisionKey = longPreferencesKey("queue_revision")

    private val positionKey = longPreferencesKey("position_ms")
    private val positionUpdatedAtKey = longPreferencesKey("position_updated_at")
    /** 位置属于队列里的哪一项；见 [positionMatchesQueue]。 */
    private val positionIndexKey = intPreferencesKey("position_index")
    private val wasPlayingKey = booleanPreferencesKey("was_playing")

    /**
     * The play cursor. Written in the same edit as the position, never with the ids.
     *
     * These used to live in the queue store. `CURSOR` skipped re-encoding the id string
     * but not writing it: DataStore rewrites the whole preferences file atomically, so
     * every track change still re-wrote the ~50 KB `track_ids` at a 5 000-track library.
     * The cursor moves on every transition; the ids do not. Keeping them apart makes the
     * frequent write O(1) bytes, which is what the scope was supposed to buy.
     */
    private val cursorIndexKey = intPreferencesKey("cursor_index")
    private val cursorTrackKey = stringPreferencesKey("cursor_track")
    private val cursorShuffleKey = booleanPreferencesKey("cursor_shuffle")
    private val cursorRepeatKey = intPreferencesKey("cursor_repeat")
    /** The queue revision this cursor belongs to; see [load]. */
    private val cursorRevisionKey = longPreferencesKey("cursor_revision")

    /**
     * The queue file's own copy of the cursor, written atomically with [idsKey].
     *
     * Two jobs. On a FULL write it is the cursor the new queue starts at, so a reader
     * that never sees the position file still gets a coherent answer; on load it is the
     * fallback when the cursor edit did not land. The key names predate the split, when
     * the whole cursor lived in this file, so an upgrade keeps reading the last session.
     */
    private val queueCursorIndexKey = intPreferencesKey("current_index")
    private val queueCursorTrackKey = stringPreferencesKey("current_track")
    private val queueCursorShuffleKey = booleanPreferencesKey("shuffle")
    private val queueCursorRepeatKey = intPreferencesKey("repeat_mode")

    /** In-memory copy of [queueRevisionKey]; the write path is serialised by the service. */
    @Volatile
    private var queueRevision: Long? = null

    suspend fun load(): PlaybackSnapshot? = withContext(Dispatchers.IO) {
        val queue = queuePreferences()
        val rawIds = queue[idsKey] ?: return@withContext null
        val ids = rawIds.split(',').mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return@withContext null
        val position = positionPreferences()
        val storedRevision = queue[queueRevisionKey]
        // Legacy snapshots have no revision: their cursor still lives in the queue store,
        // so it is taken as current and checked with the timestamp rule below.
        val cursorIsCurrent = cursorBelongsToQueue(
            queueRevision = storedRevision,
            cursorRevision = position[cursorRevisionKey],
        )
        val queueIndex = queue[queueCursorIndexKey] ?: 0
        val currentIndex = if (cursorIsCurrent) {
            position[cursorIndexKey] ?: queueIndex
        } else {
            queueIndex
        }
        val currentTrack = if (cursorIsCurrent) {
            position[cursorTrackKey] ?: queue[queueCursorTrackKey]
        } else {
            queue[queueCursorTrackKey]
        }
        val queueUpdatedAt = queue[updatedAtKey] ?: 0L
        // Whether the *position* belongs to this queue is a separate question from
        // whether the cursor metadata does. `currentIndex` is the cursor's index when it
        // matches, and the queue file's copy otherwise — either way it names the item the
        // position would have to belong to. A ticker can keep writing after the cursor
        // half of a FULL write failed, so rejecting the position only because the cursor
        // is stale would throw away a timestamp that is provably for this queue.
        val positionUpdatedAt = position[positionUpdatedAtKey] ?: queueUpdatedAt
        val positionIsCurrent = positionMatchesQueue(
            queueIndex = currentIndex,
            queueUpdatedAtMs = queueUpdatedAt,
            positionIndex = position[positionIndexKey],
            positionUpdatedAtMs = positionUpdatedAt,
        )
        PlaybackSnapshot(
            trackIds = ids,
            currentIndex = currentIndex,
            positionMs = if (positionIsCurrent) position[positionKey] ?: 0L else 0L,
            shuffleModeEnabled = if (cursorIsCurrent) {
                position[cursorShuffleKey] ?: queue[queueCursorShuffleKey] ?: true
            } else {
                queue[queueCursorShuffleKey] ?: true
            },
            repeatMode = if (cursorIsCurrent) {
                position[cursorRepeatKey] ?: queue[queueCursorRepeatKey] ?: Player.REPEAT_MODE_ALL
            } else {
                queue[queueCursorRepeatKey] ?: Player.REPEAT_MODE_ALL
            },
            wasPlaying = if (positionIsCurrent) position[wasPlayingKey] ?: false else false,
            current = currentTrack?.let(::decodeTrack),
            updatedAtMs = queueUpdatedAt,
        )
    }

    /**
     * Writes the queue, the cursor and the position in one logical step.
     *
     * The ids and a copy of the cursor go to the queue file; the live cursor and the
     * position go to the position file. Both edits are needed: the queue file is what a
     * reader without the next edit can trust, the position file is what the frequent
     * writes keep updating. [queueRevisionKey] ties them together, so a reader can tell
     * "this cursor was written for this queue" from "the queue edit landed, the cursor
     * edit did not".
     *
     * The position must move with the queue: a track change resets it to 0, and leaving
     * the old value behind would make the next cold start seek into the new track at
     * the previous track's timestamp.
     */
    suspend fun saveQueue(snapshot: PlaybackSnapshot) = withContext(Dispatchers.IO) {
        val ids = snapshot.trackIds.joinToString(",")
        val current = snapshot.current?.let { json.encodeToString(SnapshotTrack.serializer(), it) }
        // Not published until the edit below returns: if the write fails, the cursor
        // writers must keep pairing with the revision that is actually on disk.
        val revision = nextRevision()
        context.playbackQueueStore.edit { preferences ->
            preferences[idsKey] = ids
            preferences[updatedAtKey] = snapshot.updatedAtMs
            preferences[queueRevisionKey] = revision
            // Written atomically with the ids: if the cursor edit below never lands,
            // this is the only coherent cursor for the new queue.
            preferences[queueCursorIndexKey] = snapshot.currentIndex
            if (current == null) {
                preferences.remove(queueCursorTrackKey)
            } else {
                preferences[queueCursorTrackKey] = current
            }
            preferences[queueCursorShuffleKey] = snapshot.shuffleModeEnabled
            preferences[queueCursorRepeatKey] = snapshot.repeatMode
        }
        queueRevision = revision
        context.playbackPositionStore.edit { preferences ->
            writeCursor(
                preferences = preferences,
                currentIndex = snapshot.currentIndex,
                encodedTrack = current,
                shuffleModeEnabled = snapshot.shuffleModeEnabled,
                repeatMode = snapshot.repeatMode,
                revision = revision,
            )
            writePosition(
                preferences = preferences,
                positionMs = snapshot.positionMs,
                wasPlaying = snapshot.wasPlaying,
                currentIndex = snapshot.currentIndex,
                updatedAtMs = snapshot.updatedAtMs,
            )
        }
    }

    /**
     * Cursor-only write for a track change.
     *
     * One edit, one small file: the ids live in the queue store and are not touched.
     * That is the whole point — at 5 000 tracks `track_ids` is ~50 KB, and a DataStore
     * edit rewrites its file in full, so leaving the string out of the *encode* was
     * never enough.
     *
     * The position travels with the cursor because they describe the same item. They
     * used to be two edits in two files, which is why `load` needs
     * [positionMatchesQueue] at all; inside one edit the pair is atomic.
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
        val revision = currentRevision()
        context.playbackPositionStore.edit { preferences ->
            writeCursor(
                preferences = preferences,
                currentIndex = currentIndex,
                encodedTrack = encoded,
                shuffleModeEnabled = shuffleModeEnabled,
                repeatMode = repeatMode,
                revision = revision,
            )
            writePosition(
                preferences = preferences,
                positionMs = positionMs,
                wasPlaying = wasPlaying,
                currentIndex = currentIndex,
                updatedAtMs = updatedAtMs,
            )
        }
    }

    /**
     * Position plus the play/pause intent.
     *
     * Called on every `isPlaying` transition and on the fifteen-second ticker while
     * playback advances. The ticker writes the flag too: it is one small preference in
     * the same edit, and a position-only write would leave a stale `wasPlaying` behind.
     *
     * [currentIndex] is the item the position belongs to. It is written next to the
     * position rather than read from the cursor on load: without it, a position that
     * lost the race against a track change would be resumed on the *next* track.
     *
     * Deliberately does not touch [cursorIndexKey]. The cursor fields are a single
     * consistent set written by [saveCursor]/[saveQueue]; letting a ticker move the
     * index alone could pair a new index with the previous item's metadata. A mismatch
     * between the two indices is caught by [positionMatchesQueue], which drops the
     * position but keeps the cursor.
     */
    suspend fun savePlaybackState(wasPlaying: Boolean, positionMs: Long, currentIndex: Int) =
        withContext(Dispatchers.IO) {
            context.playbackPositionStore.edit { preferences ->
                writePosition(
                    preferences = preferences,
                    positionMs = positionMs,
                    wasPlaying = wasPlaying,
                    // The ticker only moves the position; its timestamp is newer than
                    // the queue's, which `load` reads as "position still matches".
                    currentIndex = currentIndex,
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
        }

    private fun writeCursor(
        preferences: MutablePreferences,
        currentIndex: Int,
        encodedTrack: String?,
        shuffleModeEnabled: Boolean,
        repeatMode: Int,
        revision: Long,
    ) {
        preferences[cursorIndexKey] = currentIndex
        if (encodedTrack == null) {
            preferences.remove(cursorTrackKey)
        } else {
            preferences[cursorTrackKey] = encodedTrack
        }
        preferences[cursorShuffleKey] = shuffleModeEnabled
        preferences[cursorRepeatKey] = repeatMode
        preferences[cursorRevisionKey] = revision
    }

    /**
     * A revision strictly greater than the one already on disk.
     *
     * Equality — not ordering — is what [load] tests, but "greater" is what makes a
     * clock that moved backwards safe: a repeated value would let a cursor from the
     * previous queue validate against the new one if the cursor edit landed first.
     */
    private suspend fun nextRevision(): Long {
        val previous = queueRevision ?: queuePreferences()[queueRevisionKey] ?: 0L
        return bumpRevision(previous = previous, now = System.currentTimeMillis())
    }

    /**
     * The revision of the queue currently on disk.
     *
     * Never mints a new one: the cursor is only valid while it matches the queue file,
     * so bumping here would make every first cursor write of a process unreadable until
     * the next FULL write. A cursor write normally follows a FULL write in the same
     * session; the read only happens when the first write of a process is a transition
     * (a platform resumption, or playback restored without `playQueue`).
     */
    private suspend fun currentRevision(): Long {
        queueRevision?.let { return it }
        val stored = queuePreferences()[queueRevisionKey] ?: 0L
        queueRevision = stored
        return stored
    }

    private fun writePosition(
        preferences: MutablePreferences,
        positionMs: Long,
        wasPlaying: Boolean,
        currentIndex: Int,
        updatedAtMs: Long,
    ) {
        preferences[positionKey] = positionMs.coerceAtLeast(0L)
        preferences[wasPlayingKey] = wasPlaying
        preferences[positionUpdatedAtKey] = updatedAtMs
        preferences[positionIndexKey] = currentIndex
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
        // The queue is gone; the next FULL write must mint a revision from scratch
        // instead of pairing a cursor with a revision that no longer exists on disk.
        queueRevision = null
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
