package com.milan.music.play

import kotlin.math.pow

/**
 * Reads ReplayGain from the tags a local library actually carries.
 *
 * ## Why attenuate only
 *
 * ReplayGain's reference is -18 LUFS, so the tag value is the correction in dB: a
 * track tagged `-6.50 dB` sits 6.5 dB above the reference and is turned down. Positive
 * values would ask for gain above unity, which clips on material that is already near
 * full scale and would need a limiter. Bounding the result at 1.0 means the app only
 * ever attenuates: loud tracks come down, quiet tracks stay as the artist mastered
 * them. Half the perceived benefit, none of the clipping risk, no DSP stage at all.
 *
 * ## Why `Player.volume`
 *
 * The correction is one constant per track, so `player.volume` already is the right
 * control — it scales samples before the sink on the `AudioTrack` path. A custom
 * `AudioProcessor` would add a per-sample multiply, a limiter, and a permanent
 * incompatible-with-offload constraint, to gain nothing that a scalar can do.
 *
 * ## What is not supported
 *
 * MP4/M4A stores tags in `moov`, which can sit at the end of the file, so a head-only
 * read cannot see it. Those files return null and play at their mastered level.
 */
internal object ReplayGainTags {

    /** How much to read from a file whose container puts its comments near the start. */
    const val SMALL_HEAD_BYTES = 64 * 1024

    /** Ceiling for an ID3v2 tag that declares a larger size than [SMALL_HEAD_BYTES]. */
    const val MAX_ID3_BYTES = 1024 * 1024

    /**
     * How many bytes [parse] needs, given the first few bytes of the file.
     *
     * FLAC and Ogg put their comments immediately after the stream info, so a fixed
     * small window covers them. ID3v2 declares its own size and frequently carries
     * cover art ahead of the `TXXX` frames, which is exactly the case a fixed window
     * would miss — on a library where every album has artwork, that would mean
     * ReplayGain silently applied to almost nothing.
     *
     * Capped because the declared size comes from the file: a corrupt or hostile header
     * must not turn into a matching allocation.
     */
    fun readLimitFor(head: ByteArray): Int {
        if (head.size < 10 || !head.startsWith(0, "ID3")) return SMALL_HEAD_BYTES
        val tagSize = readSyncSafe(head, 6) ?: return SMALL_HEAD_BYTES
        return (tagSize + 10).coerceAtMost(MAX_ID3_BYTES)
    }

    private const val FLAC_VORBIS_COMMENT_BLOCK = 4
    private const val TRACK_GAIN_KEY = "replaygain_track_gain"
    private const val ALBUM_GAIN_KEY = "replaygain_album_gain"

    /**
     * Smallest valid ID3v2.4 extended header: four bytes of syncsafe size plus the
     * flag-byte count and flags. The size field includes itself, unlike v2.3.
     */
    private const val MIN_V2_4_EXTENDED_HEADER = 6

    /** Guards against a corrupt tag asking for an inaudible or absurd correction. */
    private const val MAX_PLAUSIBLE_DB = 60f

    fun parse(header: ByteArray): Float? = when {
        header.size < 4 -> null
        header.startsWith(0, "fLaC") -> parseFlac(header)
        header.startsWith(0, "ID3") -> parseId3(header)
        header.startsWith(0, "OggS") -> parseOgg(header)
        else -> null
    }

    // --- FLAC ---------------------------------------------------------------------

    /**
     * Walks the metadata block chain from byte 4.
     *
     * The last-block flag is obeyed rather than ignored: past the last block the bytes
     * are audio frames, and reading a frame length as a block length would walk into
     * arbitrary data looking for a comment header.
     */
    private fun parseFlac(bytes: ByteArray): Float? {
        var offset = 4
        while (offset + 4 <= bytes.size) {
            val header = bytes[offset].toInt() and 0xFF
            val isLast = header and 0x80 != 0
            val type = header and 0x7F
            val length = readBe24(bytes, offset + 1) ?: return null
            val dataStart = offset + 4
            // Subtraction, not `dataStart + length > size`: the latter overflows for a
            // declared length near Int.MAX_VALUE and lets the read through.
            if (length > bytes.size - dataStart) return null
            if (type == FLAC_VORBIS_COMMENT_BLOCK) {
                return parseVorbisComment(bytes, dataStart, dataStart + length)
            }
            if (isLast) return null
            offset = dataStart + length
        }
        return null
    }

    // --- Ogg / Opus ---------------------------------------------------------------

