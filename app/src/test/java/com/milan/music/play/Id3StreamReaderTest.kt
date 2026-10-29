package com.milan.music.play

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The streamed frame walk has to satisfy two things at once, and only the second one is
 * obvious:
 *
 * 1. it must return the same gain [ReplayGainTags.parse] would, because a divergence
 *    shows up as a silently wrong correction — a misread length turns -6.5 dB into
 *    +6.5 dB and the track gets *louder*;
 * 2. it must not read the frames it skips.
 *
 * The second is why these tests count bytes: an implementation that walks the header
 * table correctly but then reads each body to advance would pass every correctness case
 * here while still paying the full tag on the transition path, which is the whole point
 * of the change.
 */
class Id3StreamReaderTest {

    @Test
    fun `cover art ahead of the gain frame is skipped, not read`() {
        // 300 KB of `APIC` before the `TXXX` frame: the case a buffered read cannot win.
        val tag = id3Tag(version = 3, frames = apic(300 * 1024, 3) + txxx("-6.50 dB", version = 3))
        val (gain, readBytes) = gainFromStream(tag)

        assertEquals(-6.5f, gain!!, 0.01f)
        assertTrue(
            "read $readBytes bytes to find a frame at offset ${10 + 300 * 1024 + 10}",
            readBytes < 1024,
        )
    }

    @Test
    fun `an untagged tag is not read to its declared size`() {
        val tag = id3Tag(version = 3, frames = apic(300 * 1024, 3) + txxxBody("TITLE", "no gain here", version = 3))
        val (gain, readBytes) = gainFromStream(tag)

        assertNull(gain)
        assertTrue("read $readBytes bytes", readBytes < 1024)
    }

    @Test
    fun `a declared size beyond the cap stops at the end of the stream`() {
        // A corrupt header claiming 256 MB must not size anything. The walk reads what
        // the file actually holds and then stops.
        val tag = id3Tag(version = 3, frames = apic(4096, 3), declaredSize = 0x0FFF_FFFF)
        val (gain, readBytes) = gainFromStream(tag)

        assertNull(gain)
        assertTrue("read $readBytes bytes from a ${tag.size}-byte file", readBytes <= tag.size)
    }

    @Test
    fun `id3v2_4 frame sizes are read as syncsafe`() {
        // A body over 127 bytes is where plain big-endian and syncsafe decoding differ,
        // so a wrong branch passes a small fixture and corrupts every real tag.
        val tag = id3Tag(version = 4, frames = apic(4096, 4) + txxx("-9.75 dB".padEnd(200, ' '), version = 4))
        val (gain, _) = gainFromStream(tag)

        assertEquals(-9.75f, gain!!, 0.01f)
    }

    @Test
    fun `an id3v2_4 extended header is skipped before the frame table`() {
        val extended = syncSafeSize(6) + byteArrayOf(0, 0)
        val tag = id3Tag(
            version = 4,
            frames = extended + apic(2048, 4) + txxx("+1.50 dB", version = 4),
            flags = 0x40,
        )
        val (gain, readBytes) = gainFromStream(tag)

        assertEquals(1.5f, gain!!, 0.01f)
        assertTrue("read $readBytes bytes", readBytes < 1024)
    }

    @Test
    fun `an id3v2_3 extended header is measured excluding its own size field`() {
        val extended = byteArrayOf(0, 0, 0, 6) + ByteArray(6)
        val tag = id3Tag(version = 3, frames = extended + txxx("-2.00 dB", version = 3), flags = 0x40)
        val (gain, _) = gainFromStream(tag)

        assertEquals(-2.0f, gain!!, 0.01f)
    }

    @Test
    fun `album gain is the fallback when the track gain is absent`() {
        val tag = id3Tag(version = 3, frames = txxxBody("REPLAYGAIN_ALBUM_GAIN", "-4.00 dB", version = 3))
        val (gain, _) = gainFromStream(tag)

        assertEquals(-4.0f, gain!!, 0.01f)
    }

    @Test
    fun `a frame claiming more bytes than the tag holds stops the walk`() {
        val oversized = frame("TXXX", ByteArray(64), version = 3)
        // Rewrite the size field to claim more than the tag body can hold.
        oversized[4] = 0x7F
        val tag = id3Tag(version = 3, frames = oversized)
        assertNull(gainFromStream(tag).first)
    }

    /** A negative size would leave the walk where it is, spinning on the IO dispatcher. */
    @Test(timeout = 2_000)
    fun `a negative frame size stops the walk`() {
        val negative = frame("TXXX", ByteArray(16), version = 3)
        negative[4] = 0xFF.toByte()
        negative[5] = 0xFF.toByte()
        negative[6] = 0xFF.toByte()
        negative[7] = 0xF6.toByte() // -10
        assertNull(gainFromStream(id3Tag(version = 3, frames = negative)).first)
    }

