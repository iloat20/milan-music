package com.milan.music.play

import androidx.media3.common.Player
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSnapshotTest {

    @Test
    fun `restore keeps the current track when earlier tracks were deleted`() {
        val mapped = mapRestoredQueue(
            trackIds = listOf(10L, 20L, 30L, 40L),
            currentIndex = 2,
            availableIds = setOf(20L, 30L, 40L),
        )

        assertEquals(listOf(20L, 30L, 40L), mapped?.trackIds)
        assertEquals(1, mapped?.startIndex)
    }

    @Test
    fun `restore does not coerce the old index onto the new list`() {
        // Current track (10) is gone; index 2 now points at 40. Resuming at 40 would
        // silently play a different song, so the snapshot must be rejected instead.
        val mapped = mapRestoredQueue(
            trackIds = listOf(10L, 20L, 30L),
            currentIndex = 0,
            availableIds = setOf(20L, 30L),
        )

        assertNull(mapped)
    }

    @Test
    fun `restore rejects an empty snapshot`() {
        assertNull(mapRestoredQueue(emptyList(), 0, setOf(1L)))
    }

    @Test
    fun `restore rejects an out of range index`() {
        assertNull(mapRestoredQueue(listOf(1L, 2L), 5, setOf(1L, 2L)))
        assertNull(mapRestoredQueue(listOf(1L, 2L), -1, setOf(1L, 2L)))
    }

    @Test
    fun `restore rejects a queue whose tracks are all gone`() {
        assertNull(mapRestoredQueue(listOf(1L, 2L), 0, emptySet()))
    }

    @Test
    fun `snapshot json round trips`() {
        val snapshot = PlaybackSnapshot(
            trackIds = listOf(1L, 2L, 3L),
            currentIndex = 1,
            positionMs = 12_345L,
            shuffleModeEnabled = false,
            repeatMode = Player.REPEAT_MODE_ONE,
            wasPlaying = true,
            current = SnapshotTrack(
                id = 2L,
                uri = "content://media/external/audio/media/2",
                title = "标题",
                artist = "艺人",
                album = "专辑",
                albumId = 7L,
                durationMs = 180_000L,
                albumArtist = "合辑艺人",
                year = 2024,
                trackNumber = 3,
            ),
            updatedAtMs = 1_700_000_000_000L,
        )

        val raw = Json.encodeToString(PlaybackSnapshot.serializer(), snapshot)
        val decoded = Json.decodeFromString(PlaybackSnapshot.serializer(), raw)

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `snapshot written before rich metadata still decodes`() {
        // Shape written by 1.0.64: no albumArtist / year / trackNumber on the current
        // item. Those fields must default instead of failing the whole restore.
        val raw = """
            {"trackIds":[7],"currentIndex":0,"positionMs":1000,
             "shuffleModeEnabled":true,"repeatMode":0,"wasPlaying":true,"updatedAtMs":1,
             "current":{"id":7,"uri":"content://media/external/audio/media/7",
             "title":"标题","artist":"艺人","album":"专辑","albumId":1,"durationMs":2000}}
        """.trimIndent()

        val decoded = Json.decodeFromString(PlaybackSnapshot.serializer(), raw)

        assertEquals(7L, decoded.current?.id)
        assertEquals("", decoded.current?.albumArtist)
        assertEquals(0, decoded.current?.year ?: -1)
        assertEquals(0, decoded.current?.trackNumber ?: -1)
    }

    @Test
    fun `unknown repeat modes fall back to repeat all`() {
        assertEquals(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_OFF.validRepeatMode())
        assertEquals(Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ONE.validRepeatMode())
        assertEquals(Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ALL.validRepeatMode())
        assertEquals(Player.REPEAT_MODE_ALL, 99.validRepeatMode())
        assertEquals(Player.REPEAT_MODE_ALL, (-1).validRepeatMode())
    }

    @Test
    fun `a failed snapshot write is contained`() = runBlocking {
        var reported: Throwable? = null

        val saved = saveSnapshotSafely("queue", { _, failure -> reported = failure }) {
            throw IOException("disk full")
        }

        assertFalse(saved)
        assertTrue(reported is IOException)
    }

    @Test(expected = CancellationException::class)
    fun `snapshot writes still honour cancellation`() = runBlocking {
        saveSnapshotSafely("queue", { _, _ -> }) {
            throw CancellationException("service stopping")
        }
        Unit
    }
}
