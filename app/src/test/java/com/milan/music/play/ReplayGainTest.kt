package com.milan.music.play

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ReplayGain is stored as text in three different containers. These tests build the
 * real binary shapes rather than stubbing the parser, because the failure mode that
 * matters is a *silently wrong number*: a misread length turns -6.5 dB into 6.5 dB and
 * the track gets louder instead of quieter, which no listener would report as a bug in
 * the parser.
 *
 * Every malformed-input case must return null, never throw: this runs from the player
 * on files the app did not write.
 */
class ReplayGainTest {

    @Test
    fun `flac vorbis comment carries the gain`() {
        assertEquals(-6.5f, gainOf(flac("REPLAYGAIN_TRACK_GAIN=-6.50 dB")), 0.01f)
    }

    @Test
    fun `flac without a replaygain comment yields nothing`() {
        assertNull(ReplayGainTags.parse(flac("TITLE=No Gain Here")))
    }

    @Test
    fun `the block walk stops at the last metadata block`() {
        // A second, unrelated block follows the comment block. If the walk ignored the
        // last-block flag it would keep reading past the comment into the audio frames.
        assertEquals(-1.25f, gainOf(flac("REPLAYGAIN_TRACK_GAIN=-1.25 dB")), 0.01f)
    }

    @Test
    fun `id3v2_3 txxx frame carries the gain`() {
        assertEquals(2.1f, gainOf(id3(gain = "+2.10 dB", version = 3)), 0.01f)
    }

    @Test
    fun `id3v2_4 uses syncsafe frame sizes`() {
        // v2.4 differs only in that the frame size is 7 bits per byte. Reading it as a
        // plain big-endian integer still yields a plausible number for small frames, so
        // this case has to be pinned with a size that crosses 0x7F.
        val padding = "PADDING".repeat(40)
        assertEquals(
            -9.75f,
            gainOf(id3(gain = "-9.75 dB", version = 4, extraText = padding)),
            0.01f,
        )
    }

    @Test
    fun `id3v2 without the track gain frame yields nothing`() {
        assertNull(ReplayGainTags.parse(id3(gain = null, version = 3)))
    }

    @Test
    fun `ogg vorbis comment carries the gain`() {
        assertEquals(-3.2f, gainOf(ogg("REPLAYGAIN_TRACK_GAIN=-3.20 dB")), 0.01f)
    }

    @Test
    fun `album gain is the fallback when the track gain is absent`() {
        assertEquals(-4.0f, gainOf(flac("REPLAYGAIN_ALBUM_GAIN=-4.00 dB")), 0.01f)
    }

    @Test
    fun `an unparsable value is ignored instead of guessed`() {
        assertNull(ReplayGainTags.parse(flac("REPLAYGAIN_TRACK_GAIN=loud")))
    }

    @Test
    fun `truncated input returns nothing`() {
        // The reader only sees the head of the file, so truncation is normal, not
        // exceptional: a cover-art-sized ID3 tag can push the comments past the window.
        val full = flac("REPLAYGAIN_TRACK_GAIN=-6.50 dB")
        assertNull(ReplayGainTags.parse(full.copyOf(12)))
    }

    @Test
    fun `an unknown container returns nothing`() {
        assertNull(ReplayGainTags.parse(ByteArray(64) { 0x41 }))
    }

    @Test
    fun `an empty input returns nothing`() {
        assertNull(ReplayGainTags.parse(ByteArray(0)))
    }
}

/**
 * The read window is not a constant.
 *
 * A 64 KB head is enough for FLAC and Ogg, whose comment headers sit right after the
 * stream info. An MP3 with embedded cover art can push its `TXXX` frames past that, and
 * a fixed window would then report "untagged" for every album that has artwork — the
 * most common case in a curated library. The tag itself declares its size, so the read
 * can size itself.
 */
class ReplayGainReadLimitTest {

    @Test
    fun `flac uses the small window`() {
        assertEquals(ReplayGainTags.SMALL_HEAD_BYTES, ReplayGainTags.readLimitFor(flac("TITLE=x")))
    }

    @Test
    fun `an id3 tag is read to its declared size`() {
        val tag = id3(gain = "-3.00 dB", version = 3, extraText = "X".repeat(200))
        assertEquals(10 + syncSafeValue(tag), ReplayGainTags.readLimitFor(tag))
    }

    @Test
    fun `an oversized id3 tag is capped`() {
        // The declared size is attacker-controlled in the sense that it comes from a
        // file the app did not write; a 2 GB declaration must not become a 2 GB array.
        val huge = "ID3".toByteArray() +
            byteArrayOf(3, 0, 0) +
            byteArrayOf(0x1F, 0x7F, 0x7F, 0x7F)
        assertEquals(ReplayGainTags.MAX_ID3_BYTES, ReplayGainTags.readLimitFor(huge))
    }

    @Test
    fun `truncated input falls back to the small window`() {
        assertEquals(ReplayGainTags.SMALL_HEAD_BYTES, ReplayGainTags.readLimitFor(byteArrayOf(0x49)))
    }
}

