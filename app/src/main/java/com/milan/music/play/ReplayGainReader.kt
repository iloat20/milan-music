package com.milan.music.play

import android.content.Context
import android.util.Log
import com.milan.music.data.model.Track
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves [ReplayGainTags] for the track that is about to play.
 *
 * ## Why lazy
 *
 * Tags live in the file, and MediaStore exposes no ReplayGain column. Parsing all
 * 1 233 tracks during a library scan would add ~40 MB of reads to a scan that already
 * competes with first playback; parsing one track when it starts costs a single
 * 64 KB read on the IO dispatcher.
 *
 * ## Why the caller must not wait
 *
 * The gain arrives a few milliseconds after the transition, so the first samples of a
 * track play at the previous track's level. That is deliberate: the alternative — reset
 * to unity until the tag is known — produces an audible *rise* then fall on every
 * track change, whereas staying at the previous level produces a change of a few dB
 * that lasts under one buffer.
 *
 * ## Cache
 *
 * Keyed by `MediaStore` id and bounded to the queue in practice. A null result is
 * cached as [Float.NaN] so an untagged track is not re-read on every replay; a
 * re-tagged file therefore keeps its old value until the process restarts, which is
 * the same staleness the library cache already accepts.
 */
internal class ReplayGainReader(context: Context) {

    private val contentResolver = context.applicationContext.contentResolver

    /**
     * Concurrent on purpose: [gainFor] does its work on the IO dispatcher, so two rapid
     * skips can be inside the map at the same time. A `LinkedHashMap` here would be a
     * data race, not just a cache miss.
     *
     * No eviction: an entry is one `Long` and one `Float`, so a 5 000-track library
     * costs ~80 KB — smaller than the bookkeeping an LRU would need.
     */
    private val cache = ConcurrentHashMap<Long, Float>()

    /** The correction in dB for [track], or null when the file carries no usable tag. */
    suspend fun gainFor(track: Track): Float? = withContext(Dispatchers.IO) {
        cache[track.id]?.let { return@withContext if (it.isNaN()) null else it }
        val gain = readGain(track)
        cache[track.id] = gain ?: Float.NaN
        gain
    }

    private fun readGain(track: Track): Float? {
        val header = try {
            contentResolver.openInputStream(track.uri)?.use { stream ->
                readHead(stream)
            }
        } catch (e: Exception) {
            // A file that disappeared between scan and playback is not an error worth
            // surfacing: the track simply plays at its mastered level.
            Log.w(TAG, "replaygain read failed id=${track.id}", e)
            null
        }
        return header?.let(ReplayGainTags::parse)
    }

    /**
     * Streams the head, then resizes the window once the container is known.
     *
     * One open, one sequential read: reopening to extend the window would cost a
     * second provider round trip on the path that runs between two tracks.
     */
    private fun readHead(stream: InputStream): ByteArray {
        val head = ByteArrayOutputStream(INITIAL_CHUNK)
        val chunk = ByteArray(INITIAL_CHUNK)
        var limit = ReplayGainTags.SMALL_HEAD_BYTES
        var decided = false
        while (head.size() < limit) {
            val read = stream.read(chunk, 0, minOf(chunk.size, limit - head.size()))
            if (read <= 0) break
            head.write(chunk, 0, read)
            if (!decided && head.size() >= 10) {
                decided = true
                limit = ReplayGainTags.readLimitFor(head.toByteArray())
            }
        }
        return head.toByteArray()
    }

    private companion object {
        const val TAG = "ReplayGainReader"
        const val INITIAL_CHUNK = 8 * 1024
    }
}
