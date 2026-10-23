package com.milan.music.ui.player

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

/**
 * 播放页封面的几何约束（2026-10-22 第 42 批：框 50% + 铺满 + 纵向 5%）。
 *
 * 这一条线走了五轮，每次都是被真机否掉才换的，所以这里把结论钉死：
 *
 *  - 第 38 批 `Fit`（整张装进框）：框恒 50%、一张不裁，但竖图左右各空 ≈9% 糊边 —— 否；
 *  - 第 39/40 批 `FillWidth`（按宽度铺满、不裁不拉）：去白边后的封面不是方图，
 *    照片只铺到屏高 40%，用户连提两轮「封面还是没有占到屏幕 50%」—— 否；
 *  - 第 41 批 `Crop`：照片铺满整个框，任何比例都**正好占屏高 50%**，代价是裁切；
 *  - 第 42 批 `Crop + 纵向 5%`：铺满不变，纵向多给 5% 换更少的横向裁切（方图裁切减半）。
 *
 * 于是文件守六件事：
 *
 *  1. 框 = 屏高 50%，窗口太矮时先让封面退让，不挤信息区；
 *  2. 照片**至少盖满整个框**（`Crop`）：任何比例的显示尺寸都不小于框；
 *  3. 缩放最多**纵向多看 5%**，绝不对称之外的方向形变（没有 `FillBounds` 那种拉伸）；
 *  4. 裁切量就是对外承诺的那几个数：方图左右各 1.5%、宽图 7.4%、竖图底部 18.8%；
 *  4b. 纵向拉伸不超过 5%，而且只在"拉伸能换到更少裁切"时启用（竖图不拉）；
 *  5. 淡出 = 「清晰 40% 屏 + 淡出带 10% 屏」，终点落在框底；
 *  6. 色场交界点 = 框底（屏幕 50%），不能从照片中间开始。
 */
class CoverLayoutTest {

    // 与生产常量同步（59dp 的信息区下移让内在高度涨到 ≈277dp）。
    private val minTransport = 300.dp

    /** 常见竖屏尺寸，用来验证「隔壁不可见」与「封面恒占半屏」。 */
    private val commonWindows = listOf(320 to 640, 360 to 780, 360 to 800, 390 to 844, 411 to 914)

    /** 390 × 844 上的封面框：满宽 390dp，占屏 50% = 422dp。 */
    private val hero = Size(390f, 422f)

    private val heroHeight = CoverLayout.heroHeight(844.dp, minTransport)

    /** 真机那张去白边封面的尺寸（873 × 767），第 39 批就是被它掀翻的。 */
    private val wideW = 873f
    private val wideH = 767f

    @Test
    fun heroTakesHalfTheWindow() {
        assertEquals(422f, heroHeight.value, 0.01f)
        assertEquals("封面框不是屏高的 50%", 0.50f, heroHeight.value / 844f, 0.001f)
        // 框必须比页宽高，否则 Crop 的裁切方向会变成上下裁。
        assertTrue("框没有比页宽高，Crop 的裁切方向不对", heroHeight > 390.dp)
    }

    @Test
    fun shortWindowShrinksTheHeroInsteadOfClippingTheTransport() {
        // 窗口矮到"50% 之后放不下信息区"时，让封面退让，而不是把控制挤出屏幕。
        val window = 520.dp
        val h = CoverLayout.heroHeight(window, minTransport)

        assertEquals((window - minTransport).value, h.value, 0.01f)
        assertTrue("封面吃掉了信息区的高度", window - h >= minTransport)
    }

    @Test
    fun neighboursStayOutsideTheWindowAtRest() {
        for ((w, h) in commonWindows) {
            val pitch = CoverLayout.pitch(w.dp)
            val nearEdge = pitch - w.dp / 2
            val halfWindow = w.dp / 2

            assertTrue(
                "窗口 $w x $h dp：隔壁封面探进屏幕 ${halfWindow - nearEdge}",
                nearEdge >= halfWindow,
            )
            assertTrue("窗口 $w x $h dp：一页位移没有越过封面右边缘", pitch > w.dp)
        }
    }

