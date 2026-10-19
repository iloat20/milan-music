package com.milan.music.play

import com.milan.music.data.model.Track
import java.util.concurrent.ConcurrentHashMap

/**
 * `mediaId` → [Track], shared between the UI that builds the queue and the session
 * that resolves each item's metadata.
 *
 * ## Why this exists
 *
 * [PlayerController.playQueue] used to attach a full `MediaMetadata` to every
 * `MediaItem` before handing the list to the session. For a 5 000-track library that
 * is 5 000 builder objects, 5 000 × 3 strings and one `Uri` each — allocated on the
 * main thread at the moment the user taps a row, then written into a Parcel and read
 * back out on the session side. None of it is needed to *queue* the tracks; it is
 * only needed for the item that is actually playing, plus the next one being
 * prepared.
 *
 * The queue therefore travels as `mediaId` + `uri` only, and
 * [PlaybackService]'s `MediaSession.Callback.onAddMediaItems` rebuilds the metadata
 * from this index when the session asks for it. The index is the single
 * `ConcurrentHashMap` both sides share, so the lookup is one hash probe per resolved
 * item instead of a second copy of the library.
 *
 * ## Lifecycle
 *
 * Replaced wholesale on every [PlayerController.playQueue] and never cleared by
 * `PlayerController.release()`: a released controller can leave a live paused queue in
 * the session, and the session must still be able to resolve metadata for it (the
 * notification re-resolves artwork and text on every metadata refresh). It is
 * therefore bounded by "the tracks of the current queue", not by process lifetime.
 *
 * ## Same-process assumption
 *
 * This works only because `PlaybackService` runs in the application process: both
 * sides reach the same [com.milan.music.di.AppContainer] and therefore the same map.
 * Declaring the service with `android:process` would give each side its own instance —
 * the controller would fill one, the session would read the other, and every item
 * would play with no title, artist or artwork and no error anywhere. If the service
 * ever moves out of process, this has to become an explicit IPC contract.
 */
class PlaybackTrackIndex {

    private val byId = ConcurrentHashMap<String, Track>()

    /** Replaces the whole index. Called before the session is given the new queue. */
    fun replace(tracks: List<Track>) {
        byId.clear()
        tracks.forEach { byId[it.id.toString()] = it }
    }

    /** The track for a `mediaId`, or null when the queue no longer contains it. */
    fun get(mediaId: String?): Track? = mediaId?.let(byId::get)
}
