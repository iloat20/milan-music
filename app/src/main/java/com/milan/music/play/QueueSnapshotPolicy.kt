package com.milan.music.play

/** Which part of the persisted queue a write has to touch. */
internal enum class QueueSnapshotScope {
    /** Ids plus cursor: the queue itself changed. */
    FULL,

    /** Cursor plus position only: the same queue, one item further along. */
    CURSOR,
}

/**
 * Decides how much of the queue a snapshot write has to rewrite.
 *
 * A track change today rewrote every id: on a 1 233-track library that is a ~12 KB
 * string, re-encoded and written as two atomic DataStore edits (whole file, rename,
 * fsync) — three of them per transition, because the same event also writes the
 * position. The ids did not change; only the index did.
 *
 * The distinction is drawn from the event, not from comparing id lists afterwards.
 * [onTimelineChanged] is the only signal that the item list itself moved: every
 * `addMediaItems` / `removeMediaItem` / `moveMediaItems` changes the timeline, while a
 * plain transition does not. That keeps the transition path O(1) — no 1 233-element
 * read loop to discover the answer was "unchanged".
 *
 * A failure is safe by construction: [onIdsPersisted] is the acknowledgement, so a
 * write that threw never clears the flag and the next transition retries the full form.
 */
internal class QueueSnapshotPolicy {

    private var idsPersisted = false
    private var structureDirty = false

    fun onTimelineChanged() {
        structureDirty = true
    }

    fun onIdsPersisted() {
        idsPersisted = true
        structureDirty = false
    }

    fun scopeForTransition(): QueueSnapshotScope =
        if (idsPersisted && !structureDirty) QueueSnapshotScope.CURSOR else QueueSnapshotScope.FULL
}
