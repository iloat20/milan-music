package com.milan.music.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 深色主题的表面层级契约。
 *
 * 2026-10-25 用户提「暗夜模式可以再暗点」，方向已由首页背景那一轮（10-23）确立过一次，
 * 这次扩到**表面层**：上一版的深色表里 `Night`（背景）L\* 4.9，而 `NightRaised`（面板、
 * 列表行、底栏）L\* 9.7、`surfaceVariant` L\* 13.8 —— 面板比背景亮 5~9 级，大面积铺开
 * 就是"暗夜还是发灰"的来源。只压背景不压表面，差别会被面板抵掉。
 *
 * 所以这一组断言守的是三件事，缺一条都会被"下次顺手压一压"绕过去：
 *
 *  1. **各层都在更暗的一档里**（双侧夹住：上限挡没压够，下限挡压成纯黑 —— 层级没了，
 *     搜索框与底栏会糊进背景）；
 *  2. **层级顺序不变**（背景 < 面板 < 变体 < 容器的强调底），压暗不能把相对关系打乱；
 *  3. **可读性不陪葬**（正文对面板 ≥7:1、次要文字 ≥4.5:1），文字是唯一不跟着压的东西，
 *     面板一暗对比度只会更高，但将来若有人反过来调亮文字，这条会先红。
 */
class DarkThemeToneTest {

    private val scheme = DarkColors

    @Test
    fun darkBackgroundMovedIntoTheDarkerBand() {
        // 上一版 Night = 0xFF141009（Y 0.0054、L* 4.9）。用户要求「再暗点」，
        // 压到 0xFF110D07（Y 0.0042、L* 3.8）—— 系数 0.78 与首页背景同一档。
        val y = luminance(scheme.background)
        assertTrue("深色 background 亮度是 $y：上一版 0.0054，用户要求再暗点，必须 ≤ 0.0046。", y <= 0.0046f)
        assertTrue("深色 background 亮度 $y 已低于 0.0030：那是纯黑，背景与面板就没有层次了。", y >= 0.0030f)
    }

    @Test
    fun darkSurfaceMovedIntoTheDarkerBand() {
        // 上一版 NightRaised = 0xFF201A11（Y 0.0109、L* 9.7）→ 0xFF1B150D（Y 0.0080、L* 7.2）。
        val y = luminance(scheme.surface)
        assertTrue("深色 surface 亮度是 $y：上一版 0.0109，必须 ≤ 0.0085。", y <= 0.0085f)
        assertTrue("深色 surface 亮度 $y 已低于 0.0060：面板会糊进 background。", y >= 0.0060f)
    }

    @Test
    fun darkSurfaceVariantMovedIntoTheDarkerBand() {
        // 上一版 0xFF2A2217（Y 0.0170、L* 13.8）→ 0xFF241D13（Y 0.0130、L* 11.3）。
        // 它是列表里封面占位块与选中行的底色，面积最大，也是"发灰"读数最高的一层。
        val y = luminance(scheme.surfaceVariant)
        assertTrue("深色 surfaceVariant 亮度是 $y：上一版 0.0170，必须 ≤ 0.0140。", y <= 0.0140f)
        assertTrue("深色 surfaceVariant 亮度 $y 已低于 0.0100：会与 surface 撞成一层。", y >= 0.0100f)
    }

    @Test
    fun darkAccentContainerDoesNotStayBright() {
        // 0xFF4A3714（L* 24.4）→ 0xFF402F10（L* 20.7）。它不是主角，但铺在面板上时
        // 是全屏最亮的实心块，跟着压一档才不破坏"整屏更暗"的读数。
        val l = lstar(luminance(scheme.primaryContainer))
        assertTrue("深色 primaryContainer 的 L* 是 $l：上一版 24.4，必须 ≤ 22。", l <= 22f)
        assertTrue("深色 primaryContainer 的 L* 是 $l，已低于 18：强调底会读成普通面板。", l >= 18f)
    }

    @Test
    fun darkLayersKeepTheirOrder() {
        val bg = luminance(scheme.background)
        val surface = luminance(scheme.surface)
        val variant = luminance(scheme.surfaceVariant)
        val container = luminance(scheme.primaryContainer)
        assertTrue("深色层级 $bg / $surface / $variant / $container 不是递增：压暗把相对关系打乱了。",
            bg < surface && surface < variant && variant < container)
    }

    @Test
    fun darkTextStaysReadableOnTheDarkerSurface() {
        val body = contrastRatio(scheme.onSurface, scheme.surface)
        assertTrue("正文 Moonlight 对 surface 只有 $body:1 —— 面板压暗后仍须 ≥7:1（AAA 正文）。", body >= 7f)
        val muted = contrastRatio(scheme.onSurfaceVariant, scheme.surface)
        assertTrue("次要文字对 surface 只有 $muted:1，低于 4.5:1。", muted >= 4.5f)
    }

    private fun luminance(color: Color): Float {
        fun channel(c: Float): Float =
            if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * channel(color.red) +
            0.7152f * channel(color.green) +
            0.0722f * channel(color.blue)
    }

    private fun lstar(y: Float): Float =
        if (y > 0.008856f) 116f * y.toDouble().pow(1.0 / 3.0).toFloat() - 16f else 903.3f * y

    private fun contrastRatio(a: Color, b: Color): Float {
        val la = luminance(a) + 0.05f
        val lb = luminance(b) + 0.05f
        return if (la > lb) la / lb else lb / la
    }
}
