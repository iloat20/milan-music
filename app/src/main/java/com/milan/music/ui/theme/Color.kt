package com.milan.music.ui.theme

import androidx.compose.ui.graphics.Color

// Champagne gold — the whole palette leans pale gold rather than amber.
val Amber = Color(0xFFBE9743)
val AmberBright = Color(0xFFE7CE93)
val AmberPale = Color(0xFFF6EBCF)
val GoldInk = Color(0xFF7A5F22)

val Ink = Color(0xFF2A2419)
val InkMuted = Color(0xFF6C604A)
val Paper = Color(0xFFFAF3E1)
val PaperDeep = Color(0xFFF1E6CD)
val Cream = Color(0xFFFDF9EE)

val Night = Color(0xFF141009)
val NightRaised = Color(0xFF201A11)
val NightMuted = Color(0xFFB5A88E)
val Moonlight = Color(0xFFF8F1E2)

// 玻璃不再自带染色。
//
// 沿革：42% 中性灰薄幕（blur-only 时代用来硬造"体"）→ 10% → 6% → **0**。
// 每一步都是同一个诊断在推进：面板内部不该比背景更亮。
//
// 第 21 批把这条件算清楚了。`GlassStyle.clear` 的浅色链是
//
//     白点 0.17 → 对比度 0.08 → tint 6%
//
// 作用在背景 (238,223,184) 上依次得到 (241,228,196) → (250,237,205) → (251,238,208)，
// 而屏上框内实测 (251,238,203)。三个环节各贡献 +5.4 / +7.6 / +1.9 级，合计 +15。
// 也就是说"太白"里只有 1.9 级来自 tint，主项是白点与对比度 —— 前几批一直在压 tint
// （10% → 6%）却不见效果，原因就在这里。
//
// 同一条链还把 edgeShadow 压出来的暗带一起提亮了：223 × 0.90 = 201 应当给出 −22 级，
// 过完白点与对比度变成 217，只剩 −3。暗带一消失，边缘就只剩一条孤立亮线，
// 面板读成"描边贴纸"而不是"玻璃棱"。
//
// 所以 tint 用库的默认值 Transparent，不再声明。


val Sheen = Color(0x40FFF4D8)
val HairlineGold = Color(0x40C8A550)

/** Liked / favourite accent — iOS system red, readable on both light and dark surfaces. */
val LikedRed = Color(0xFFFF3B30)

/**
 * 首页背景（`HomeScreen.AmbientBackdrop`）的一层光晕。
 *
 * 四个位置参数都是屏幕尺寸的比例，所以换机型时构图不变。
 */
internal data class HomeGlow(
    val color: Color,
    val alpha: Float,
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
)

/**
 * 首页环境的完整配方：一条竖直播渐变 + 叠在上面的几枚径向光晕。
 *
 * 抽成数据而不是把颜色散在 `Canvas` 里，是为了让 `HomeBackdropTest` 能断言
 * **主题里真正生效的那组值**（"淡"和"暗"这类要求只能靠数值守，看图守不住）。
 */
internal data class HomeBackdrop(
    /** `(location, color)`，location 是 0..1 的纵向位置。至少两段 —— 单段等于平涂。 */
    val stops: List<Pair<Float, Color>>,
    val glows: List<HomeGlow>,
)

/**
 * 浅色首页：**顶部淡金 → 底部纸白**。
 *
 * 2026-10-22 用户要求「淡金色要再淡点 … 都要有渐变效果」。上一版是
 * `F5E8C6 → Paper(FAF3E1)`：顶部亮度 0.811、色带 OKLab 彩度 5.3，读起来是一片
 * 偏黄的米色，而且两端的明度差只有 4 级、几乎看不出是渐变。
 *
 * 现在的做法是把金色**只留在上半页**，并整体抬亮一档：
 *
 *  - 顶部 `F7EDD3`：亮度 0.851（+0.040），彩度 3.7（−1.6）——仍是暖金，但明显更淡；
 *  - 中段 `FBF4E4` 只是过渡，防止两端线性插值在中段发灰；
 *  - 底部 `FDFAF2` 接近纸白，与顶部的明度差拉到 11 级（原 4 级），渐变才看得出来。
 *
 * 光晕换成浅香槟（`F2E4C2 / ECDCB4 / E5D3A8`）而不是原来的 `Amber`：
 * `BE9743` 这种深金压在近白底上会沉成一块土黄，与"更淡"直接冲突。
 */
internal val LightHomeBackdrop = HomeBackdrop(
    stops = listOf(
        0.00f to Color(0xFFF7EDD3),
        0.55f to Color(0xFFFBF4E4),
        1.00f to Color(0xFFFDFAF2),
    ),
    glows = listOf(
        HomeGlow(Color(0xFFF2E4C2), 0.36f, 0.16f, 0.10f, 0.80f),
        HomeGlow(Color(0xFFECDCB4), 0.30f, 0.92f, 0.28f, 0.64f),
        HomeGlow(Color(0xFFE5D3A8), 0.26f, 0.42f, 0.86f, 0.74f),
    ),
)

/**
 * 深色首页：**顶部暗金 → 底部近黑**。
 *
 * 同一轮要求「暗黑模式的暗金色要再暗点」。上一版是 `2E2414 → Night(141009)`
 * 叠 `AmberBright` 三枚 30% 光晕：整屏平均 (60,51,33)，顶部亮度 0.039，
 * 在真机上是一片发亮的褐色雾（见 review/2026-10-22-home-backdrop-2.png 左下图）。
 *
 * 现在把金色收进上半页、光晕换成深金（`8A6C2A / 7A5F22 / 6B521E`）并降到 18%/15%/13%：
 * 整屏平均 (33,25,11)，顶部亮度 0.0126、底部 0.0038 —— 暗一档半，且明度差 15 级，
 * 渐变清晰。深色下的 `Moonlight` 文字对比反而更高，不是牺牲可读性换来的。
 */
internal val DarkHomeBackdrop = HomeBackdrop(
    stops = listOf(
        0.00f to Color(0xFF261D0E),
        0.50f to Color(0xFF1A1308),
        1.00f to Color(0xFF100C06),
    ),
    glows = listOf(
        HomeGlow(Color(0xFF8A6C2A), 0.18f, 0.16f, 0.10f, 0.80f),
        HomeGlow(Color(0xFF7A5F22), 0.15f, 0.92f, 0.28f, 0.64f),
        HomeGlow(Color(0xFF6B521E), 0.13f, 0.42f, 0.86f, 0.74f),
    ),
)

/** 首页背景的主题开关 —— 与 [glassColorsFor] 同级：调用点不再各自判深浅。 */
internal fun homeBackdropFor(darkTheme: Boolean): HomeBackdrop =
    if (darkTheme) DarkHomeBackdrop else LightHomeBackdrop

