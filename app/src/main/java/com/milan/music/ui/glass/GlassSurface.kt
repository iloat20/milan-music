package com.milan.music.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.milan.music.ui.theme.LocalGlassColors
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.hazeGlass

/**
 * Liquid Glass 面板。
 *
 * 第 19 批之前这里是自己拼的：`hazeBlur` + 42% 薄幕 + 手画的亮线/暗带。
 * 一共改过四版，每版都被否，根因不在数值——**`hazeBlur` 只有高斯模糊，没有折射**。
 * 2026-10-22 的实测截图里，搜索框内部与背景只差 4 级、色度不变，整块面板只剩
 * 一条 1dp 描边；而参考实现（liquid-rust、glass-lens-effect、Kyant Backdrop）的
 * 一致结论是 "most of the effect comes from bending light, not from blur"。
 *
 * 现在用 `haze-glass` 的 **`GlassStyle.clear`**：折射、边缘二次折射细节、
 * Fresnel、方向性镜面高光、形状跟随的暗边都由库给出，我们只负责形状。
 *
 * 为什么是 Clear 不是 Regular：Regular 的模糊随最短边自适应到 20~25dp，在这块
 * 46dp 高的搜索框上会把背后的低频渐变完全糊成一块均匀的奶白（用户看到的
 * "太白了、只有模糊"），而它的 `refractionDetailIntensity` 是 0——边缘没有任何
 * 二次折射细节（"边缘没弯折"）。Clear 把这些反过来：1.25dp 浅模糊 + 76% 边缘
 * 折射细节 + 28dp 折射带，代价是背后内容更清晰，这正是"透明"的定义。
 *
 * 第 21 批又往前推了一步：**把白点与对比度归零、彻底去掉 tint**。Clear 的浅色链
 * 会把亮背景上的面板整体推高 15 级、并把暗边抹平到 −3，那正是"奶白块 + 一根描边"
 * 的完整成因。归零后面板内部与背景等价，只在边界留下光学（详见 `glassStyleFor`）。
 *
 * 关于 `blurRadius`：这个参数被**删掉**了。两种内置样式都自带按尺寸校准的模糊，
 * 外部再塞一个固定 dp 等于关掉那套校准。要调就用 `optics { copy(...) }`。
 *
 * 第 73 批加了 [sweepOnAppear]：面板"出现"时扫一次高光。只给会出现的那两块（迷你
 * 播放器、排序面板）—— 常驻面板不要开，三块玻璃在启动时同时扫一次看起来就是一次故障。
 * 扫光是纯叠加绘制，不读 HazeState、不改采样坐标，详见 `GlassSweep`。
 *
 * Note: a `hazeGlass` node may not draw an ancestor `hazeSource`. Callers must
 * therefore place glass surfaces as siblings of the source, not inside it.
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun GlassSurface(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(28.dp),
    sweepOnAppear: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = LocalGlassColors.current

    // Style 可以被复用，但换圆角时必须重建：Style 比较走 identity，
    // 留在旧实例上等于面板不跟随形状。
    val style = remember(shape) { glassStyleFor(shape) }

    // 极轻投影只负责轮廓脱离，不参与"体"：12dp、8%~10% 黑，浅色主题才给。
    // haze-glass 的 `edgeShadow` 是**贴着边缘**的暗边，投影是**落在背景上**的影子，
    // 两者不是一件事，都要有。
    Box(
        modifier = modifier
            .shadow(
                elevation = if (glass.isDark) 0.dp else 12.dp,
                shape = shape,
                ambientColor = Color(0x0D000000),
                spotColor = Color(0x14000000),
            )
            // 子内容（封面、图标）自己也需要裁剪：haze-glass 的 shape 只保证材质边界，
            // 不会约束里面画什么。
            .clip(shape)
            // 扫光必须在 clip 之后：它靠形状裁剪收掉旋转后溢出的矩形。
            .glassSweepOnAppear(sweepOnAppear, glass.isDark)
            .hazeGlass(
                input = HazeInput.Backdrop(hazeState),
                style = style,
            ),
        content = content,
    )
}