private fun syncSafeValue(tag: ByteArray): Int =
    ((tag[6].toInt() and 0x7F) shl 21) or
        ((tag[7].toInt() and 0x7F) shl 14) or
        ((tag[8].toInt() and 0x7F) shl 7) or
        (tag[9].toInt() and 0x7F)

/**
 * Conversion tests.
 *
 * ReplayGain's reference loudness is -18 LUFS, so the tag value *is* the correction in
 * dB: -6 dB means "this track is 6 dB above the reference, turn it down".
 */
class ReplayGainVolumeTest {

    @Test
    fun `zero gain is unity volume`() {
        assertEquals(1f, gainToVolume(0f), 0.0001f)
    }

    @Test
    fun `minus six decibels is about half the linear amplitude`() {
        assertEquals(0.5012f, gainToVolume(-6f), 0.001f)
    }

    @Test
    fun `positive gain is clamped and never amplifies`() {
        // Amplifying above 1.0 would clip on files that are already near full scale.
        // The decision is deliberately "only ever attenuate".
        assertEquals(1f, gainToVolume(6f), 0.0001f)
    }

    @Test
    fun `an absurd attenuation stays positive instead of collapsing to silence`() {
        // A corrupt tag such as -600 dB must not mute the track with no way back.
        assertEquals(MIN_NORMALIZED_VOLUME, gainToVolume(-600f), 0.0001f)
    }
}

private fun gainOf(header: ByteArray): Float =
    requireNotNull(ReplayGainTags.parse(header)) { "no gain parsed from fixture" }

// ---------------------------------------------------------------------------------
// Binary fixtures. Each builder emits the container exactly as a real file would,
// including the length fields the parser has to trust.
// ---------------------------------------------------------------------------------

private fun flac(comment: String): ByteArray {
    val payload = ByteArrayOutputStream().apply {
        writeLe32(4)
        write("test".toByteArray())
        writeLe32(1)
        val body = comment.toByteArray()
        writeLe32(body.size)
        write(body)
    }.toByteArray()

    return ByteArrayOutputStream().apply {
        write("fLaC".toByteArray())
        // Not last: type 4 (VORBIS_COMMENT) with the last-block flag clear.
        write(0x04)
        write(payload.size ushr 16)
        write(payload.size ushr 8)
        write(payload.size)
        write(payload)
        // A trailing block with the last-block flag set, so a walk that ignores the
        // flag keeps going here and finds nothing.
        write(0x81)
        write(0)
        write(0)
        write(4)
        write(ByteArray(4))
    }.toByteArray()
}

private fun ogg(comment: String): ByteArray {
    val comments = ByteArrayOutputStream().apply {
        writeLe32(4)
        write("test".toByteArray())
        writeLe32(1)
        val body = comment.toByteArray()
        writeLe32(body.size)
        write(body)
        write(1) // framing bit
    }.toByteArray()

    val identification = ByteArrayOutputStream().apply {
        write(0x01)
        write("vorbis".toByteArray())
        write(ByteArray(23))
    }.toByteArray()

    val commentPacket = ByteArrayOutputStream().apply {
        write(0x03)
        write("vorbis".toByteArray())
        write(comments)
    }.toByteArray()

    return ByteArrayOutputStream().apply {
        write(oggPage(identification, sequence = 0))
        write(oggPage(commentPacket, sequence = 1))
        write(oggPage(ByteArray(16), sequence = 2))
    }.toByteArray()
}

private fun oggPage(packet: ByteArray, sequence: Int): ByteArray = ByteArrayOutputStream().apply {
    write("OggS".toByteArray())
    write(0) // version
    write(0) // header type: no continued packet
    write(ByteArray(8)) // granule position
    writeLe32(1) // serial
    writeLe32(sequence)
    writeLe32(0) // crc, not validated
    // One lacing value per 255-byte chunk, plus the terminator.
    val chunks = packet.size / 255 + 1
    write(chunks)
    repeat(chunks - 1) { write(255) }
    write(packet.size % 255)
    write(packet)
}.toByteArray()

private fun id3(gain: String?, version: Int, extraText: String = ""): ByteArray {
    val frames = ByteArrayOutputStream().apply {
        if (gain != null) write(txxx("REPLAYGAIN_TRACK_GAIN", gain, version))
        if (extraText.isNotEmpty()) {
            write(txxx("COMMENT_FILLER", extraText, version))
        }
    }.toByteArray()

    return ByteArrayOutputStream().apply {
        write("ID3".toByteArray())
        write(version)
        write(0)
        write(0) // flags
        write(syncSafe(frames.size))
        write(frames)
    }.toByteArray()
}

private fun txxx(description: String, value: String, version: Int): ByteArray {
    val body = ByteArrayOutputStream().apply {
        write(0) // ISO-8859-1
        write(description.toByteArray(Charsets.ISO_8859_1))
        write(0)
        write(value.toByteArray(Charsets.ISO_8859_1))
    }.toByteArray()

    return ByteArrayOutputStream().apply {
        write("TXXX".toByteArray())
        if (version >= 4) write(syncSafe(body.size)) else writeBe32(body.size)
        write(0)
        write(0) // flags
        write(body)
    }.toByteArray()
}

