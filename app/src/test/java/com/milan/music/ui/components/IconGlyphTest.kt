package com.milan.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * 自绘图标（方案 03「分层双色」）的门禁。
 *
 * 守的四条，每条对应一个会真实出问题的方向：
 *
 *  - 深色主题的次层 alpha 必须**高于**浅色 —— 同一个 0.55 在 `Night` 上会让手柄、
 *    下方箭头整条消失，看起来像"图标画漏了一半"；
 *  - 搜索笔画必须比原先的 Material 图标细 —— 这是方案 03 的观感前提，粗回去就退回旧样子；
 *  - 双箭头两个箭头之间要有实打实的净间隙 —— 挤在一起会糊成一个「H」；
 *  - 手柄起点必须落在圆环上 —— 只改半径或只改端点，连接处就会断出一个缝。
 */
class IconGlyphTest {

    private fun strokeDp(units: Float, iconSizeDp: Float): Float =
        units / GlyphMetrics.Viewport * iconSizeDp

    @Test
    fun secondaryLayerNeverVanishesOnDarkBackground() {
        val light = secondaryLayerAlpha(darkTheme = false)
        val dark = secondaryLayerAlpha(darkTheme = true)

        assertEquals(
            "浅色次层应是 0.55：纯黑压到中灰，层次在这个值上刚好读得出前后关系。",
            0.55f,
            light,
            1e-6f,
        )
        assertTrue(
            "深色次层是 $dark，不得低于 0.70：Night 已压到 0xFF110D07，低 alpha 叠上去直接消失。",
            dark >= 0.70f,
        )
        assertTrue(
            "深色次层 $dark 必须严格高于浅色 $light，否则深色主题下手柄与下方箭头会整条不见。",
            dark > light,
        )
    }

    @Test
    fun searchStrokeIsThinnerThanMaterialSearch() {
        val actual = strokeDp(GlyphMetrics.SearchStroke, iconSizeDp = 21f)

        assertEquals(
            "21dp 上笔画应折合 1.4dp（1.6/24 × 21）。",
            1.4f,
            actual,
            0.01f,
        )
        assertTrue(
            "笔画是 $actual dp，不得 ≥ 1.75dp：那是 Icons.Rounded.Search 的 2/24，"
                + "粗回去就失去了方案 03 的轻盈感。",
            actual < 1.75f,
        )
    }

    @Test
    fun sortArrowsKeepRealGapBetweenThem() {
        val gapUnits = (GlyphMetrics.ShaftDownX - GlyphMetrics.HeadHalf) -
            (GlyphMetrics.ShaftUpX + GlyphMetrics.HeadHalf) -
            GlyphMetrics.SortStroke
        val gapDp = strokeDp(gapUnits, iconSizeDp = 20f)

        assertTrue(
            "两箭头的净间隙是 $gapDp dp @20dp（按 20dp 图标算），"
                + "低于 1dp 会被抗锯齿糊成一个「H」。",
            gapDp >= 1f,
        )
    }

    @Test
    fun handleStartsExactlyOnTheRing() {
        val distance = hypot(
            GlyphMetrics.HandleStart.x - GlyphMetrics.RingCenter.x,
            GlyphMetrics.HandleStart.y - GlyphMetrics.RingCenter.y,
        )

        assertEquals(
            "手柄起点到圆心是 $distance，必须等于 ${GlyphMetrics.RingRadius}："
                + "两者取自 10 + r/√2，只改单边会在连接处裂出空隙。",
            GlyphMetrics.RingRadius,
            distance,
            0.02f,
        )
    }

    @Test
    fun bothGlyphsSitInsideTheViewport() {
        val half = GlyphMetrics.SearchStroke / 2f
        val ringLeft = GlyphMetrics.RingCenter.x - GlyphMetrics.RingRadius - half
        val handleRight = GlyphMetrics.HandleEnd.x + half
        assertTrue("圆环左缘 $ringLeft 越出视口左边界。", ringLeft >= 0f)
        assertTrue("手柄右端 $handleRight 越出视口右边界 24。", handleRight <= 24f)

        val sortLeft = GlyphMetrics.ShaftUpX - GlyphMetrics.HeadHalf - half
        val sortRight = GlyphMetrics.ShaftDownX + GlyphMetrics.HeadHalf + half
        assertTrue("双箭头左缘 $sortLeft 越界。", sortLeft >= 0f)
        assertTrue("双箭头右缘 $sortRight 越界。", sortRight <= 24f)

        // 双箭头要视觉居中：只有左缘和右缘到视口中心等距，它在玻璃里才不会偏。
        val centerX = (sortLeft + sortRight) / 2f
        assertEquals("双箭头的水平中心是 $centerX，应落在视口中线 12。", 12f, centerX, 0.5f)
    }
}
