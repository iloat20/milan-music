package com.milan.music.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页背景（[LightHomeBackdrop] / [DarkHomeBackdrop]）的取值契约。
 *
 * 用户 2026-10-22 的要求是两句话：「淡金色要再淡点」「暗黑模式的暗金色要再暗点」
 * 「都要有渐变效果」。三条都是视觉判断，但都能翻译成数值，所以守在这里而不是守截图：
 *
 *  - **更淡** = 浅色背景的平均亮度必须高于上一版（`F5E8C6 → Paper`，均亮 0.855）；
 *  - **更暗** = 深色背景的平均亮度必须低于上一版（`2E2414 → Night`，均亮 0.0122）；
 *  - **有渐变** = 首末色点的亮度差不能塌成 0（单段平涂会让这条先红）。
 *
 * 另加两条“材质”约束：浅色光晕不能用深金（会把“更淡”抵掉），深色光晕不能用亮金
 * （上一版正是 `AmberBright` 三枚 30% 光晕把整屏烧成褐色雾）。
 *
 * 全部断言只依赖纯函数与常量，不需要设备。
 */
class HomeBackdropTest {

    @Test
    fun lightBackdropIsPalerThanTheShadeItReplaced() {
        val light = homeBackdropFor(darkTheme = false)
        val mean = light.stops.map { luminance(it.second) }.average()
        assertTrue(
            "浅色背景各色点平均亮度是 $mean：上一版是 0.855，用户要求“再淡点”，必须更高。",
            mean >= 0.88,
        )
        val top = luminance(light.stops.first().second)
        assertTrue(
            "浅色顶部亮度是 $top：上一版 F5E8C6 是 0.812，淡金色还要再淡。",
            top >= 0.84,
        )
    }

    @Test
    fun lightBackdropIsAGradientNotAFlatWash() {
        val stops = homeBackdropFor(darkTheme = false).stops
        assertTrue("浅色背景只有 ${stops.size} 个色点：少于三段就退化成平涂。", stops.size >= 3)
        val delta = luminance(stops.last().second) - luminance(stops.first().second)
        assertTrue(
            "浅色背景首末亮度差是 $delta：小于 0.08 时肉眼读不出渐变，等于平涂。",
            delta >= 0.08,
        )
    }

    @Test
    fun lightGlowsStayChampagneInsteadOfAmber() {
        homeBackdropFor(darkTheme = false).glows.forEach { glow ->
            val lum = luminance(glow.color)
            assertTrue(
                "浅色光晕 ${glow.color} 的亮度是 $lum：深金（如 Amber 0xBE9743，0.31）压在近白底上会沉成土黄，与“更淡”冲突。",
                lum >= 0.60f,
            )
            assertTrue("浅色光晕 alpha 是 ${glow.alpha}：超过 0.40 会把渐变盖平。", glow.alpha <= 0.40f)
        }
    }

    @Test
    fun darkBackdropIsDarkerThanTheShadeItReplaced() {
        val dark = homeBackdropFor(darkTheme = true)
        val mean = dark.stops.map { luminance(it.second) }.average()
        assertTrue(
            "深色背景各色点平均亮度是 $mean：上一版是 0.0122，用户要求“暗金再暗点”。",
            mean <= 0.010,
        )
        val top = luminance(dark.stops.first().second)
        assertTrue("深色顶部亮度是 $top：上一版 2E2414 是 0.0189，还要更暗。", top <= 0.016f)
    }

    @Test
    fun darkBackdropIsAGradientNotAFlatWash() {
        val stops = homeBackdropFor(darkTheme = true).stops
        assertTrue("深色背景只有 ${stops.size} 个色点：少于三段就退化成平涂。", stops.size >= 3)
        val delta = luminance(stops.first().second) - luminance(stops.last().second)
        assertTrue(
            "深色背景首末亮度差是 $delta：小于 0.005 时在近黑底上已经完全看不出渐变。",
            delta >= 0.005f,
        )
    }

    @Test
    fun darkGlowsStayDeepGoldInsteadOfBrightGold() {
        homeBackdropFor(darkTheme = true).glows.forEach { glow ->
            val lum = luminance(glow.color)
            assertTrue(
                "深色光晕 ${glow.color} 的亮度是 $lum：亮金（如 AmberBright 0xE7CE93，0.66）会烧成褐色雾。",
                lum <= 0.20f,
            )
            assertTrue("深色光晕 alpha 是 ${glow.alpha}：超过 0.20 会盖掉底部的黑。", glow.alpha <= 0.20f)
        }
    }

    @Test
    fun themeRoutesToItsOwnBackdrop() {
        assertEquals(LightHomeBackdrop, homeBackdropFor(darkTheme = false))
        assertEquals(DarkHomeBackdrop, homeBackdropFor(darkTheme = true))
    }

    private fun luminance(color: Color): Float {
        fun channel(c: Float): Float =
            if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * channel(color.red) +
            0.7152f * channel(color.green) +
            0.0722f * channel(color.blue)
    }
}