private fun syncSafe(value: Int): ByteArray = byteArrayOf(
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

private fun ByteArrayOutputStream.writeLe32(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 24) and 0xFF)
}

/**
 * Every length in these formats is a 32-bit number written by whatever tagged the file,
 * and this parser runs on files the app did not create. `offset + length > end` is not a
 * safe check: with `length` at `Int.MAX_VALUE` the sum wraps negative, the comparison
 * passes, and the next read is outside the array. That surfaces as an
 * `ArrayIndexOutOfBoundsException` on the transition path, i.e. a crash while skipping
 * a track. Each case below must return null instead.
 */
class ReplayGainRobustnessTest {

    @Test
    fun `a huge vendor length returns nothing`() {
        assertNull(ReplayGainTags.parse(flacWithLengths(vendorLength = Int.MAX_VALUE, entryLength = -1)))
    }

    @Test
    fun `a huge entry length returns nothing`() {
        assertNull(ReplayGainTags.parse(flacWithLengths(vendorLength = 4, entryLength = Int.MAX_VALUE)))
    }

    @Test
    fun `a huge extended header returns nothing`() {
        assertNull(ReplayGainTags.parse(id3WithExtendedHeader(Int.MAX_VALUE)))
    }

    @Test
    fun `a huge frame length returns nothing`() {
        assertNull(ReplayGainTags.parse(id3WithFrameSize(Int.MAX_VALUE)))
    }

    @Test
    fun `a huge block length returns nothing`() {
        assertNull(ReplayGainTags.parse(flacWithBlockLength(Int.MAX_VALUE)))
    }

    /**
     * A `TXXX` frame without a null separator.
     *
     * The body layout is `encoding`, description, terminator, value. When the
     * terminator is missing — a truncated frame, or a tag written by something that
     * does not follow ID3 — the search for it returns the end of the frame, and
     * `end - split - 1` becomes `-1`. `String(byte[], int, int, Charset)` throws
     * `StringIndexOutOfBoundsException` for a negative length.
     *
     * This is reached from the playback transition path, not from a test-only
     * entry point: a corrupt tag would crash the process the moment the track
     * changed, because the parser runs outside the reader's try/catch.
     */
    @Test
    fun `txxx without a terminator returns nothing`() {
        assertNull(ReplayGainTags.parse(id3NoSeparator(encoding = 0)))
    }

    @Test
    fun `utf16 txxx without a terminator returns nothing`() {
        assertNull(ReplayGainTags.parse(id3NoSeparator(encoding = 1)))
    }

    /** An encoding byte the parser does not handle must not be read as ISO-8859-1. */
    @Test
    fun `an unknown txxx encoding returns nothing`() {
        assertNull(ReplayGainTags.parse(id3NoSeparator(encoding = 9)))
    }
}

private fun id3NoSeparator(encoding: Int): ByteArray {
    // No zero byte anywhere in the body, so no terminator can be found.
    val body = byteArrayOf(encoding.toByte()) + "REPLAYGAIN_TRACK_GAIN".toByteArray()
    val bodySize = body.size
    return ByteArrayOutputStream().apply {
        write("ID3".toByteArray())
        write(3)
        write(0)
        write(0)
        write(syncSafe(10 + bodySize))
        write("TXXX".toByteArray())
        writeBe32(bodySize)
        write(0)
        write(0)
        write(body)
    }.toByteArray()
}

private fun flacWithBlockLength(blockLength: Int): ByteArray = ByteArrayOutputStream().apply {
    write("fLaC".toByteArray())
    write(0x84)
    write(blockLength ushr 16)
    write(blockLength ushr 8)
    write(blockLength and 0xFF)
    write(ByteArray(8))
}.toByteArray()

private fun flacWithLengths(vendorLength: Int, entryLength: Int): ByteArray {
    val payload = ByteArrayOutputStream().apply {
        writeLe32(vendorLength)
        write("test".toByteArray())
        writeLe32(1)
        writeLe32(entryLength)
        write("REPLAYGAIN_TRACK_GAIN=-6.00 dB".toByteArray())
    }.toByteArray()

    return ByteArrayOutputStream().apply {
        write("fLaC".toByteArray())
        write(0x84)
        write(payload.size ushr 16)
        write(payload.size ushr 8)
        write(payload.size and 0xFF)
        write(payload)
    }.toByteArray()
}

private fun id3WithExtendedHeader(size: Int): ByteArray = ByteArrayOutputStream().apply {
    write("ID3".toByteArray())
    write(3)
    write(0)
    write(0x40) // extended header present
    write(syncSafe(4))
    writeBe32(size)
    write(ByteArray(8))
}.toByteArray()

private fun id3WithFrameSize(size: Int): ByteArray = ByteArrayOutputStream().apply {
    write("ID3".toByteArray())
    write(3)
    write(0)
    write(0)
    write(syncSafe(20))
    write("TXXX".toByteArray())
    writeBe32(size)
    write(0)
    write(0)
    write(ByteArray(10))
}.toByteArray()
