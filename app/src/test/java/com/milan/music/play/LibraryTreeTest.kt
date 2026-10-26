package com.milan.music.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryTreeTest {

    @Test
    fun `page slice returns the first page`() {
        assertEquals(0 until 3, pageSlice(total = 10, page = 0, pageSize = 3))
    }

    @Test
    fun `page slice returns a partial last page`() {
        assertEquals(9 until 10, pageSlice(total = 10, page = 3, pageSize = 3))
    }

    @Test
    fun `page slice returns empty past the last page`() {
        assertEquals(IntRange.EMPTY, pageSlice(total = 10, page = 4, pageSize = 3))
    }

    @Test
    fun `page slice rejects invalid inputs`() {
        assertEquals(IntRange.EMPTY, pageSlice(total = 0, page = 0, pageSize = 10))
        assertEquals(IntRange.EMPTY, pageSlice(total = 10, page = 0, pageSize = 0))
        assertEquals(IntRange.EMPTY, pageSlice(total = 10, page = -1, pageSize = 10))
    }

    @Test
    fun `page slice clamps a page size larger than the total`() {
        assertEquals(0 until 10, pageSlice(total = 10, page = 0, pageSize = 100))
    }

    /**
     * `page * pageSize` used to be an `Int` multiply. At these values it overflows to a
     * negative number, and the old `until` would either throw or return the wrong
     * window instead of "past the end".
     */
    @Test
    fun `page slice does not overflow on huge page numbers`() {
        assertEquals(
            IntRange.EMPTY,
            pageSlice(total = 100, page = Int.MAX_VALUE, pageSize = 10),
        )
        assertEquals(
            IntRange.EMPTY,
            pageSlice(total = Int.MAX_VALUE, page = 1, pageSize = Int.MAX_VALUE),
        )
    }

    @Test
    fun `album ids round trip`() {
        val id = LibraryIds.album(42L)
        assertEquals("lib:album:42", id)
        assertEquals(42L, LibraryIds.albumId(id))
        assertNull(LibraryIds.albumId("lib:album:not-a-number"))
        assertNull(LibraryIds.albumId("42"))
    }

    @Test
    fun `negative album ids round trip`() {
        // Untagged albums get a synthetic negative id so they can be split by name
        // without colliding with any real MediaStore album id.
        val id = LibraryIds.album(-42L)
        assertEquals("lib:album:-42", id)
        assertEquals(-42L, LibraryIds.albumId(id))
    }

    @Test
    fun `artist ids round trip unicode and separators`() {
        listOf("周杰伦", "AC/DC: Live", "a:b/c", "", "🎵 artist").forEach { name ->
            val id = LibraryIds.artist(name)
            assertEquals(name, LibraryIds.artistName(id))
        }
    }

    @Test
    fun `malformed container ids are rejected`() {
        assertNull(LibraryIds.artistName("lib:artist:!!!"))
        assertNull(LibraryIds.artistName("lib:artist"))
        assertNull(LibraryIds.artistName("123"))
        assertNull(LibraryIds.albumId("lib:album:"))
    }
}