    @Test
    fun `a truncated stream returns nothing`() {
        val full = id3Tag(version = 3, frames = apic(4096, 3) + txxx("-6.50 dB", version = 3))
        assertNull(gainFromStream(full.copyOf(full.size - 20)).first)
    }

    /**
     * The two paths must not drift.
     *
     * [Id3StreamReader] duplicates the frame-table rules of [ReplayGainTags.parseId3]
     * because one reads bytes and the other reads a stream; a fix applied to one and
     * not the other would be invisible until a user reported the wrong loudness.
     */
    @Test
    fun `streamed and buffered parsing agree`() {
        val cases = listOf(
            id3Tag(version = 3, frames = txxx("-6.50 dB", version = 3)),
            id3Tag(version = 4, frames = txxx("+2.10 dB", version = 4)),
            id3Tag(version = 3, frames = apic(1024, 3) + txxxBody("REPLAYGAIN_ALBUM_GAIN", "-3.25 dB", version = 3)),
            id3Tag(version = 3, frames = apic(1024, 3)),
            id3Tag(version = 3, frames = txxx("not a number", version = 3)),
        )
        cases.forEach { tag ->
            assertEquals(ReplayGainTags.parse(tag), gainFromStream(tag).first)
        }
    }
}

// ---------------------------------------------------------------------------------
// Fixtures. Every length field is the real one the walk has to trust.
// ---------------------------------------------------------------------------------

/**
 * A file whose tag declares more than the small-window threshold, so the reader would
 * choose the streamed path in production.
 */
private fun id3Tag(
    version: Int,
    frames: ByteArray,
    declaredSize: Int = frames.size,
    flags: Int = 0,
): ByteArray = ByteArrayOutputStream().apply {
    write("ID3".toByteArray())
    write(version)
    write(0)
    write(flags)
    write(syncSafeSize(declaredSize))
    write(frames)
}.toByteArray()

private fun apic(bodySize: Int, version: Int): ByteArray =
    frame("APIC", ByteArray(bodySize) { 0x41 }, version)

private fun txxx(value: String, version: Int): ByteArray =
    txxxBody("REPLAYGAIN_TRACK_GAIN", value, version)

private fun txxxBody(description: String, value: String, version: Int): ByteArray {
    val body = ByteArrayOutputStream().apply {
        write(0) // ISO-8859-1
        write(description.toByteArray(Charsets.ISO_8859_1))
        write(0)
        write(value.toByteArray(Charsets.ISO_8859_1))
    }.toByteArray()
    return frame("TXXX", body, version)
}

private fun frame(id: String, body: ByteArray, version: Int): ByteArray = ByteArrayOutputStream().apply {
    write(id.toByteArray())
    if (version >= 4) write(syncSafeSize(body.size)) else writeBe32(body.size)
    write(0)
    write(0) // flags
    write(body)
}.toByteArray()

private fun syncSafeSize(value: Int): ByteArray = byteArrayOf(
    ((value ushr 21) and 0x7F).toByte(),
    ((value ushr 14) and 0x7F).toByte(),
    ((value ushr 7) and 0x7F).toByte(),
    (value and 0x7F).toByte(),
)

private fun ByteArrayOutputStream.writeBe32(value: Int) {
    write(value ushr 24)
    write(value ushr 16)
    write(value ushr 8)
    write(value)
}

/**
 * Counts the bytes actually pulled from the stream. `skip` is deliberately not counted:
 * it is the operation this reader uses to avoid the read, and a `ByteArrayInputStream`
 * moves its position in constant time for it, which is what a seekable descriptor does.
 */
private class CountingStream(bytes: ByteArray) : InputStream() {
    private val delegate = ByteArrayInputStream(bytes)
    var readBytes = 0
        private set

    override fun read(): Int {
        val byte = delegate.read()
        if (byte >= 0) readBytes++
        return byte
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        val read = delegate.read(target, offset, length)
        if (read > 0) readBytes += read
        return read
    }

    override fun skip(count: Long): Long = delegate.skip(count)

    override fun available(): Int = delegate.available()
}

private fun gainFromStream(tag: ByteArray): Pair<Float?, Int> {
    val stream = CountingStream(tag)
    val header = ByteArray(Id3StreamReader.FRAME_HEADER_BYTES)
    var offset = 0
    while (offset < header.size) {
        val read = stream.read(header, offset, header.size - offset)
        if (read <= 0) break
        offset += read
    }
    val gain = Id3StreamReader.readGain(stream, header)
    return gain to stream.readBytes
}
