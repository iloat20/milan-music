package com.milan.music.play

import java.io.InputStream

/**
 * Walks an ID3v2 frame table directly on the stream, so a tag whose declared size is
 * dominated by cover art never has to be read into memory.
 *
 * ## Why this exists
 *
 * [ReplayGainTags.parse] takes a `ByteArray`, so the reader used to size the read from
 * the tag's own header and then load the whole tag: an MP3 carrying a 900 KB `APIC`
 * frame cost a 900 KB read *and* a second 900 KB copy inside
 * `ByteArrayOutputStream.toByteArray()`, on the transition path of every track, to find
 * a `TXXX` frame that usually sits in the first kilobyte. The frame table is
 * self-describing — id, size, flags — so the walk below reads 10 bytes per frame and
 * skips the body; only the `TXXX` body it actually needs is ever allocated.
 *
 * ## Why `skip` rather than a read loop
 *
 * `ContentResolver` hands back a stream over a file descriptor, and `skip` on that
 * reaches `lseek`. A provider whose stream cannot seek falls back to reading, which is
 * no worse than the behaviour this replaced — the memory win holds either way.
 *
 * ## What this is not
 *
 * Not a general ID3 parser: it reads exactly one thing (the ReplayGain `TXXX` frames)
 * and must agree with `ReplayGainTags.parseId3` byte for byte on that. The two share
 * [ReplayGainTags.parseUserText] and [ReplayGainTags.parseGainDb] so a decoding fix
 * cannot land in one and be forgotten in the other.
 */
internal object Id3StreamReader {

    /** `frameId` (4) + `size` (4) + `flags` (2). */
    const val FRAME_HEADER_BYTES = 10

    /**
     * How much of a tag body the walk is willing to cover.
     *
     * This is a budget, not an allocation: the declared size comes from a file the app
     * did not write, so a 2 GB declaration has to stop the walk rather than size an
     * array. Reaching it means the tag is corrupt or hostile; the track then plays at
     * its mastered level.
     */
    const val MAX_TAG_BYTES = 1024 * 1024

    /**
     * The gain carried by the tag whose first [FRAME_HEADER_BYTES] are [header], or null
     * when it carries none, is malformed, or ends before the frame that holds it.
     *
     * [stream] must be positioned immediately after [header].
     */
    fun readGain(stream: InputStream, header: ByteArray): Float? {
        if (header.size < FRAME_HEADER_BYTES) return null
        val version = header[3].toInt() and 0xFF
        // v2.2 uses three-character frame ids and a different TXXX layout ("TXX");
        // walking it with the v2.3 rules below would read an id off by one byte and a
        // size from the wrong offset. A wrong gain is worse than none.
        if (version != 3 && version != 4) return null
        val flags = header[5].toInt() and 0xFF
        val declared = readSyncSafe(header, 6) ?: return null
        var remaining = declared.coerceAtMost(MAX_TAG_BYTES)

        if (flags and 0x40 != 0) {
            val consumed = skipExtendedHeader(stream, version, remaining) ?: return null
            remaining -= consumed
        }

        var trackGain: Float? = null
        var albumGain: Float? = null
        val frameHeader = ByteArray(FRAME_HEADER_BYTES)

        while (remaining >= FRAME_HEADER_BYTES) {
            if (!readFully(stream, frameHeader, FRAME_HEADER_BYTES)) return null
            // Zero padding marks the end of the frame table.
            if (frameHeader[0].toInt() == 0) break
            val frameId = String(frameHeader, 0, 4, Charsets.ISO_8859_1)
            // v2.4 frame sizes are syncsafe (7 bits per byte) while v2.3 are plain
            // big-endian; for a frame under 128 bytes both decodings agree, which is
            // why a wrong branch passes a naive fixture and corrupts every larger tag.
            val frameSize = if (version >= 4) {
                readSyncSafe(frameHeader, 4)
            } else {
                readBe32(frameHeader, 4)
            } ?: return null
            // A non-positive size would leave the walk where it is, spinning forever on
            // the IO dispatcher; one larger than the tag would move the next frame id
            // outside it. Both stop the walk instead.
            if (frameSize <= 0 || frameSize > remaining - FRAME_HEADER_BYTES) return null
            remaining -= FRAME_HEADER_BYTES + frameSize

            if (frameId == "TXXX") {
                val body = ByteArray(frameSize)
                if (!readFully(stream, body, frameSize)) return null
                val parsed = ReplayGainTags.parseUserText(body, 0, frameSize)
                if (parsed != null) {
                    val (description, value) = parsed
                    when (description.lowercase()) {
                        ReplayGainTags.TRACK_GAIN_KEY -> trackGain = ReplayGainTags.parseGainDb(value)
                        ReplayGainTags.ALBUM_GAIN_KEY -> albumGain = ReplayGainTags.parseGainDb(value)
                    }
                }
            } else if (!skipFully(stream, frameSize)) {
                // The file ended inside a frame body: the tag is truncated, so nothing
                // after this point can be trusted either.
                return null
            }
        }
        // Track gain wins; album gain is the fallback for rips tagged album-wide only.
        return trackGain ?: albumGain
    }

    /**
     * Consumes the extended header, returning how many bytes it took.
     *
     * v2.4's size covers the whole header including its own four bytes; v2.3's excludes
     * them. Both values come from a file the app did not write, so an oversized one is
     * rejected before it can move the position past the tag.
     */
    private fun skipExtendedHeader(stream: InputStream, version: Int, remaining: Int): Int? {
        val sizeField = ByteArray(4)
        if (!readFully(stream, sizeField, 4)) return null
        if (version >= 4) {
            val size = readSyncSafe(sizeField, 0) ?: return null
            if (size < ReplayGainTags.MIN_V2_4_EXTENDED_HEADER || size > remaining) return null
            return if (skipFully(stream, size - 4)) size else null
        }
        val size = readBe32(sizeField, 0) ?: return null
        if (size < 0 || size > remaining - 4) return null
        return if (skipFully(stream, size)) 4 + size else null
    }

    private fun readFully(stream: InputStream, target: ByteArray, length: Int): Boolean {
        var offset = 0
        while (offset < length) {
            val read = stream.read(target, offset, length - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }

    /** `skip` may move fewer bytes than asked and may return 0 without being at EOF. */
    private fun skipFully(stream: InputStream, count: Int): Boolean {
        var remaining = count.toLong()
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            if (stream.read() < 0) return false
            remaining--
        }
        return true
    }
}
