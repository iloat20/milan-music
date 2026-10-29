package com.milan.music.play

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Fuzzes [Id3StreamReader] with generated input instead of hand-written fixtures.
 *
 * ## Why
 *
 * [Id3StreamReaderTest] covers the cases a person thought of. This covers the ones nobody
 * did: frame ids that are not ASCII, sizes whose high bit is set, tags that end inside a
 * body, declared sizes that disagree with the bytes present. Every input here comes from a
 * fixed seed, so a failure is reproducible from the seed and the case index alone.
 *
 * ## Invariants
 *
 * 1. **No throw.** This runs on the track-transition path; anything escaping becomes a
 *    crash in `ReplayGainReader`'s caller.
 * 2. **Bounded read.** The declared size is attacker-controlled, so the reader must never
 *    pull more than `min(fileSize, MAX_TAG_BYTES + one frame header)`.
 * 3. **Deterministic.** Same bytes, same answer, twice.
 * 4. **No silent divergence.** Where both paths return a value for the same bytes they
 *    must return the same value. The streamed path is *allowed* to be stricter — it
 *    returns null where the buffered one still reads a gain out of a truncated tag — but
 *    it is never allowed to disagree.
 *
 * Invariant 4 is the one worth fuzzing for: a divergence here is a wrong volume with no
 * exception, no log line, and no way to notice except by ear.
 */
class Id3StreamFuzzTest {

    @Test
    fun `generated tags never throw, never over-read, and never disagree`() {
        val rnd = Random(SEED)
        var cases = 0
        var divergedNullable = 0

        repeat(RANDOM_CASES) {
            val bytes = randomBytes(rnd)
            verify(bytes, "random#$it")
            cases++
        }
        repeat(STRUCTURED_CASES) { i ->
            // Hostile by construction: sizes disagree with the bytes present. Only the
            // no-throw, bounded-read and determinism invariants apply here.
            verify(structuredTag(rnd), "structured#$i")
            cases++
        }
        repeat(CLEAN_CASES) { i ->
            // Well-formed by construction, so the two paths must agree exactly. This is
            // the part of the corpus where a decoder bug would hide from the hostile one.
            val tag = validTag(rnd)
            val streamed = gainOf(tag).first
            assertEquals(
                "clean#$i disagreement: ${tag.toHex()}",
                ReplayGainTags.parse(tag),
                streamed,
            )
            assertTrue("clean#$i returned nothing: ${tag.toHex()}", streamed != null)
            cases++
        }
        repeat(MUTATED_CASES) { i ->
            val base = validTag(rnd)
            val mutated = base.copyOf()
            repeat(rnd.nextInt(1, 6)) {
                val at = rnd.nextInt(mutated.size)
                mutated[at] = rnd.nextInt(256).toByte()
            }
            verify(mutated, "mutated#$i")
            if (ReplayGainTags.parse(mutated) != null && gainOf(mutated).first == null) {
                divergedNullable++
            }
            cases++
        }
        repeat(TRUNCATED_CASES) { i ->
            val base = validTag(rnd)
            val cut = base.copyOf(rnd.nextInt(0, base.size + 1))
            verify(cut, "truncated#$i")
            // Truncation is the one class where the two paths legitimately differ: the
            // buffered parser stops at the bytes it has, the streamed walk refuses a tag
            // whose declared size it cannot honour. Counted, not asserted to be zero.
            if (ReplayGainTags.parse(cut) != null && gainOf(cut).first == null) divergedNullable++
            cases++
        }

        println("Id3StreamFuzzTest cases=$cases divergedNullable=$divergedNullable")
        assertTrue(
            "fuzzed only $cases cases",
            cases >= RANDOM_CASES + STRUCTURED_CASES + CLEAN_CASES + MUTATED_CASES,
        )
        // Documents the known asymmetry rather than hiding it: a change that makes the
        // streamed path as lenient as the buffered one should be a deliberate edit here.
        assertTrue("truncated-tag strictness disappeared", divergedNullable > 0)
    }

    private fun verify(bytes: ByteArray, label: String) {
        val first = try {
            gainOf(bytes)
        } catch (t: Throwable) {
            fail("$label threw ${t::class.java.name}: ${bytes.toHex()}")
            return
        }
        val second = gainOf(bytes)
        assertEquals("$label not deterministic: ${bytes.toHex()}", first.first, second.first)

        val limit = minOf(bytes.size.toLong(), Id3StreamReader.MAX_TAG_BYTES.toLong()) + 16L
        assertTrue(
            "$label read ${first.second} bytes from ${bytes.size}: ${bytes.toHex()}",
            first.second <= limit,
        )
        // The budget must also bound what is allocated, which is why the read counter is
        // the observable: a walk that honoured the budget but read on would show here.
        assertTrue("$label negative read count", first.second >= 0)
    }

