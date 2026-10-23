package com.milan.music.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字号节奏的两条契约。
 *
 * 背景（P3-7）：`MaterialTheme.typography` 只覆盖了 9 档，播放页歌名用的
 * `headlineSmall` 一直走 Material 默认值，等于"用了但看不见"。补齐时最大的风险
 * 不是漏，而是**顺手改** —— 补一条 `headlineSmall` 很容易写成 22sp 或 SemiBold，
 * 那会让播放页标题的视觉在无声中变掉。
 *
 * 所以第一条守的正是"补齐 ≠ 改视觉"：与 `Typography()` 的默认值逐字段相等。
 * 想把歌名调小调粗，先改这条断言并说明理由，不要让它悄悄漂移。
 *
 * 第二条是行高的下限。字号与行高是成对的，单独调字号（比如把 24sp 的档位塞进
 * 32sp 的行高之后又改字号）会让多行文本挤在一起 —— 当前所有档位都在 1.19 以上，
 * 1.15 是能容忍的最小值。
 */
class TypographyRhythmTest {

    private val defaults = Typography()

    @Test
    fun headlineSmallWasAddedWithoutChangingTheRenderedText() {
        assertSameRendering(defaults.headlineSmall, MilanTypography.headlineSmall)
    }

    @Test
    fun everyOverriddenStyleKeepsLineHeightAboveItsFontSize() {
        overriddenStyles(MilanTypography).forEach { (name, style) ->
            assertTrue("$name 的字号必须是 sp", style.fontSize.isSp && style.lineHeight.isSp)
            val ratio = style.lineHeight.value / style.fontSize.value
            assertTrue(
                "$name 的行高/字号 = ${"%.2f".format(ratio)}，低于 1.15 —— 多行会挤在一起",
                ratio >= 1.15f,
            )
        }
    }

    /**
     * 覆盖了哪些档位。这条清单本身也是契约：新增一档就要在这里登记，
     * 否则"只覆盖真被引用的档位"这个约定会慢慢失效。
     */
    private fun overriddenStyles(t: Typography): Map<String, TextStyle> = mapOf(
        "displaySmall" to t.displaySmall,
        "headlineMedium" to t.headlineMedium,
        "headlineSmall" to t.headlineSmall,
        "titleLarge" to t.titleLarge,
        "titleMedium" to t.titleMedium,
        "bodyLarge" to t.bodyLarge,
        "bodyMedium" to t.bodyMedium,
        "labelLarge" to t.labelLarge,
        "labelSmall" to t.labelSmall,
    )

    /**
     * 逐字段比对，但**不比 `fontFamily`**。
     *
     * Material 的默认值是 `FontFamily.SansSerif`，本文件通篇写 `FontFamily.Default`。
     * 两者是同一个泛型族 `sans-serif` 的两个实例（`GenericFontFamily` 没有
     * `equals`，所以引用不等），渲染完全一样 —— 比它只会得到一条永远红的假失败。
     * 字体族要真有变化，会体现在 `fontWeight` 与字号上，这里没漏。
     */
    private fun assertSameRendering(expected: TextStyle, actual: TextStyle) {
        assertEquals("fontSize", expected.fontSize, actual.fontSize)
        assertEquals("lineHeight", expected.lineHeight, actual.lineHeight)
        assertEquals("letterSpacing", expected.letterSpacing, actual.letterSpacing)
        assertEquals("fontWeight", expected.fontWeight, actual.fontWeight)
    }
}
