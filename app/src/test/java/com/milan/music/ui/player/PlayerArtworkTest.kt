package com.milan.music.ui.player

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 封面产物的归属规则（P0-2）。
 *
 * 缺陷是"换曲之后上一首的图还画在屏幕上"：`edgeHaze` / `heroCover` / `reflection` 曾经是
 * 三个独立 `var`，只在加载成功时写入、换曲从不清空。无内嵌封面的曲目因此走到
 * `syncCover == null` → `bandCover = heroCover`，镜面线以下铺着**上一首封面的镜像**。
 *
 * 触发条件窄，但那是"内容错误"而不是"效果差"：屏幕下方的倒影和上面那张封面不是同一张。
 * 修法是把三个画源绑到同一个 trackId 上，[PlayerArtwork.forTrack] 是唯一的判据 ——
 * 这三条用例守的就是"归属不同就必须作废"。
 *
 * 位图参数一律 `null`：这里验证的是**归属判断**，不是解码。切歌后必然有一段时间
 * 手上的产物还属于上一首，而那段时间画的必须是"没有封面"，不是"上一首的封面"。
 */
class PlayerArtworkTest {

    private fun artworkOf(trackId: Long) = PlayerArtwork(
        trackId = trackId,
        cover = null,
        mirror = null,
        haze = null,
        palette = null,
    )

    @Test
    fun `换到另一首曲目后上一首的产物不可用`() {
        val previous = artworkOf(trackId = 1L)

        assertNull(previous.forTrack(2L))
    }

    @Test
    fun `同一首曲目的产物可以反复取用`() {
        val current = artworkOf(trackId = 7L)

        assertSame(current, current.forTrack(7L))
        assertSame(current, current.forTrack(7L))
    }

    @Test
    fun `没有当前曲目时不使用任何产物`() {
        val some = artworkOf(trackId = 3L)

        assertNull(some.forTrack(null))
    }
}
