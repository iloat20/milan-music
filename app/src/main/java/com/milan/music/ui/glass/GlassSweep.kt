package com.milan.music.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import com.milan.music.ui.theme.MilanMotion

/**
 * 玻璃面板升起时的那一次扫光（第 73 批，iOS 27 的 specular 口径）。
 *
 * iOS 27 对 Liquid Glass 的收敛里有两条：**暗边加深**、**镜面高光提亮**。暗边已经由
 * `haze-glass` 的 `GlassStyle.clear` 给出（形状跟随的 Fresnel 暗边），这里补的是高光 ——
 * 而且只补"出现的那一下"，不做常驻动画。
 *
 * 三条硬约束，全部来自之前踩过的坑：
 *
 *  1. **纯叠加绘制**。它是画在材质之上的一层渐变，不读 `HazeState`、不改采样坐标。
 *     第 71 批首页那次"玻璃里浮出放大错位的列表文字"是祖先变换破坏采样，这类操作
 *     一律不许再出现在玻璃上。
 *  2. **一次即完**。不做无限循环、不跟随手指、不订阅传感器。Apple 自己在 iOS 27
 *     砍掉了重力感应的轮廓光，常驻高光只会让每块面板都比它背后的内容更亮。
 *  3. **峰值 alpha 有上限**。玻璃一旦被白色盖到 8% 以上，就从"透过去的材质"变成
 *     "半透明白塑料条"，在深色背景上尤其明显。上限写在 [MaxPeakAlpha] 并由门禁守。
 *
 * 参数与几何都是纯函数/常量，`GlassSweepTest` 直接断言它们 —— 视觉参数不能靠"看着还行"。
 */
object GlassSweep {

    /**
     * 扫过一次的时长。
     *
     * 与迷你播放器入场（`Swap` 弹簧解算出的落位时间同量级）对齐：扫光比面板到得晚，
     * 就变成"面板已经停好又在上面抹一下"。短于 240ms 会被读成闪烁而不是扫过。
     */
    const val DurationMs = 320

    /** 扫光带的峰值不透明度。 */
    const val PeakAlpha = 0.06f

    /** 门禁上限：超过这个值玻璃就退化成白色塑料条。 */
    const val MaxPeakAlpha = 0.08f

    /** 带子的宽度，按面板宽度的比例。太窄像一道划痕，太宽等于整块闪一下。 */
    const val BandWidthFraction = 0.35f

    /** 带子相对竖直方向的倾角。0° 是纯竖条，读起来像加载条；12° 才有掠光感。 */
    const val AngleDeg = 12f

    /**
     * 带子左缘在面板宽度上的归一化位置。
     *
     * `progress = 0` 时整条带完全在面板左侧之外，`1` 时完全在右侧之外 —— 因此不需要
     * 额外的淡入淡出包络：带子进出面板本身就是渐进的，而"alpha 随进度起落"会在中段
     * 制造一次比边缘更亮的假峰值。
     */
    fun bandStart(progress: Float): Float {
        val center = -BandWidthFraction + progress * (1f + 2f * BandWidthFraction)
        return center - BandWidthFraction / 2f
    }

    /** 带子右缘，恒等于 [bandStart] + [BandWidthFraction]。 */
    fun bandEnd(progress: Float): Float = bandStart(progress) + BandWidthFraction

    /**
     * 这块玻璃上该给多亮。**浅色玻璃返回 0，即不扫。**
     *
     * 2026-10-26 用 `review/glass-sweep-preview.py` 离线实测（6% 白、深/浅两种玻璃）：
     *
     *  - 深色：面板峰值 **+15 级**（看得见的一道掠光），文字块只被洗 **+0.4 级**；
     *  - 浅色：面板峰值只有 **+2 级**（肉眼无差别），文字块却被洗 **+5.1 级**
     *    —— 因为浅色面板本身已接近 255，6% 的白加在面板上等于没加，加在深色文字上
     *    却实打实地提亮。
     *
     * 也就是说浅色玻璃上的扫光是"看不见收益、只付可读性代价"。与其调低 alpha 折中
     * （那样两边都不成立），不如直接不做。
     */
    fun alphaFor(darkGlass: Boolean): Float = if (darkGlass) PeakAlpha else 0f
}

/**
 * 在材质之上叠一层扫光，`progress` 只在绘制阶段读取。
 *
 * 读取放在绘制 lambda 里而不是组合期：`Animatable` 每帧都在变，组合期读会把 60fps
 * 的动画变成 60fps 的重组 —— 与首页联动那条"`coverProgress` 必须是 lambda"同一条规矩。
 */
internal fun Modifier.glassSweep(peakAlpha: Float, progress: () -> Float): Modifier = drawWithContent {
    drawContent()

    val p = progress()
    if (p <= 0f || p >= 1f || peakAlpha <= 0f) return@drawWithContent

    val width = size.width
    val startX = width * GlassSweep.bandStart(p)
    val endX = width * GlassSweep.bandEnd(p)

    rotate(degrees = GlassSweep.AngleDeg, pivot = center) {
        // 旋转之后要盖住整块面板，所以矩形按"宽度 + 两侧各一个高度"铺开；
        // 溢出部分由 `GlassSurface` 的 `clip(shape)` 收掉。
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = peakAlpha),
                1f to Color.Transparent,
                startX = startX,
                endX = endX,
            ),
            topLeft = Offset(-size.height, -size.height),
            size = Size(width + 2f * size.height, 3f * size.height),
        )
    }
}

/**
 * 进场扫一次。
 *
 * `LaunchedEffect(Unit)` 的语义是"这个组合实例第一次成形时跑一次"：迷你播放器随队列
 * 出现、排序面板每次弹出，都是新的实例，所以每次出现扫一次；而重排、换曲、改文字这些
 * 重组不会重放。常驻面板（底栏、搜索框）不要开这个开关 —— 三块玻璃在启动时同时扫
 * 会读成一次故障。
 */
@Composable
internal fun Modifier.glassSweepOnAppear(enabled: Boolean, isDark: Boolean): Modifier {
    // 浅色玻璃不扫：实测面板只亮 2 级、文字却被洗 5.1 级 —— 见 [GlassSweep.alphaFor]。
    val peakAlpha = GlassSweep.alphaFor(isDark)
    if (!enabled || peakAlpha <= 0f) return this
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        sweep.animateTo(1f, tween(GlassSweep.DurationMs, easing = MilanMotion.Standard))
    }
    return glassSweep(peakAlpha) { sweep.value }
}
