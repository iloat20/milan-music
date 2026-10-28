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
 * A track change used to rewrite every id: on a 1 233-track library that is a ~12 KB
 * string, re-encoded and written as two atomic DataStore edits (whole file, rename,
 * fsync) — three of them per transition, because the same event also wrote the
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

    /**
     * Incremented on every timeline change.
     *
     * A FULL write reads the player before it suspends on IO, and another timeline change
     * can land during that write. Without a version, the completing write would clear the
     * flag the newer change just set, and the next transition would put a cursor on top of
     * the older ids. The version is captured before the read and compared when the write
     * is acknowledged, so only a write that covered every change since its read clears it.
     */
    private var structureVersion = 0L

    fun onTimelineChanged() {
        structureVersion++
        structureDirty = true
    }

    /** The version to capture before reading the player for a FULL write. */
    fun currentStructureVersion(): Long = structureVersion

    /**
     * Acknowledges a FULL write.
     *
     * [version] is the value [currentStructureVersion] returned before the player was
     * read. If a newer timeline change has arrived since, the structure stays dirty and
     * the next transition writes the full form again.
     */
    fun onIdsPersisted(version: Long) {
        idsPersisted = true
        if (version == structureVersion) structureDirty = false
    }

    fun scopeForTransition(): QueueSnapshotScope =
        if (idsPersisted && !structureDirty) QueueSnapshotScope.CURSOR else QueueSnapshotScope.FULL
}
