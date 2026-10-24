package com.milan.music.ui.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for scan results that must not replace the library.
 *
 * The first version only guarded a *count of zero*. A cursor that threw halfway
 * through returned a non-empty partial list, which looked like a successful scan: it
 * was published, persisted and stamped as current, so the missing tracks stayed
 * invisible until MediaStore's generation changed again.
 */
class LibraryScanPolicyTest {

    @Test
    fun `an incomplete scan never replaces the library`() {
        assertFalse(
            shouldApplyScan(scannedCount = 3, complete = false, cachedCount = 5_000),
        )
        assertFalse(
            shouldApplyScan(scannedCount = 3, complete = false, cachedCount = 0),
        )
    }

    @Test
    fun `a complete empty scan keeps a non-empty library`() {
        assertFalse(shouldApplyScan(scannedCount = 0, complete = true, cachedCount = 1))
        assertFalse(shouldApplyScan(scannedCount = 0, complete = true, cachedCount = 5_000))
    }

    @Test
    fun `a complete empty scan on an empty library is applied`() {
        assertTrue(shouldApplyScan(scannedCount = 0, complete = true, cachedCount = 0))
    }

    @Test
    fun `a complete non-empty scan replaces the library`() {
        assertTrue(shouldApplyScan(scannedCount = 1, complete = true, cachedCount = 5_000))
        assertTrue(shouldApplyScan(scannedCount = 5_000, complete = true, cachedCount = 1))
    }
}
