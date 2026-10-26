package com.milan.music.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 扫光几何的门禁（第 73 批）。
 *
 * 扫光是一次性材质动作：320ms，一过就没了。真机上"扫得不对"几乎无法复现 ——
 * 它太快、太淡，录像也看不清。所以把可以判定的部分全部前置成对纯函数的断言：
 *
 *  1. **带子宽度恒定**。宽度随进度变，扫起来会像呼吸而不是掠光。
 *  2. **从左侧之外走到右侧之外，且单向**。方向反了或者中途回头，观感是"玻璃抖了一下"。
 *  3. **不跳格**。相邻采样点之间带子的位移必须小于带子宽度，否则会看到一道一道的断续。
 *  4. **峰值 alpha 有上限**。超过 8% 玻璃就退化成白色塑料条 —— 这是这一整套效果里
 *     唯一会让它明显难看的方向，所以用常量上限死死压住。
 */
class GlassSweepTest {

    /** 采样密度：1% 一档，比任何一帧的时间粒度都细。 */
    private val samples = (0..100).map { it / 100f }

    @Test
    fun `band keeps its declared width at every progress`() {
        samples.forEach { p ->
            val width = GlassSweep.bandEnd(p) - GlassSweep.bandStart(p)
            assertEquals("p=$p 时带子宽度变了", GlassSweep.BandWidthFraction, width, 1e-6f)
        }
    }

    @Test
    fun `band travels left to right and never turns back`() {
        val starts = samples.map(GlassSweep::bandStart)
        starts.zipWithNext().forEach { (a, b) ->
            assertTrue("带子回头了：$a → $b", b > a)
        }
    }

    @Test
    fun `band starts and ends fully outside the panel`() {
        // 起点：整条带在面板左侧之外；终点：整条带在右侧之外。
        // 否则开场会看见一条凭空出现的亮带，结尾会看见它半截停在边上。
        assertTrue("p=0 时带子应完全在左侧之外", GlassSweep.bandEnd(0f) <= 0f)
        assertTrue("p=1 时带子应完全在右侧之外", GlassSweep.bandStart(1f) >= 1f)
    }

    @Test
    fun `band does not leave gaps between frames`() {
        val step = abs(GlassSweep.bandStart(0.01f) - GlassSweep.bandStart(0f))
        assertTrue(
            "每 1% 进度的位移 ${step} 不小于带宽 ${GlassSweep.BandWidthFraction}，扫过会成断续的条",
            step < GlassSweep.BandWidthFraction,
        )
    }

    @Test
    fun `peak alpha stays under the plastic limit`() {
        assertTrue("PeakAlpha 必须为正", GlassSweep.PeakAlpha > 0f)
        assertTrue(
            "PeakAlpha=${GlassSweep.PeakAlpha} 超过上限 ${GlassSweep.MaxPeakAlpha}，玻璃会变成白色塑料条",
            GlassSweep.PeakAlpha <= GlassSweep.MaxPeakAlpha,
        )
        assertTrue(
            "上限本身也要合理：>8% 时这个效果就不该存在",
            GlassSweep.MaxPeakAlpha <= 0.08f,
        )
    }

    @Test
    fun `sweep runs on dark glass only`() {
        assertEquals(
            "深色玻璃的峰值必须是 PeakAlpha",
            GlassSweep.PeakAlpha,
            GlassSweep.alphaFor(darkGlass = true),
            0f,
        )
        // 离线实测（review/glass-sweep-preview.py）：浅色玻璃上 6% 的白只把面板提亮
        // 2 级（看不见），却把深色文字洗掉 5.1 级。收益为零、代价是真实的，所以给 0。
        assertEquals(
            "浅色玻璃必须完全不扫：面板只亮 +2 级，文字却被洗 +5.1 级",
            0f,
            GlassSweep.alphaFor(darkGlass = false),
            0f,
        )
    }

    @Test
    fun `sweep is quick but not a flicker and tilts enough to read as a glancing light`() {
        assertTrue(
            "DurationMs=${GlassSweep.DurationMs} 不在 240–480ms：短于此是闪烁，长于此会盖过面板自身的入场",
            GlassSweep.DurationMs in 240..480,
        )
        assertTrue(
            "AngleDeg=${GlassSweep.AngleDeg} 不在 5–20°：0° 读成加载条，过大读成斜纹",
            GlassSweep.AngleDeg in 5f..20f,
        )
    }
}
