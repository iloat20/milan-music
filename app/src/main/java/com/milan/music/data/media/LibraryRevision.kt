package com.milan.music.data.media

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide revision of the scanned library.
 *
 * [LibraryCacheStore] persists the rows, but a reader that keeps them in memory has no
 * way to notice a rescan. The library browser in
 * [com.milan.music.play.PlaybackService] does exactly that: it caches the grouped tree
 * for the lifetime of the service. Bumping this counter after a scan is cheaper than
 * re-reading and re-decoding the whole cache on every browse request, and it keeps the
 * tree from serving rows that were replaced minutes ago.
 */
class LibraryRevision {

    private val value = AtomicLong(0L)

    /** Called after [LibraryCacheStore.save] has committed a new snapshot. */
    fun bump() {
        value.incrementAndGet()
    }

    fun current(): Long = value.get()
}
