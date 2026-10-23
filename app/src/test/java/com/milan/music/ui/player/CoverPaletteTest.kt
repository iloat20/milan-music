package com.milan.music.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 播放页配色的取色规则。
 *
 * 这几条都是"挑两张封面看不出来、换一张就翻车"的类型，所以钉成断言：
 *
 *  - **两段颜色都只来自封面最下 20%**（2026-10-23 用户要求，顶部完全不参与）；
 *  - **色场是那 20% 色带的算术平均**（不筛暗部、不按彩度加权、不压暗）；
 *  - 顶部仍与色场差一档；纯黑封面要退兜底色而不是变全黑；
 *  - 背景渐变在镜面线以下必须真的变暗，且不能暗到与封面脱钩。
 */
class CoverPaletteTest {

    private val size = 48

    @Test
    fun onlyTheBottomBandColoursThePage() {
        // 2026-10-23 用户：「播放页取色只取封面最下 20%」。
        // 两张封面只有**上半部分**不同（一张近黑、一张近白），底部 20% 完全一致：
        // 推出来的两段色必须逐通道相等。旧实现顶部取封面最上面 10%，这条会立刻红。
        val darkTop = paletteOf(
            cover(color = Color(0xFF4A4A4A), top = Color(0xFF0A0A14), bottom = Color(0xFFB06018)),
            size,
            size,
        )
        val lightTop = paletteOf(
            cover(color = Color(0xFF4A4A4A), top = Color(0xFFF2F2F2), bottom = Color(0xFFB06018)),
            size,
            size,
        )

        assertEquals("封面顶部改了，页面顶端却跟着变", darkTop.top, lightTop.top)
        assertEquals("封面顶部改了，色场却跟着变", darkTop.bottom, lightTop.bottom)

        // 顶端与色场都要偏橙（底边是暖橙），谁也不许被上半部分拉走。
        assertTrue("页面顶端没有取自底部色带（应偏橙）", darkTop.top.red > darkTop.top.blue)
        assertTrue("色场没有取自底部色带（应偏橙）", darkTop.bottom.red > darkTop.bottom.blue)
    }

    @Test
    fun backdropFadesDarkerTowardsTheBottom() {
        // 「要渐变」的可测形式：镜面线以下必须真的变暗，但也不能暗到与封面脱钩
        // （那会在屏幕底部出现一条与画面无关的暗带）。
        val palette = paletteOf(cover(Color(0xFF6E5A44)), size, size)
        val stops = backdropStopsOf(palette, coverBottom = 0.25f)

        assertEquals("色点数不是 3：渐变退化成平涂", 3, stops.size)
        assertEquals("镜面线位置被改动了", 0.25f, stops[1].first, 1e-4f)
        assertEquals("镜面线处不是色场本色", palette.bottom, stops[1].second)

        val field = luminanceOf(stops[1].second)
        val end = luminanceOf(stops[2].second)
        assertTrue("页面底端亮度 $end 没有低于色场 $field：下半页还是一块平色", end < field * 0.85f)
        assertTrue("页面底端亮度 $end 掉到色场的 60% 以下：底部会多出一条与封面无关的暗带", end > field * 0.60f)
    }

    @Test
    fun backdropStopsStayOrderedForExtremeCoverHeights() {
        // 短窗口里 hero 会被压到 MinTransportHeight 附近，coverBottom 可能很小；
        // location 必须**严格**递增 —— 相等的话 verticalGradient 会画出一条硬边。
        for (bottom in listOf(0f, 0.02f, 0.25f, 0.6f, 1f, 1.4f)) {
            val stops = backdropStopsOf(FallbackPalette, bottom)
            assertTrue(
                "coverBottom=$bottom 时色点不是严格递增：${stops.map { it.first }}",
                stops[0].first < stops[1].first && stops[1].first < stops[2].first,
            )
        }
    }

    @Test
    fun darkenKeepsHueAndOnlyLowersBrightness() {
        // 渐变靠"同一块颜色暗下去"，不是"往灰里推"：色相方向必须保持。
        val cool = darken(Color(0xFF2F4A6B), 0.7f)
        assertTrue("变暗后蓝色不再是主导通道", cool.blue > cool.red + 0.05f)
        assertTrue("变暗没有真的降低亮度", luminanceOf(cool) < luminanceOf(Color(0xFF2F4A6B)))
    }

