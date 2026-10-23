package com.milan.music.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 墨色前景的取值契约（[iconInkFor]）。
 *
 * 这条规则被用户推翻过一次：搜索框里的放大镜与排序图标原本取
 * `MaterialTheme.colorScheme.onBackground @0.62/0.72`，而 `onBackground` 指向 [Ink]
 * （`0xFF2A2419`，暖黑）——叠上 alpha 之后在浅金底上读起来是灰棕。
 * 用户的原话是「搜索图标和筛选图标变成纯黑色」。
 *
 * 所以这里守的不是"某个值"，而是**两条都不能反过来**：
 *
 *  - 浅色主题必须是纯黑 `0xFF000000`，而不是 Ink —— 后者哪怕不透明也还是棕；
 *  - 也不能两边都用纯黑：纯黑在夜色背景上约 1.05:1，等于隐形。
 *
 * 用例只依赖纯函数，不需要设备：`onBackground` 那类错误取值在这里没有入口，
 * 因此任何"改回暖黑 / 加回 alpha"的改动都会先让这两条变红。
 */
class IconInkTest {

    @Test
    fun lightThemeUsesPureBlackNotTheWarmInk() {
        val light = iconInkFor(darkTheme = false)
        assertEquals(
            "浅色主题的墨色前景是 $light：必须是纯黑 0x00000000 的 ARGB，不是 Ink 的暖黑。",
            Color.Black,
            light,
        )
        assertEquals(
            "浅色主题的墨色前景 alpha 是 ${light.alpha}：任何透明度都会让它在浅金底上变灰。",
            1f,
            light.alpha,
            1e-6f,
        )
        // 与 Ink 的差异必须能被测出来，否则说明这两个色号已经撞上、断言失去意义。
        assertNotEquals(
            "墨色前景等于 Ink（0xFF2A2419）：这正是用户否掉的那个暖黑。",
            Ink,
            light,
        )
    }

    @Test
    fun darkThemeFallsBackToMoonlightSoTheIconStaysVisible() {
        val dark = iconInkFor(darkTheme = true)
        assertEquals(
            "深色主题的墨色前景是 $dark：纯黑在夜色背景上只有约 1.05:1，图标会消失。",
            Moonlight,
            dark,
        )
        // 亮度差越过 WCAG 的 3:1（大图形门槛），确保它确实"看得见"。
        assertTrue(
            "深色主题前景与夜色背景的对比度只有 ${contrastRatio(dark, Night)}:1，低于 3:1。",
            contrastRatio(dark, Night) >= 3f,
        )
    }

    private fun contrastRatio(a: Color, b: Color): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun relativeLuminance(color: Color): Float {
        fun channel(c: Float): Float =
            if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * channel(color.red) +
            0.7152f * channel(color.green) +
            0.0722f * channel(color.blue)
    }
}
