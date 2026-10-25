package com.milan.music.play

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A track change rewrites the whole persisted queue today, which is 12 KB of ids on a
 * 1 233-track library plus two atomic DataStore edits — for a queue whose structure did
 * not change. These tests pin the narrower write the transition path is allowed to do.
 */
class QueueSnapshotPolicyTest {

    @Test
    fun `first transition writes the whole queue`() {
        // Nothing has been persisted yet, so a cursor-only write would leave no queue to
        // restore. This is the one case where the expensive write is mandatory.
        val policy = QueueSnapshotPolicy()

        assertEquals(QueueSnapshotScope.FULL, policy.scopeForTransition())
    }

    @Test
    fun `transition after a written queue only moves the cursor`() {
        val policy = QueueSnapshotPolicy()
        policy.onIdsPersisted()

        assertEquals(QueueSnapshotScope.CURSOR, policy.scopeForTransition())
    }

    @Test
    fun `a changed timeline rewrites the queue`() {
        // addMediaItems / removeMediaItem / moveMediaItems all land here. A cursor-only
        // write on top of a stale id list would restore the wrong index.
        val policy = QueueSnapshotPolicy()
        policy.onIdsPersisted()
        policy.onTimelineChanged()

        assertEquals(QueueSnapshotScope.FULL, policy.scopeForTransition())
    }

    @Test
    fun `the structure is clean again once the rewrite landed`() {
        val policy = QueueSnapshotPolicy()
        policy.onIdsPersisted()
        policy.onTimelineChanged()
        assertEquals(QueueSnapshotScope.FULL, policy.scopeForTransition())

        policy.onIdsPersisted()

        assertEquals(QueueSnapshotScope.CURSOR, policy.scopeForTransition())
    }

    @Test
    fun `a failed rewrite keeps the next transition full`() {
        // onIdsPersisted() is the acknowledgement, not the attempt: a write that threw
        // never reaches it, so the structure stays dirty and the next transition retries.
        val policy = QueueSnapshotPolicy()
        policy.onTimelineChanged()

        assertEquals(QueueSnapshotScope.FULL, policy.scopeForTransition())
    }
}
