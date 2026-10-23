package com.milan.music.ui.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the "empty rescan wipes the library" bug.
 *
 * `refresh()` used to assign whatever the provider returned, so a momentarily
 * unavailable MediaProvider cleared the list and stamped the empty snapshot as
 * current — the next launch then matched the stamp and skipped the rescan.
 */
class LibraryScanPolicyTest {

    @Test
    fun `empty scan keeps a non-empty library`() {
        assertTrue(keepsCachedLibraryOnEmptyScan(freshCount = 0, cachedCount = 1))
        assertTrue(keepsCachedLibraryOnEmptyScan(freshCount = 0, cachedCount = 5_000))
    }

    @Test
    fun `empty scan on an empty library is allowed to proceed`() {
        assertFalse(keepsCachedLibraryOnEmptyScan(freshCount = 0, cachedCount = 0))
    }

    @Test
    fun `a non-empty scan always replaces the library`() {
        assertFalse(keepsCachedLibraryOnEmptyScan(freshCount = 1, cachedCount = 5_000))
        assertFalse(keepsCachedLibraryOnEmptyScan(freshCount = 5_000, cachedCount = 1))
    }
}
