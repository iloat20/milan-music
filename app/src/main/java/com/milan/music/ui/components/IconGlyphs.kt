package com.milan.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.milan.music.ui.theme.LocalGlassColors
import com.milan.music.ui.theme.iconInkFor

/**
 * 搜索与排序图标。两者都自绘，不再用 `material-icons`。
 *
 * ## 为什么自绘
 *
 * 方案 03「分层双色」（2026-10-22 定）：iOS 27 处理玻璃上的符号，不是把玻璃叠加到图标
 * 上（描边、高光、暗带 —— 那三条路第 26/27 批已经全部归零），而是把层次**内建进图形**，
 * 让轮廓在模糊与折射下依然读得出前后关系。
 *
 * `material-icons` 的每个 symbol 是一整块墨，`Icon(tint=…)` 只能给一个颜色，做不出分档。
 * 因此：几何按 SF Symbols 的 24×24 画布自绘，主层与次层分开绘制。
 *
 * ## 分层是"按主题不同"的两套值，不能统一
 *
 * 浅色把次层压到 0.55，在暖金底上读作中灰，层次清楚。
 * 但深色主题下同一个 0.55 会掉进 `Night`（`0xFF141009`）里 —— 等于次层直接消失。
 * 所以深色用 0.78。**这不是审美微调，是深底下 alpha 衰减必须更保守**；
 * `IconGlyphTest` 守着"深色严格高于浅色"。
 *
 * ## 几何（设计单位 24×24）
 *
 * 笔画 1.6 单位，在 21dp 上折合 1.4dp —— 比原先 `Icons.Rounded.Search` 的
 * 2/24（1.75dp）细 20%。这是方案 01/03 与现状最大的差别：轻，但不至于发虚。
 */
internal object GlyphMetrics {
    /** SF Symbols 的画布边长。所有坐标都以它为基准，绘制时按实际 dp 缩放。 */
    const val Viewport = 24f

    /** 搜索：1.6 单位 ≈ 1.4dp @21dp。 */
    const val SearchStroke = 1.6f

    /** 排序：与搜索同宽。双箭头内部仍有 1.5dp 间隙，不会糊在一起（见 IconGlyphTest）。 */
    const val SortStroke = 1.6f

    val RingCenter = Offset(10f, 10f)
    const val RingRadius = 6.1f

    /** 手柄起点落在圆环上：10 + 6.1/√2 = 14.32。手算过的值，别改单边。 */
    val HandleStart = Offset(14.32f, 14.32f)
    val HandleEnd = Offset(19.35f, 19.35f)

    /** 双箭头的两根竖杆。间距 9 单位，是"两根不糊成一根"的下限。 */
    const val ShaftUpX = 7.5f
    const val ShaftDownX = 16.5f
    const val ShaftUpTop = 6.2f
    const val ShaftUpBottom = 17.8f
    const val ShaftDownTop = 5.9f
    const val ShaftDownBottom = 17.5f

    /** 箭头张角：3 单位横 3 单位竖 = 45°，与 SF Symbols 一致。 */
    const val HeadHalf = 3f
    const val HeadDrop = 3f
}

/**
 * 次层（手柄 / 下方箭头）的透明度。
 *
 * 浅色 0.55：纯黑压到中灰，与主层形成前后关系而不是"两根不同颜色的零件"。
 * 深色 0.78：深底上 0.55 会整条消失，见文件头注释。
 */
internal fun secondaryLayerAlpha(darkTheme: Boolean): Float = if (darkTheme) 0.78f else 0.55f

@Composable
fun SearchGlyph(iconSize: Dp, modifier: Modifier = Modifier) {
    val dark = LocalGlassColors.current.isDark
    val ink = iconInkFor(dark)
    val secondary = ink.copy(alpha = secondaryLayerAlpha(dark))

    Canvas(modifier.size(iconSize)) {
        val u = this.size.minDimension / GlyphMetrics.Viewport
        val stroke = GlyphMetrics.SearchStroke * u
        drawCircle(
            color = ink,
            radius = GlyphMetrics.RingRadius * u,
            center = GlyphMetrics.RingCenter * u,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = secondary,
            start = GlyphMetrics.HandleStart * u,
            end = GlyphMetrics.HandleEnd * u,
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun SortGlyph(iconSize: Dp, modifier: Modifier = Modifier) {
    val dark = LocalGlassColors.current.isDark
    val ink = iconInkFor(dark)
    val secondary = ink.copy(alpha = secondaryLayerAlpha(dark))

    Canvas(modifier.size(iconSize)) {
        val u = this.size.minDimension / GlyphMetrics.Viewport
        val stroke = GlyphMetrics.SortStroke * u

        val upX = GlyphMetrics.ShaftUpX * u
        val upTop = GlyphMetrics.ShaftUpTop * u
        val upBottom = GlyphMetrics.ShaftUpBottom * u
        val downX = GlyphMetrics.ShaftDownX * u
        val downTop = GlyphMetrics.ShaftDownTop * u
        val downBottom = GlyphMetrics.ShaftDownBottom * u
        val half = GlyphMetrics.HeadHalf * u
        val drop = GlyphMetrics.HeadDrop * u

        // 主层：左侧 ↑（"当前方向"落在最前面，与 SF Symbols 的 hierarchical 渲染同序）
        drawLine(ink, Offset(upX, upTop), Offset(upX, upBottom), stroke, StrokeCap.Round)
        drawLine(ink, Offset(upX - half, upTop + drop), Offset(upX, upTop), stroke, StrokeCap.Round)
        drawLine(ink, Offset(upX + half, upTop + drop), Offset(upX, upTop), stroke, StrokeCap.Round)

        // 次层：右侧 ↓，整体降一档，读作"另一个方向"而不是第二个图标
        drawLine(secondary, Offset(downX, downTop), Offset(downX, downBottom), stroke, StrokeCap.Round)
        drawLine(secondary, Offset(downX - half, downBottom - drop), Offset(downX, downBottom), stroke, StrokeCap.Round)
        drawLine(secondary, Offset(downX + half, downBottom - drop), Offset(downX, downBottom), stroke, StrokeCap.Round)
    }
}
