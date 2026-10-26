package com.milan.music.ui.components

import com.milan.music.ui.theme.MilanMotion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The now-playing bars are an infinite 60 fps animation. HomeScreen stays composed
 * under the player sheet, so without this gate the animation keeps waking the
 * Choreographer for a row that is completely covered.
 */
class NowPlayingIndicatorTest {

    @Test
    fun `bars animate for the visible playing row`() {
        assertTrue(shouldAnimatePlayingBars(isPlaying = true, uiCovered = false))
    }

    @Test
    fun `bars stop while the player sheet covers the library`() {
        assertFalse(shouldAnimatePlayingBars(isPlaying = true, uiCovered = true))
    }

    @Test
    fun `bars do not animate while paused`() {
        assertFalse(shouldAnimatePlayingBars(isPlaying = false, uiCovered = false))
        assertFalse(shouldAnimatePlayingBars(isPlaying = false, uiCovered = true))
    }

    /**
     * 系统的"移除动画"（`ANIMATOR_DURATION_SCALE = 0`）。
     *
     * `shouldAnimatePlayingBars` 管的是产品逻辑（在播 + 未被覆盖），这一条管的是平台
     * 无障碍设置。两者必须分开：合并成一个布尔会让"为什么这根柱不动"有两个互不相干
     * 的原因，排查时无法归因。
     *
     * Compose 会把**有限**动画的时长乘这个倍率，但**无限循环**没有终点，倍率为 0
     * 时它不会自己停下 —— 所以必须显式判断。
     */
    @Test
    fun `bars hold still when the system removes animations`() {
        assertFalse(barsShouldAnimate(animationScale = 0f))
        assertTrue(barsShouldAnimate(animationScale = 0.5f))
        assertTrue(barsShouldAnimate(animationScale = 1f))
        assertTrue(barsShouldAnimate(animationScale = 2f))
    }

    /**
     * 静止高度必须落在正常呼吸区间内。
     *
     * 三条柱在"移除动画"时停在 1.0 会是一排满格的实心块，停在 0.3 是三根几乎看不见的
     * 短棍 —— 两种都读成"坏掉了"。0.55 落在中段，静态画面仍像"在响"。
     */
    @Test
    fun `resting bar height sits inside the breathing range`() {
        assertTrue(
            "BarsRestScale=${MilanMotion.BarsRestScale} 应落在 0.30–1.0",
            MilanMotion.BarsRestScale > 0.30f && MilanMotion.BarsRestScale < 1f,
        )
    }
}