    @Test
    fun fieldIsTheCoverBottomColourItself() {
        // 这是用户反馈过的真实情况：封面底部是一条浅灰的版权条。
        // 它必须以**本色**出现在下半页 —— 一旦压暗，眼睛就会判定"颜色不是从封面取的"。
        val band = Color(0xFFACA3A1)
        val field = paletteOf(cover(Color(0xFFF0E9DE), bottom = band), size, size).bottom

        assertEquals("色场红通道被改动了", band.red, field.red, 0.02f)
        assertEquals("色场绿通道被改动了", band.green, field.green, 0.02f)
        assertEquals("色场蓝通道被改动了", band.blue, field.blue, 0.02f)
    }

    @Test
    fun fieldAveragesTheWholeBottomBandInsteadOfItsBrightestPixels() {
        // 用户反馈"取色还是不准确"的真实情形：封面下缘一半是亮暖色、一半是深色衬衫。
        // 色场必须是这两半的平均，而不是"够亮的那一半"。
        //
        // 一条断言同时钉住两件事，残留的错误实现各自只会撞上一个方向：
        //  - 只取底部 10%（48 行里的 43..47 行）→ 全是深色，亮度塌到 0.01；
        //  - 取 20% 但按"luma ≥ 45 且彩度 ≥ 0.12"筛掉暗部 → 只剩亮暖色，亮度 0.49。
        val tan = Color(0xFFE0B080)
        val shadow = Color(0xFF241A12)
        val field = paletteOf(bandCover(tan = tan, shadow = shadow), size, size).bottom

        // 期望值就是这一条色带的算术平均：(111, 86, 62)。
        assertEquals("色场红通道不是色带平均", 111f / 255f, field.red, 0.02f)
        assertEquals("色场绿通道不是色带平均", 86f / 255f, field.green, 0.02f)
        assertEquals("色场蓝通道不是色带平均", 62f / 255f, field.blue, 0.02f)

        val luminance = luminanceOf(field)
        assertTrue("暗部被剔除后色场偏亮（$luminance）", luminance < 0.25f)
        assertTrue("取色带只有 10%，暗部把它压塌了（$luminance）", luminance > 0.05f)
    }

    @Test
    fun fieldKeepsEachCoverItsOwnBrightness() {
        // 浅封面得到浅下半页、深封面得到深下半页：亮度不许被统一压到某个暗值。
        val bottoms = listOf(
            "深蓝" to Color(0xFF2F4A6B),
            "浅灰" to Color(0xFFACA3A1),
            "浅米" to Color(0xFFBFA98C),
        )

        for ((name, band) in bottoms) {
            val field = paletteOf(cover(Color(0xFF808080), bottom = band), size, size).bottom

            assertTrue(
                "$name 封面：色场亮度 ${luminanceOf(field)} 与色带 ${luminanceOf(band)} 差太多",
                abs(luminanceOf(field) - luminanceOf(band)) <= 0.03f,
            )
        }
    }

    @Test
    fun nearWhiteCoverIsCappedSoThePageIsNotBlank() {
        // 兜底安全线：封面底部接近纯白时整页会变成一张白纸，压回 0.5。
        //
        // 容差 0.005 不是"放水"：`Color(red, green, blue)` 的三个 Float 通道在 Compose 里
        // 存成 8 bit sRGB，187.5/255 会被取整成 188/255，亮度因此必然带 ±0.004 的量化误差。
        // 目标是"不许变成白纸"（那会是 0.95），0.503 与 0.500 在屏幕上是同一块色。
        val field = paletteOf(
            cover(Color(0xFFFDFDFD), bottom = Color(0xFFFAFAFA)),
            size,
            size,
        ).bottom

        assertTrue(
            "近白封面的色场亮度 ${luminanceOf(field)} 没有兜住，整页会是一张白纸",
            luminanceOf(field) <= 0.505f,
        )
    }