    /**
     * Finds the comment packet among the leading pages.
     *
     * Identified by content (`\x03vorbis` for Vorbis, `OpusTags` for Opus) rather than
     * by packet index: the index depends on the codec's header count, the marker does
     * not. Only packets that fit inside a single page are considered — the comment
     * header always does, and stitching a continued packet would be guessing.
     */
    private fun parseOgg(bytes: ByteArray): Float? {
        var offset = 0
        while (offset + 27 <= bytes.size) {
            if (!bytes.startsWith(offset, "OggS")) return null
            val headerType = bytes[offset + 5].toInt() and 0xFF
            val segmentCount = bytes[offset + 26].toInt() and 0xFF
            val segmentTable = offset + 27
            val dataStart = segmentTable + segmentCount
            if (dataStart > bytes.size) return null
            var dataLength = 0
            for (i in 0 until segmentCount) {
                dataLength += bytes[segmentTable + i].toInt() and 0xFF
            }
            if (dataLength > bytes.size - dataStart) return null
            if (headerType and 0x01 == 0) {
                val payloadOffset = when {
                    bytes.startsWith(dataStart, "\u0003vorbis") -> dataStart + 7
                    bytes.startsWith(dataStart, "OpusTags") -> dataStart + 8
                    else -> -1
                }
                if (payloadOffset > 0) {
                    return parseVorbisComment(bytes, payloadOffset, dataStart + dataLength)
                }
            }
            offset = dataStart + dataLength
        }
        return null
    }

    // --- Shared Vorbis comment body ------------------------------------------------

    /**
     * Body layout is identical for FLAC's block and Ogg's comment header:
     * `vendorLen`, vendor, `count`, then `len`-prefixed `KEY=VALUE` strings. All
     * lengths are little-endian here — that is the format, not a preference.
     */
    private fun parseVorbisComment(bytes: ByteArray, start: Int, end: Int): Float? {
        var offset = start
        val vendorLength = readLe32(bytes, offset, end) ?: return null
        if (vendorLength > end - offset - 4) return null
        offset += 4 + vendorLength
        if (offset + 4 > end) return null
        val count = readLe32(bytes, offset, end) ?: return null
        offset += 4

        var trackGain: Float? = null
        var albumGain: Float? = null
        repeat(count) {
            val length = readLe32(bytes, offset, end) ?: return null
            offset += 4
            if (length > end - offset) return null
            val entry = String(bytes, offset, length, Charsets.UTF_8)
            offset += length
            val separator = entry.indexOf('=')
            if (separator <= 0) return@repeat
            when (entry.substring(0, separator).lowercase()) {
                TRACK_GAIN_KEY -> trackGain = parseGainDb(entry.substring(separator + 1))
                ALBUM_GAIN_KEY -> albumGain = parseGainDb(entry.substring(separator + 1))
            }
        }
        // Track gain wins; album gain is the fallback for rips tagged album-wide only.
        return trackGain ?: albumGain
    }

    // --- ID3v2 --------------------------------------------------------------------

    /**
     * Reads `TXXX` frames looking for the two gain descriptions.
     *
     * v2.4 frame sizes are syncsafe (7 bits per byte) while v2.3 and earlier are plain
     * big-endian. For a frame under 128 bytes both decodings agree, which is why a
     * wrong branch would pass a naive test and corrupt every larger tag.
     */
    private fun parseId3(bytes: ByteArray): Float? {
        if (bytes.size < 10) return null
        val version = bytes[3].toInt() and 0xFF
        // v2.2 uses three-character frame ids and a different TXXX layout ("TXX");
        // parsing it as v2.3 would read a frame id that is off by one byte and a size
        // taken from the wrong offset — a wrong gain is worse than none.
        if (version != 3 && version != 4) return null
        val flags = bytes[5].toInt() and 0xFF
        val tagSize = readSyncSafe(bytes, 6) ?: return null
        var offset = 10
        val tagEnd = minOf(10 + tagSize, bytes.size)

        if (flags and 0x40 != 0) {
            // v2.4's size covers the whole extended header, including its own four
            // bytes; v2.3's excludes them. Both values come from a file the app did not
            // write, so a negative or oversized one must be rejected before it can move
            // `offset` backwards — a negative index crashes in the frame loop below.
            if (version >= 4) {
                val extendedSize = readSyncSafe(bytes, offset) ?: return null
                if (extendedSize < MIN_V2_4_EXTENDED_HEADER || extendedSize > tagEnd - offset) {
                    return null
                }
                offset += extendedSize
            } else {
                val extendedSize = readBe32(bytes, offset) ?: return null
                if (extendedSize < 0 || extendedSize > tagEnd - offset - 4) return null
                offset += 4 + extendedSize
            }
        }

        var trackGain: Float? = null
        var albumGain: Float? = null
        while (offset <= tagEnd - 10) {
            val frameId = String(bytes, offset, 4, Charsets.ISO_8859_1)
            // Zero padding marks the end of the frame table.
            if (frameId[0] == '\u0000') break
            val frameSize = if (version >= 4) {
                readSyncSafe(bytes, offset + 4)
            } else {
                readBe32(bytes, offset + 4)
            } ?: return null
            val dataStart = offset + 10
            // A non-positive size is malformed. The common corrupt value -10 would
            // leave `offset` exactly where it was, spinning forever on the IO
            // dispatcher; a larger negative one would move the next frame id before the
            // array. Both must stop the walk rather than move it backwards.
            if (frameSize <= 0 || frameSize > tagEnd - dataStart) return null
            if (frameId == "TXXX") {
                val parsed = parseUserText(bytes, dataStart, dataStart + frameSize)
                if (parsed != null) {
                    val (description, value) = parsed
                    when (description.lowercase()) {
                        TRACK_GAIN_KEY -> trackGain = parseGainDb(value)
                        ALBUM_GAIN_KEY -> albumGain = parseGainDb(value)
                    }
                }
            }
            offset = dataStart + frameSize
        }
        return trackGain ?: albumGain
    }