    @Test
    fun photoCoversTheWholeFrame() {
        // 落位是唯一来源，调用点不许另写一个值。
        assertSame(Alignment.TopCenter, CoverLayout.HeroContentAlignment)
        assertEquals("纵向多看比例变了", 1.05f, CoverLayout.PhotoStretch, 0.0005f)

        val cases = listOf(
            873f to 873f,
            1000f to 1000f,
            600f to 800f,
            1600f to 900f,
            wideW to wideH,
        )
        for ((w, h) in cases) {
            val factor = CoverLayout.HeroContentScale.computeScaleFactor(Size(w, h), hero)
            val shownW = w * factor.scaleX
            val shownH = h * factor.scaleY

            assertTrue("${w}x$h 的封面宽度没盖住框：$shownW < 390", shownW >= 390f - 0.01f)
            assertTrue("${w}x$h 的封面高度没盖住框：$shownH < 422", shownH >= 422f - 0.01f)
            // 纵向最多多看 5%（PhotoStretch），横向永远不额外放大。
            val stretch = factor.scaleY / factor.scaleX
            assertTrue(
                "${w}x$h 的封面纵向拉伸是 ${"%.3f".format(stretch)}，超过 5% 上限",
                stretch <= 1.05f + 0.0005f,
            )
            assertTrue(
                "${w}x$h 的封面纵向被压缩了：${"%.3f".format(stretch)}",
                stretch >= 1f - 0.0005f,
            )
            // 恰好有一边顶满 —— 否则就是在框里放大过头了。
            assertTrue(
                "${w}x$h 的封面两边都溢出，说明放多了：${shownW}x$shownH",
                shownW <= 390.01f || shownH <= 422.01f,
            )
        }
    }

    @Test
    fun sideCropStaysWithinTheDocumentedNumbers() {
        // 方图：纵向多 5% 之后显示 401.9 宽铺进 390 → 左右各裁 1.48%。
        // （纯 Crop 是 3.79%，用户要求"少裁一半"，这里是 39%）
        val square = CoverLayout.HeroContentScale.computeScaleFactor(Size(873f, 873f), hero)
        val squareShownW = 873f * square.scaleX
        assertEquals(
            "方图左右各裁的比例变了",
            0.0148f,
            (squareShownW - 390f) / 2 / squareShownW,
            0.0005f,
        )

        // 真机那张宽图：显示 457.4 宽 → 左右各裁 7.37%（纯 Crop 是 9.41%）。
        val wide = CoverLayout.HeroContentScale.computeScaleFactor(Size(wideW, wideH), hero)
        val wideShownW = wideW * wide.scaleX
        assertEquals(
            "宽图左右各裁的比例变了",
            0.0737f,
            (wideShownW - 390f) / 2 / wideShownW,
            0.0005f,
        )
        assertTrue(
            "宽图左右合起来的裁切不该超过 20%",
            (wideShownW - 390f) / wideShownW <= 0.20f,
        )
    }

    @Test
    fun stretchIsOnlyAppliedWhenItBuysLessCrop() {
        // 宽图/方图：高度是被"拉伸后的需求"顶出来的，纵向确实吃 5%。
        for ((w, h) in listOf(873f to 873f, wideW to wideH, 1600f to 900f)) {
            val f = CoverLayout.HeroContentScale.computeScaleFactor(Size(w, h), hero)
            assertEquals("${w}x$h 应该吃满 5%", 1.05f, f.scaleY / f.scaleX, 0.0005f)
        }
        // 竖图：高度本来就溢出，再拉只会多裁底部 —— 必须退化成不拉。
        for ((w, h) in listOf(600f to 800f, 1000f to 1500f)) {
            val f = CoverLayout.HeroContentScale.computeScaleFactor(Size(w, h), hero)
            assertEquals("${w}x$h 不该被拉伸", 1f, f.scaleY / f.scaleX, 0.0005f)
        }
    }