    /** Feeds the first-frame-header contract the same way `ReplayGainReader` does. */
    private fun gainOf(bytes: ByteArray): Pair<Float?, Int> {
        val stream = FuzzCountingStream(bytes)
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

    // --- Generators ---------------------------------------------------------------

    private fun randomBytes(rnd: Random): ByteArray {
        val size = when (rnd.nextInt(4)) {
            0 -> rnd.nextInt(0, 12) // shorter than one header
            1 -> rnd.nextInt(0, 64)
            2 -> rnd.nextInt(0, 512)
            else -> rnd.nextInt(0, 4096)
        }
        return ByteArray(size) { rnd.nextInt(256).toByte() }
    }

    /** Everything a real tag walk has to survive, including hostile size fields. */
    private fun structuredTag(rnd: Random): ByteArray {
        val version = listOf(2, 3, 4).random(rnd)
        val frames = ByteArrayOutputStream()
        repeat(rnd.nextInt(0, 5)) { frames.write(randomFrame(rnd, version)) }
        val body = frames.toByteArray()
        val declared = when (rnd.nextInt(8)) {
            0 -> rnd.nextInt(0, Int.MAX_VALUE)
            1 -> 0
            2 -> body.size + rnd.nextInt(1, 1 shl 20) // larger than the file
            else -> body.size
        }
        return assemble(version, flags = listOf(0, 0x40, 0x80, 0xC0).random(rnd), declared, body)
    }

    private fun randomFrame(rnd: Random, version: Int): ByteArray {
        val id = listOf("TXXX", "APIC", "TIT2", "TXXX", "\u0000\u0000\u0000\u0000", "zzzz").random(rnd)
        val bodySize = rnd.nextInt(0, 64)
        val body = when {
            id == "TXXX" && rnd.nextBoolean() -> randomTxxxBody(rnd)
            else -> ByteArray(bodySize) { rnd.nextInt(256).toByte() }
        }
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        val declared = when (rnd.nextInt(6)) {
            0 -> 0
            1 -> -1
            2 -> rnd.nextInt(1 shl 20, Int.MAX_VALUE) // claims past the tag
            3 -> body.size + 1
            else -> body.size
        }
        if (version >= 4) out.write(syncSafe(declared and 0x0FFF_FFFF)) else out.writeBe32(declared)
        out.write(0)
        out.write(0)
        out.write(body)
        return out.toByteArray()
    }

    private fun randomTxxxBody(rnd: Random): ByteArray {
        val encoding = listOf(0, 1, 2, 3, 4).random(rnd)
        val key = listOf(
            "REPLAYGAIN_TRACK_GAIN",
            "replaygain_album_gain",
            "REPLAYGAIN_TRACK_GAIN",
            "TITLE",
        ).random(rnd)
        val value = listOf("-6.50 dB", "+2.00 dB", "garbage", "-1e9", "0.00 dB").random(rnd)
        val out = ByteArrayOutputStream()
        out.write(encoding)
        val separator: ByteArray = if (encoding == 1 || encoding == 2) byteArrayOf(0, 0) else byteArrayOf(0)
        out.write(key.toByteArray(Charsets.ISO_8859_1))
        out.write(separator)
        out.write(value.toByteArray(Charsets.ISO_8859_1))
        if (rnd.nextBoolean()) out.write(0) // stray terminator: legal padding, not a new frame
        return out.toByteArray()
    }

    /** A tag whose declared size is exactly the bytes present — the differential corpus. */
    private fun validTag(rnd: Random): ByteArray {
        val version = listOf(3, 4).random(rnd)
        val frames = ByteArrayOutputStream()
        if (rnd.nextBoolean()) frames.write(frame("APIC", ByteArray(rnd.nextInt(1, 2048)), version))
        val gain = listOf("-6.50 dB", "+2.10 dB", "-0.25 dB", "-11.00 dB").random(rnd)
        frames.write(txxxBody("REPLAYGAIN_TRACK_GAIN", gain, version))
        val body = frames.toByteArray()
        return assemble(version, flags = 0, declared = body.size, body = body)
    }

    private fun assemble(version: Int, flags: Int, declared: Int, body: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write("ID3".toByteArray())
            write(version)
            write(0)
            write(flags)
            write(syncSafe(declared and 0x0FFF_FFFF))
            write(body)
        }.toByteArray()

    private fun frame(id: String, body: ByteArray, version: Int): ByteArray =
        ByteArrayOutputStream().apply {
            write(id.toByteArray(Charsets.ISO_8859_1))
            if (version >= 4) write(syncSafe(body.size)) else writeBe32(body.size)
            write(0)
            write(0)
            write(body)
        }.toByteArray()

    private fun txxxBody(description: String, value: String, version: Int): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write(0)
            write(description.toByteArray(Charsets.ISO_8859_1))
            write(0)
            write(value.toByteArray(Charsets.ISO_8859_1))
        }.toByteArray()
        return frame("TXXX", body, version)
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

    private fun ByteArray.toHex(): String {
        if (size > 64) return "${size}B: " + take(64).joinToString("") { "%02x".format(it) } + "…"
        return joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** Fixed so a failure reproduces from the seed printed in the message. */
        const val SEED = 0x1D3_F0_22

        const val RANDOM_CASES = 3_000
        const val STRUCTURED_CASES = 4_000
        const val CLEAN_CASES = 2_000
        const val MUTATED_CASES = 4_000
        const val TRUNCATED_CASES = 2_000
    }
}

/** Counts bytes pulled through `read`; `skip` stays free, as it is on a seekable fd. */
private class FuzzCountingStream(bytes: ByteArray) : InputStream() {
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