    @Test
    fun topStaysAStepDarkerThanTheField() {
        for (sample in samples()) {
            val palette = paletteOf(sample.pixels, size, size)
            val top = luminanceOf(palette.top)
            val bottom = luminanceOf(palette.bottom)

            // 版式要求"上下两段不一样"。规则是比例而不是绝对值：深色封面本来就暗，
            // 用绝对落差会把它判成"一样"。
            assertTrue(
                "${sample.name}：顶部 $top 没有比色场 $bottom 暗一档，上下两段会长得一样",
                top <= bottom * 0.75f + 1e-3f,
            )
        }
    }

    @Test
    fun hueComesFromTheCover() {
        val red = paletteOf(cover(Color(0xFFB02020)), size, size).bottom
        val blue = paletteOf(cover(Color(0xFF1E3A8A)), size, size).bottom

        assertTrue("红封面推出的色场不偏红", red.red > red.blue + 0.10f)
        assertTrue("蓝封面推出的色场不偏蓝", blue.blue > blue.red + 0.05f)
    }

    @Test
    fun differentCoversProduceDifferentFields() {
        val warm = paletteOf(cover(Color(0xFF8A5A1E)), size, size).bottom
        val cool = paletteOf(cover(Color(0xFF2F5D62)), size, size).bottom

        // 换成暖色封面后色场必须跟着走：这正是"每张封面一套渐变色"的最低要求。
        assertTrue("暖封面与冷封面的色场几乎一样", abs(warm.red - cool.red) > 0.05f)
    }

    @Test
    fun blackCoverFallsBackInsteadOfReturningBlack() {
        val palette = paletteOf(cover(Color.Black), size, size)

        assertEquals(FallbackPalette, palette)
        assertTrue("兜底色塌成黑色了", luminanceOf(palette.bottom) > 0.02f)
    }

    @Test
    fun blankInputFallsBack() {
        assertEquals(FallbackPalette, paletteOf(IntArray(0), 0, 0))
        assertEquals(FallbackPalette, paletteOf(IntArray(4), 8, 8))
    }

    /**
     * 一张三段的合成封面：上中下各一种颜色，用来模拟真实封面的纵向色差。
     */
    private fun cover(color: Color, top: Color = color, bottom: Color = color): IntArray {
        val pixels = IntArray(size * size)
        val topArgb = top.toArgb()
        val middleArgb = color.toArgb()
        val bottomArgb = bottom.toArgb()
        for (y in 0 until size) {
            val argb = when {
                y < size / 3 -> topArgb
                y < size * 2 / 3 -> middleArgb
                else -> bottomArgb
            }
            for (x in 0 until size) {
                pixels[y * size + x] = argb
            }
        }
        return pixels
    }

    /**
     * 一张"下缘一半亮、一半暗"的封面：上 2/3 是背景，再往下转暖色，最后 1/8 压成深色。
     *
     * 48 行里，底部 20% 是 38..47 行 —— 4 行暖色 + 6 行深色；只取 10% 就只剩那 6 行深色。
     */
    private fun bandCover(tan: Color, shadow: Color): IntArray {
        val pixels = IntArray(size * size)
        val background = Color(0xFFD0C8BE).toArgb()
        val tanArgb = tan.toArgb()
        val shadowArgb = shadow.toArgb()
        for (y in 0 until size) {
            val argb = when {
                y < size * 2 / 3 -> background
                y < size * 7 / 8 -> tanArgb
                else -> shadowArgb
            }
            for (x in 0 until size) {
                pixels[y * size + x] = argb
            }
        }
        return pixels
    }

    private data class Sample(val name: String, val pixels: IntArray)

    private fun samples(): List<Sample> = listOf(
        Sample(
            "深蓝封面",
            cover(
                color = Color(0xFF24406E),
                top = Color(0xFF101C33),
                bottom = Color(0xFF6E8BB5),
            ),
        ),
        Sample(
            "粉色封面",
            cover(
                color = Color(0xFFB25A78),
                top = Color(0xFF7E2E4A),
                bottom = Color(0xFFD49AAB),
            ),
        ),
        Sample("暖褐封面", cover(Color(0xFF7A4B12))),
        Sample("近乎纯白封面", cover(Color(0xFFF2F2F2), bottom = Color(0xFFE8E8E8))),
        Sample("灰阶封面", cover(Color(0xFF808080))),
        Sample("纯红封面", cover(Color(0xFFB02020))),
    )
}