    /**
     * TXXX body: `encoding`, description, terminator, value.
     *
     * The terminator width follows the encoding: one byte for ISO-8859-1 and UTF-8, two
     * for UTF-16. Treating a UTF-16 description as single-byte text would split it at
     * the first zero byte of the BOM.
     *
     * A missing terminator is a malformed frame and returns null. The searches below
     * report "not found" as `end`, so without the explicit check the value slice would
     * have a negative length and `String(bytes, offset, length, charset)` would throw
     * `StringIndexOutOfBoundsException`. That exception is not caught anywhere above
     * this call: it would surface as a crash on the track-transition path.
     */
    private fun parseUserText(bytes: ByteArray, start: Int, end: Int): Pair<String, String>? {
        if (start >= end) return null
        val encoding = bytes[start].toInt() and 0xFF
        val bodyStart = start + 1
        return when (encoding) {
            0, 3 -> {
                val charset = if (encoding == 3) Charsets.UTF_8 else Charsets.ISO_8859_1
                val split = indexOfZero(bytes, bodyStart, end)
                if (split >= end) return null
                val description = String(bytes, bodyStart, split - bodyStart, charset)
                val value = String(bytes, split + 1, end - split - 1, charset)
                description to value
            }
            1, 2 -> {
                val split = indexOfDoubleZero(bytes, bodyStart, end)
                if (split + 2 > end) return null
                val description = String(
                    bytes,
                    bodyStart,
                    split - bodyStart,
                    if (encoding == 1) Charsets.UTF_16 else Charsets.UTF_16BE,
                )
                val value = String(
                    bytes,
                    split + 2,
                    end - split - 2,
                    if (encoding == 1) Charsets.UTF_16 else Charsets.UTF_16BE,
                )
                description to value
            }
            else -> null
        }
    }

    // --- Value parsing ------------------------------------------------------------

    /**
     * `"-6.50 dB"` and `"-6.50"` both mean -6.5; anything else is a tag we do not
     * understand and must not act on.
     */
    private fun parseGainDb(text: String): Float? {
        val trimmed = text.trim()
        val number = trimmed.takeWhile { it.isDigit() || it == '+' || it == '-' || it == '.' }
        val value = number.toFloatOrNull() ?: return null
        if (value.isNaN()) return null
        if (value > MAX_PLAUSIBLE_DB || value < -MAX_PLAUSIBLE_DB) return null
        return value
    }
}

/**
 * Linear gain for a ReplayGain correction, bounded to `[MIN_NORMALIZED_VOLUME, 1]`.
 *
 * The lower bound is not cosmetic: a corrupt `-600 dB` tag would otherwise multiply the
 * signal by 1e-30, i.e. silence, with no audible clue that a tag is why.
 */
internal fun gainToVolume(gainDb: Float): Float {
    if (gainDb.isNaN() || gainDb.isInfinite()) return 1f
    return 10f.pow(gainDb / 20f).coerceIn(MIN_NORMALIZED_VOLUME, 1f)
}

/** Floor for [gainToVolume]; -26 dB, audible but unmistakably quiet. */
internal const val MIN_NORMALIZED_VOLUME = 0.05f

// --- Byte readers -------------------------------------------------------------------

private fun ByteArray.startsWith(offset: Int, ascii: String): Boolean {
    if (offset + ascii.length > size) return false
    for (i in ascii.indices) {
        if (this[offset + i].toInt() and 0xFF != ascii[i].code) return false
    }
    return true
}

private fun readBe24(bytes: ByteArray, offset: Int): Int? {
    if (offset + 3 > bytes.size) return null
    return ((bytes[offset].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        (bytes[offset + 2].toInt() and 0xFF)
}

private fun readBe32(bytes: ByteArray, offset: Int): Int? {
    if (offset + 4 > bytes.size) return null
    return ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
}

private fun readLe32(bytes: ByteArray, offset: Int, end: Int): Int? {
    if (offset + 4 > end) return null
    val value = (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    return if (value < 0) null else value
}

private fun readSyncSafe(bytes: ByteArray, offset: Int): Int? {
    if (offset + 4 > bytes.size) return null
    var value = 0
    for (i in 0 until 4) {
        val byte = bytes[offset + i].toInt() and 0xFF
        // High bit set means this is not a syncsafe integer; refuse instead of
        // producing a length that would walk off the tag.
        if (byte and 0x80 != 0) return null
        value = (value shl 7) or byte
    }
    return value
}

private fun indexOfZero(bytes: ByteArray, start: Int, end: Int): Int {
    for (i in start until end) {
        if (bytes[i].toInt() == 0) return i
    }
    return end
}

private fun indexOfDoubleZero(bytes: ByteArray, start: Int, end: Int): Int {
    var i = start
    while (i + 1 < end) {
        if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0) return i
        i += 2
    }
    return end
}
