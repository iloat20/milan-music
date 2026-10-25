package com.milan.music.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页背景（[LightHomeBackdrop] / [DarkHomeBackdrop]）的取值契约。
 *
 * 这组数值被用户改过三次，而且方向相反 —— 每一条断言都要连同"哪一轮的需求"一起看：
 *
 *  - 2026-10-22「淡金色要再淡点 / 暗黑模式的暗金色要再暗点 / 都要有渐变效果」；
 *  - 2026-10-23「背景色可以再暗点」—— 浅色翻转成"更暗"，深色在原来基础上再压一档；
 *  - **2026-10-25「暗夜模式可以再暗点」** —— 只动深色：底色再 ×0.78，并把光晕 alpha
 *    降到 ×0.60（上一轮只压底色，顶部那片金色雾没动，它才是整屏最亮处）。
 *
 * 所以浅色的平均亮度现在是**双侧夹住**（0.82 ~ 0.88）：上限挡"没压够"，下限挡"压成土黄"。
 * 只留上限的话，下一次有人把三段一起砍掉 20% 也会全绿。深色同理（0.0045 ~ 0.0060），
 * 下限同时挡住"压成纯黑、暗金与渐变一起没了"。
 *
 * 另加两条“材质”约束：浅色光晕不能用深金（会把"更淡"抵掉），深色光晕不能用亮金
 * （上一版正是 `AmberBright` 三枚 30% 光晕把整屏烧成褐色雾），且三枚合计 alpha 有上限
 * —— 单枚都小、叠起来仍会连成一片，这是 10-25 才暴露出来的那种"看不出来但确实亮"。
 *
 * 全部断言只依赖纯函数与常量，不需要设备。
 */
class HomeBackdropTest {

    @Test
    fun lightBackdropIsDarkerThanTheShadeItReplaced() {
        // 用户 2026-10-23：「背景色可以再暗点」。方向与 10-22 的「再淡点」相反，
        // 所以这一条从"必须更亮"翻转成"必须更暗"，阈值按实测值两侧夹住：
        // 上限挡住没压够（上一版均亮 0.905 / 顶部 0.851），
        // 下限挡住压过头（再暗两档是 0.748 / 0.696，那片米色会读成土黄，彩度就脏了）。
        val light = homeBackdropFor(darkTheme = false)
        val mean = light.stops.map { luminance(it.second) }.average()
        assertTrue("浅色背景各色点平均亮度是 $mean：上一版 0.905，用户要求再暗点，必须低于 0.88。", mean <= 0.88)
        assertTrue("浅色背景平均亮度 $mean 已经低于 0.82：再暗就是土黄，不是「暖金暗一档」。", mean >= 0.82)

        val top = luminance(light.stops.first().second)
        assertTrue("浅色顶部亮度是 $top：上一版 0.851，还要更暗。", top <= 0.83)
        assertTrue("浅色顶部亮度 $top 低于 0.78：金色在近白区已经读不出来，只剩灰米色。", top >= 0.78)
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
        // 2026-10-25「暗夜模式可以再暗点」：深色再压一档，系数 0.78。
        // 上一版三段 231A0C/171107/0E0A05 → 均亮 0.0068、顶部 0.0112；现在 0.0053 / 0.0087。
        val dark = homeBackdropFor(darkTheme = true)
        val mean = dark.stops.map { luminance(it.second) }.average()
        assertTrue(
            "深色背景各色点平均亮度是 $mean：上一版 0.0068，用户要求再暗点，必须 ≤ 0.0060。",
            mean <= 0.0060,
        )
        assertTrue(
            "深色背景平均亮度 $mean 已低于 0.0045：再压就塌成纯黑，暗金与渐变同时消失。",
            mean >= 0.0045,
        )
        val top = luminance(dark.stops.first().second)
        assertTrue("深色顶部亮度是 $top：上一版 0.0112（231A0C），还要更暗。", top <= 0.010f)
        assertTrue("深色顶部亮度 $top 低于 0.0070：顶部是暗金唯一还看得见的地方，压到这就只剩黑。", top >= 0.0070f)
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
        val glows = homeBackdropFor(darkTheme = true).glows
        glows.forEach { glow ->
            val lum = luminance(glow.color)
            assertTrue(
                "深色光晕 ${glow.color} 的亮度是 $lum：亮金（如 AmberBright 0xE7CE93，0.66）会烧成褐色雾。",
                lum <= 0.20f,
            )
            assertTrue("深色光晕 alpha 是 ${glow.alpha}：超过 0.20 会盖掉底部的黑。", glow.alpha <= 0.20f)
        }
        // 「再暗点」里金色雾是最主要的一块 —— 单枚 alpha 已经很小，但三枚叠在顶部
        // 仍会连成一片。上一版合计 0.18+0.15+0.13 = 0.46，现在 0.11+0.09+0.08 = 0.28。
        val total = glows.map { it.alpha }.sum()
        assertTrue("深色光晕合计 alpha 是 $total：上一版 0.46，必须 ≤ 0.30。", total <= 0.30f)
        assertTrue("深色光晕合计 alpha 只有 $total，低于 0.20：暗金会彻底消失，背景退化成纯黑。", total >= 0.20f)
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