    @Test
    fun portraitArtworkIsCroppedAtTheBottom() {
        // 竖图 3:4 → 显示 390×520，多出来的 98dp 全部落在底部（TopCenter），
        // 而不是左右各留一条糊边（第 38 批的死法）。
        val factor = CoverLayout.HeroContentScale.computeScaleFactor(Size(600f, 800f), hero)
        val shownH = 800f * factor.scaleY

        assertEquals("竖图没有铺满框宽", 390f, 600f * factor.scaleX, 0.01f)
        assertEquals("竖图底部裁掉的比例变了", 0.1885f, (shownH - 422f) / shownH, 0.0005f)
        assertEquals("竖图不该被拉伸（只会多裁底部）", 1f, factor.scaleY / factor.scaleX, 0.0005f)
    }

    @Test
    fun clearCoverEndsAtFortyPercentOfTheScreen() {
        // 用户 2026-10-22：「清晰封面要 40%（占屏幕）」。hero 是 844 × 0.50 = 422dp，
        // 所以清晰段的终点是 337.6dp = 0.80 × hero。
        assertEquals(0.80f, CoverLayout.HeroFadeOut, 0.0005f)
        assertEquals(337.6f, CoverLayout.HeroFadeOut * heroHeight.value, 0.5f)
    }

    @Test
    fun fadeEndsExactlyAtTheFrameBottom() {
        // 照片铺满整框，所以淡出终点恒为 1（框底）—— 带子 = 剩下的 20% 框高 = 10% 屏。
        assertEquals(1f, CoverLayout.PhotoBottom, 0.0001f)
        assertEquals(
            "淡出带不是 10% 屏高",
            0.10f,
            (CoverLayout.PhotoBottom - CoverLayout.HeroFadeOut) * 0.50f,
            0.0005f,
        )
        assertEquals(422f, CoverLayout.PhotoBottom * heroHeight.value, 0.01f)
    }

    @Test
    fun fadeBandIsLongEnoughToHideTheSeam() {
        // 带子太短就是硬边（第 39 批真机实测：4 行内标准差从 75 掉到 9）。
        // 这里把下限钉成"至少 5% 屏高"，同时不许吃掉清晰段。
        val band = (CoverLayout.PhotoBottom - CoverLayout.HeroFadeOut) * 0.50f
        assertTrue("淡出带只有 ${"%.1f".format(band * 100)}% 屏高，会显出一道硬边", band >= 0.05f)
        assertTrue("淡出带吃掉了清晰段", CoverLayout.HeroFadeOut >= 0.5f)
        assertTrue("渐显带与淡出带重叠了", CoverLayout.HeroFadeIn < CoverLayout.HeroFadeOut)
    }

    @Test
    fun photoAlwaysTakesHalfTheScreen() {
        // 这是本批的全部意义：无论封面比例，照片（含裁切）恰好盖住屏高的一半。
        for ((w, h) in commonWindows) {
            val height = CoverLayout.heroHeight(h.dp, minTransport)
            val share = CoverLayout.PhotoBottom * height.value / h
            val expected = minOf(0.50f, (h - minTransport.value) / h.toFloat())

            assertEquals("窗口 $w x $h dp：封面没占到屏高的 50%", expected, share, 0.0005f)
        }
    }

    @Test
    fun coverBottomMatchesTheFrameBottom() {
        // 色场交界点 = photoBottom × hero / 页高，也就是屏幕 50% 处 —— 不能再出现
        // "色场从照片中间开始"那种错位（第 33 批）。
        val coverBottom = CoverLayout.PhotoBottom * heroHeight.value / 844f
        assertEquals(0.50f, coverBottom, 0.001f)

        // 顺带守住缩放本身：横向 = max(框宽/源宽, 框高/源高/PhotoStretch)。
        val factor = CoverLayout.HeroContentScale.computeScaleFactor(Size(wideW, wideH), hero)
        assertEquals(
            "缩放算错了",
            max(390f / wideW, 422f / wideH / CoverLayout.PhotoStretch),
            factor.scaleX,
            0.0005f,
        )
    }
}
