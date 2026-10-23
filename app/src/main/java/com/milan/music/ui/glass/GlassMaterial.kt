package com.milan.music.ui.glass

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.glass.GlassStyle

/**
 * 色散强度（`chromaticAberrationStrength`，0..1）。
 *
 * Clear 自带的校准值是 0.04，几乎分不出色；文档给的起步区间是 0.1~0.25。取区间下沿而不是
 * 上沿：我们的面板只有 46~64dp 高，28dp 的折射带已经很窄，色散只在带内那几像素上分光，
 * 再往上调就会在圆角处先出一圈彩虹边。
 */
internal const val GlassChromaticAberration = 0.12f

/**
 * 把圆角落成一条 `GlassStyle`。
 *
 * 底座是 **`GlassStyle.clear`**，不是 `regular`。第 19 批用 Regular 时用户的三条反馈
 * ——「太白了」「不够透明」「只有模糊、边缘没弯折」——逐条都能在 Regular 的校准值里
 * 找到出处：
 *
 *  | 现象 | Regular | Clear |
 *  |---|---|---|
 *  | 只有模糊 | `blurRadius` 20→25dp 自适应，大面积充分漫散 | **固定 1.25dp** |
 *  | 太白 / 不透明 | `whitePoint = 0.55`（整体大幅提亮） | **0.17** |
 *  | 边缘没弯折 | `refractionDetailIntensity = 0`（关掉边缘二次折射） | **0.76** |
 *  | 棱不厚 | `Edge(20.dp)`、位移 48dp | **`Edge(28.dp)`、位移 56dp** |
 *  | 不像玻璃 | `refractionStrength` 0.7、`ambientResponse` 0.15 | **0.85 / 0.42** |
 *
 * 但 Clear 也留了两个问题，第 21 与第 26 批逐个关掉：
 *
 * **一、色调链（第 21 批）**。Clear 的浅色链是
 *
 *     白点 0.17 → 对比度 0.08 → tint
 *
 * 作用在 L≈0.87 的亮背景上，两段各贡献 +5.4 / +7.6 级，把背景 (238,223,184)
 * 推到 (251,238,203)——与屏上实测的框内色逐通道吻合（误差 ≤2 级）。所以"太白"
 * 的主项是**白点和对比度**，不是 tint；前几批一直在压 tint（10%→6%），压不出效果。
 * 同一条链还把 `edgeShadow` 的 −22 级暗带提亮成 −3，暗带一消失边缘只剩孤立亮线。
 * → `whitePoint(0f)`、`contrast(0f)`，`tint` 不写（库默认 Transparent）。
 *
 * **二、边缘亮线（第 26 批）**。`specularIntensity = 0.55` 是方向性镜面高光、
 * `ambientResponse = 0.42` 是菲涅尔边缘提亮，两者叠出的一圈亮边在浅色背景上会被
 * 读成"塑料描边"而不是玻璃。真实玻璃在均匀光照下边缘本来就是**暗**的
 * （全反射与吸收），亮线只在有明确光源且掠射时才出现，而且极窄。
 *
 * **三、边缘暗带也关掉（第 27 批）**。用户：「暗边还是很丑」。`edgeShadow` 和亮线
 * 一样是**画上去的**，不是光学——它按形状描一圈固定强度的暗色，所以读成"脏边"。
 * 真正"玻璃效果的边"只能由折射产生：光线在 Edge(28dp) 的棱上被弯折，边缘那一圈
 * 采到的是被压缩的内侧内容。而折射要可见，前提是**背景在几十像素内有明暗/色相变化**；
 * 首页背景是一条垂直渐变 + 三个大半径光晕，56dp 位移尺度上的亮度差只有 0.5~3.6 级
 * （第 20 批实测），折射采回来的是同一个颜色。**所以在这块背景上，画出来的边和
 * 折射出来的边只能二选一，而"不画"更接近真实玻璃。**
 *
 * 于是现在边缘**完全没有绘制层**：`edgeShadow(Color.Transparent)`，库文档明确
 * "Color.Transparent disables the dark edge"。浮起感只由 `GlassSurface` 的投影给出，
 * 边界感只由折射给出（有背景细节时）。
 *
 * 保留覆盖的只有形状与色散。折射剖面（0.85 / `Edge(28dp)` / 56dp）、1.25dp 浅模糊
 * 全部按库的 iOS 27 校准值走。
 */
@OptIn(ExperimentalHazeApi::class)
internal fun glassStyleFor(cornerShape: RoundedCornerShape): GlassStyle = GlassStyle.clear.then {
    shape(cornerShape)
    // 一、不做色调偏移：库的白点与对比度会在亮背景上把整块面板推高 15 级、
    // 同时抹掉暗边。
    whitePoint(0f)
    contrast(0f)
    // 二、关掉边缘亮线。这两项分别管镜面高光与菲涅尔提亮，缺一个另一条还在。
    specularIntensity(0f)
    ambientResponse(0f)
    // 三、连暗带也关掉：它是画出来的描边，不是光学。文档明确 Transparent 即禁用。
    edgeShadow(Color.Transparent)
    chromaticAberrationStrength(GlassChromaticAberration)
}
