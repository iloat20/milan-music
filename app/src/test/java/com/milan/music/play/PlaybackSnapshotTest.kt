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

    /**
     * 位置带着"属于第几项"落盘之后，冷启动不会把上一首的时间戳套在新歌上。
     *
     * 队列与位置分居两个 store，每个写入方都是两步。切歌把 index 写成 4、位置那一步
     * 还没落地时，ticker 带着**上一首**（index 3）的 5 秒位置后落地 —— 它的时间戳
     * 更新，旧判据（纯时间戳）会采信它，于是冷启动从新歌第 5 秒开始播。
     */
    @Test
    fun `a position written for another item is never resumed`() {
        assertFalse(
            positionMatchesQueue(
                queueIndex = 4,
                queueUpdatedAtMs = 1_000L,
                positionIndex = 3,
                positionUpdatedAtMs = 1_500L,
            ),
        )
    }

    @Test
    fun `a position for the same item survives a newer queue write`() {
        assertTrue(
            positionMatchesQueue(
                queueIndex = 4,
                queueUpdatedAtMs = 1_000L,
                positionIndex = 4,
                positionUpdatedAtMs = 1_500L,
            ),
        )
    }

    /**
     * 时间戳仍然是第二道闸：同一个 index 上换了歌（重扫后重排）时，只有它能把
     * 旧位置挡下来。index 不在快照里的老格式也走这条。
     */
    @Test
    fun `the timestamp still guards the same index`() {
        assertFalse("位置比队列旧：位置属于换队列之前的那首", positionMatchesQueue(2, 1_000L, 2, 900L))
        assertTrue("位置比队列新：正常 ticker", positionMatchesQueue(2, 1_000L, 2, 1_100L))
    }

    @Test
    fun `a snapshot written before position_index existed falls back to timestamps`() {
        assertTrue(positionMatchesQueue(4, 1_000L, null, 1_500L))
        assertFalse(positionMatchesQueue(4, 1_000L, null, 900L))
    }

    /**
     * 全量写跨两个文件：ids 先落盘、游标后落盘。进程在中间被杀时，新 ids 配旧游标，
     * 旧 index 可能指向另一首。revision 是唯一精确判据。
     */
    @Test
    fun `a cursor is only used when it belongs to the stored queue`() {
        assertTrue(cursorBelongsToQueue(queueRevision = 7L, cursorRevision = 7L))
        assertFalse(cursorBelongsToQueue(queueRevision = 7L, cursorRevision = 6L))
        assertFalse(cursorBelongsToQueue(queueRevision = 7L, cursorRevision = null))
    }

    @Test
    fun `a snapshot without a revision still trusts its cursor`() {
        // 旧布局里游标和 ids 在同一个文件里，天然原子，没有 revision 可比。
        assertTrue(cursorBelongsToQueue(queueRevision = null, cursorRevision = null))
        assertTrue(cursorBelongsToQueue(queueRevision = null, cursorRevision = 3L))
    }

    @Test
    fun `a revision increases even when the clock moves backwards`() {
        assertEquals(1_001L, bumpRevision(previous = 1_000L, now = 900L))
        assertEquals(1_001L, bumpRevision(previous = 1_000L, now = 1_000L))
        assertEquals(2_000L, bumpRevision(previous = 1_000L, now = 2_000L))
    }
}
