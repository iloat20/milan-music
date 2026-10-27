package com.milan.music.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.milan.music.data.media.softwareCopy
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 播放页的两段配色，**都取自封面最下面那 20%**。
 *
 * 这两个颜色是**取自封面的本色**，不压暗、不提饱和：只有颜色真的等于封面上那条色带，
 * 眼睛才能验证"背景色是从封面取的"。浅色封面因此得到浅色下半页，深色封面得到深色
 * 下半页 —— 白字在浅色封面上会发灰，这是这个选择已知的代价。
 *
 *  - [top]：同一条色带的**上段**（80%–88%），页面顶端淡出后露出来的就是它；
 *  - [bottom]：整条色带（80%–100%）的平均色，镜面线以下的色场从它开始往下渐暗。
 *
 * 唯一的加工是 [TOP_VS_FIELD]：顶部按色场压一档，"上下两段的颜色不一样"才成立；再加
 * 两条亮度安全上限，避免一张近白封面把整页变成白纸。
 */
internal data class PlayerPalette(
    val top: Color,
    val bottom: Color,
)

/**
 * 取色带：**只有封面最下面 20%**，页面顶端与色场都从这一条里出。
 *
 * 底边取到 20%，是因为最底下那一窄条几乎总是"别的东西"：版权条、渐变压暗、边缘暗角，
 * 它和眼睛在封面上看到的那个色块不是一回事。取 10% 等于把那一窄条当成整块底色；
 * 20% 把它一起平均进去，结果才接近"封面的下缘看起来是什么颜色"。再往上取就会把
 * 封面主体拉进色场，那又变成"整张图的主色"了。
 */
internal const val BOTTOM_BAND_START = 0.80f

/**
 * 页面顶端那一条的颜色，取自**同一条色带的上段**（80%–88%），不再碰封面顶部。
 *
 * 2026-10-23 用户：「播放页取色只取封面最下 20%」。旧实现顶部取封面最上面 10%，
 * 好处是页面顶端与封面顶缘同色，代价是页面被两处取色源拉扯 —— 一张上白下黑的封面，
 * 顶端白、色场黑，中间那条过渡只能靠渐变硬接。只在一条色带里取，上下天然同源。
 *
 * 上段仍然按 [TOP_VS_FIELD] 压一档，所以"上下两段不一样"这条版式要求没有丢：
 * 顶端的暗是**同一条色带内的暗**，不是另一个色相。
 */
private const val TOP_BAND_START = BOTTOM_BAND_START
private const val TOP_BAND_END = 0.88f

/**
 * 页面底端的压暗系数：从镜面线往下，色场再暗一档。
 *
 * 存在的理由是"渐变"必须**任何封面都看得见**。色带自身的纵向差可能接近 0（纯色封面、
 * 大面积平涂的封面），只靠色带内的色差，下半页就是一块死色 —— 那正是用户说的
 * "要渐变"。这一档是线性亮度乘系数，色相与彩度比不变，等于同一块颜色往下暗下去。
 *
 * 0.70 是"看得出在变暗、但不至于读成另一块色"的位置：再低，浅色封面会在屏幕底部
 * 出现一条与封面无关的暗带。
 */
private const val FIELD_FALLOFF = 0.70f

/**
 * 顶部色带相对色场的亮度上限。
 *
 * 参考版式里"上下两段不一样"是硬要求，纯色封面就靠这一条拉开：0.75 是"明显暗一档、
 * 但一眼还是同一个色系"的位置。再往下压，浅色封面会重新出现一条与画面无关的暗带。
 */
private const val TOP_VS_FIELD = 0.75f

/** 顶部色带的亮度安全上限：再亮就是白纸，压回 0.55。 */
private const val TOP_MAX_LUMINANCE = 0.55f

/**
 * 色场亮度的安全上限。
 *
 * 这不是可读性阈值（白字在 0.5 上只有 1.7:1），只是兜住极端情况：封面底部若接近
 * 纯白，整页会变成一张白纸，连进度条和控制图标都看不见。
 */
private const val FIELD_MAX_LUMINANCE = 0.50f

/** 没有封面时的兜底色。 */
internal val FallbackPalette = PlayerPalette(
    top = Color(0xFF33291F),
    bottom = Color(0xFF4E443A),
)

/**
 * 从封面像素推出一套配色。
 *
 * 纯函数：只吃 ARGB 像素和尺寸，方便单测直接构造"上暗底亮""纯色封面"这类样本，
 * 不必经过 Bitmap 解码。
 */
internal fun paletteOf(pixels: IntArray, width: Int, height: Int): PlayerPalette {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return FallbackPalette

    // 两条色带都在封面最下面那 20% 里，只是上下段不同：整条的平均色给出色场，
    // 上段给出页面顶端。**整张图的主色不行**：封面中间往往是主体，和底缘是两种颜色，
    // 用它会在封面边缘留下一条接不上的色带；封面顶部同样不行，那是另一个光源。
    val topSample = bandColorOf(pixels, width, height, TOP_BAND_START, TOP_BAND_END)
        ?: dominantColorOf(pixels, width, height)
        ?: return FallbackPalette
    val bottomSample = bandColorOf(pixels, width, height, BOTTOM_BAND_START, 1f)
        ?: dominantColorOf(pixels, width, height)
        ?: return FallbackPalette

    // 色场就是封面底部那一条本身。之前这里会压到亮度 0.13、再把彩度放大一倍，
    // 结果薰衣草白的封面被压成一块深紫 —— 怎么解释都不像"从封面取的"。
    val bottom = shade(bottomSample, FIELD_MAX_LUMINANCE)

    // 顶部仍比色场暗一档，"上下不一样"才成立；但只在它比色场还亮时才压，所以浅色
    // 封面的顶部依然是一条浅色带，而不是以前那条与画面无关的暗带。
    val ceiling = min(luminanceOf(bottom) * TOP_VS_FIELD, TOP_MAX_LUMINANCE)
    val top = shade(topSample, ceiling)

    return PlayerPalette(top = top, bottom = bottom)
}

/**
 * 把一张位图读成像素再推色，读不到就返回 `null`。
 *
 * 返回 `null` 而不是 [FallbackPalette] 是调用方的语义要求：拿不到颜色时它**保留上一套
 * 配色**，色场照常做颜色动画；换成兜底深褐就是一次可见的闪烁，而那正是"换曲不清空
 * palette"要避免的东西。
 *
 * `getPixels()` 在 `Bitmap.Config.HARDWARE` 上会抛异常，而 `loadThumbnail` 完全可能返回
 * 一张 HARDWARE 位图 —— 所以先经 [softwareCopy] 换一张可读的。只有 96 px 的缩略图会走到
 * 这条路；换不出来同样返回 `null`。
 */
internal fun paletteOfBitmap(image: ImageBitmap): PlayerPalette? {
    val bitmap = image.asAndroidBitmap().softwareCopy() ?: return null
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return paletteOf(pixels, bitmap.width, bitmap.height)
}

/**
 * 某条纵向色带的代表色：**色带的算术平均**。
 *
 * 这是"从封面取色"唯一站得住的定义 —— 一条色带平均下来是什么颜色，下半页就是什么
 * 颜色。这里曾经是"先筛亮部、再按彩度加权"，深色像素被整体剔除：一张封面下缘真实的
 * 平均色是 (162,136,120)，被读成 (212,160,120)，偏亮偏艳，于是怎么看都不像"从封面
 * 取的"。加权平均（`1 + 2·s²`）同样有偏，它把彩度高的那部分放大，等于给封面提了饱和。
 *
 * 只剔除 luma ≤ 3 与 ≥ 251 的像素：那是黑边和高光溢出，不是封面的颜色。整条色带都是
 * 这种像素时返回 `null`（纯黑/纯白封面），交给 [dominantColorOf] 兜。
 */
private fun bandColorOf(
    pixels: IntArray,
    width: Int,
    height: Int,
    fromFraction: Float,
    toFraction: Float,
): Color? {
    val startY = (height * fromFraction).toInt().coerceIn(0, height - 1)
    val endY = (height * toFraction).toInt().coerceIn(startY + 1, height)
    val stepX = max(1, width / 24)
    val stepY = max(1, (endY - startY) / 24)

    var red = 0
    var green = 0
    var blue = 0
    var count = 0
    var y = startY
    while (y < endY) {
        var x = 0
        while (x < width) {
            val color = pixels[y * width + x]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            if (luma in 4..250) {
                red += r
                green += g
                blue += b
                count++
            }
            x += stepX
        }
        y += stepY
    }
    if (count == 0) return null

    return Color(
        red = (red.toFloat() / count).roundToInt(),
        green = (green.toFloat() / count).roundToInt(),
        blue = (blue.toFloat() / count).roundToInt(),
    )
}

/**
 * 整张图的主色，只在某条色带完全没有可用像素时兜底（纯黑封面、纯白封面）。
 *
 * 取的是加权平均，不是"出现次数最多的那个 bin"：4 bit 分箱会把细微的色调差抹平
 * —— 一张几乎中性的灰绿照片会被量化成纯灰，而它其实有一层很薄的青绿。平均色能
 * 留住这层薄色。
 *
 * 两遍筛选是有意的：第一遍只统计**够亮且有彩度**的像素，第二遍才放宽到所有非极端
 * 像素。只做第一遍，整张偏暗的封面会取不到颜色；只做第二遍，一张金箔封面会被它
 * 四周的深紫边框带跑（黑边面积大、彩度还高），最后出来一块紫色。
 *
 * 这条规则只用于兜底，不参与色带取色 —— 色带要的就是它自己的平均值。
 */
private fun dominantColorOf(pixels: IntArray, width: Int, height: Int): Color? {
    val stepX = max(1, width / 24)
    val stepY = max(1, height / 24)

    // 先按网格采样一遍，两轮筛选共用同一批样本。
    val samples = IntArray(24 * 24)
    var count = 0
    var y = 0
    while (y < height && count < samples.size) {
        var x = 0
        while (x < width && count < samples.size) {
            samples[count++] = pixels[y * width + x]
            x += stepX
        }
        y += stepY
    }
    if (count == 0) return null

    return meanOf(samples, count, minLuma = 45, minSaturation = 0.12f)
        ?: meanOf(samples, count, minLuma = 10, minSaturation = 0f)
}

/**
 * 一批样本的加权平均色。
 *
 * 权重 `1 + 2·s²` 让"有颜色"的像素说话更重：同一张图里大面积的灰底不该把结果拉成灰。
 */
private fun meanOf(
    samples: IntArray,
    count: Int,
    minLuma: Int,
    minSaturation: Float,
): Color? {
    var red = 0f
    var green = 0f
    var blue = 0f
    var weightSum = 0f

    for (index in 0 until count) {
        val color = samples[index]
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val luma = (r * 299 + g * 587 + b * 114) / 1000
        if (luma < minLuma || luma > 236) continue
        val saturation = saturationOf(r, g, b)
        if (saturation < minSaturation) continue

        val weight = 1f + 2f * saturation * saturation
        red += r * weight
        green += g * weight
        blue += b * weight
        weightSum += weight
    }
    if (weightSum <= 0f) return null

    return Color(
        red = (red / weightSum).roundToInt(),
        green = (green / weightSum).roundToInt(),
        blue = (blue / weightSum).roundToInt(),
    )
}

/**
 * 整页背景的三个色点：页面顶端 → 镜面线 → 页面底端。
 *
 * 这是播放页背景**唯一的配方**，绘制与测试读的是同一个函数，所以"底端比镜面线更暗"
 * 这类要求能钉成断言，而不是靠改一层再改一层去凑。
 *
 *  - 第一段（0 → 镜面线）：封面底缘到页面顶端的过渡。它大部分被封面盖住，只有上缘
 *    淡出的那一段露出来，所以两端的色差只需要"存在"，不需要很大；
 *  - 第二段（镜面线 → 底端）：色场往下暗一档，见 [FIELD_FALLOFF]。
 *
 * 镜面线位置夹在 [MIRROR_LINE_MIN]..[MIRROR_LINE_MAX] 里：屏幕比例极端时
 * `heroHeight / pageHeight` 仍会落在这个区间，但夹一次可以保证三个色点严格递增
 * （`Brush.verticalGradient` 要求 location 升序，相等会画出一条硬边）。
 */
internal fun backdropStopsOf(
    palette: PlayerPalette,
    coverBottom: Float,
): List<Pair<Float, Color>> {
    val mirrorLine = coverBottom.coerceIn(MIRROR_LINE_MIN, MIRROR_LINE_MAX)
    return listOf(
        0f to palette.top,
        mirrorLine to palette.bottom,
        1f to darken(palette.bottom, FIELD_FALLOFF),
    )
}

/** 镜面线（封面底边）在整页里的位置区间 —— 见 [backdropStopsOf]。 */
private const val MIRROR_LINE_MIN = 0.10f
private const val MIRROR_LINE_MAX = 0.92f

/**
 * 在**线性**空间里把三个通道乘同一个系数：色相与彩度比不变，只是整体变暗。
 *
 * 不在 sRGB 里乘，是因为那样会把暗部压脏（同样的比例，暗部丢掉的信息更多）。
 * [shade] 压亮度上限走的是同一条路，两者共用这里。
 */
internal fun darken(color: Color, factor: Float): Color {
    val k = factor.coerceIn(0f, 1f)
    return Color(
        red = linearToSrgb(srgbToLinear(color.red) * k),
        green = linearToSrgb(srgbToLinear(color.green) * k),
        blue = linearToSrgb(srgbToLinear(color.blue) * k),
    )
}

/**
 * 把颜色压到目标亮度上限之内。
 *
 * 默认行为是"原样返回"—— 只有超过上限才动手。动手时在**线性**空间里对三个通道乘同
 * 一个系数：色相与饱和度比例完全不变，只是整体变暗，不会像在 sRGB 里乘系数那样把
 * 暗部压脏。
 *
 * **这里没有"纯黑给中性色"的分支，也不该有**：上一行已经是"不超过上限就原样返回"，
 * 而纯黑的亮度是 0、上限不可能小于 0，所以那个分支永远走不到。原先它存在时，注释
 * 承诺的"纯黑给中性色"是假的 —— 真正的兜底在上游：[bandColorOf] 对纯黑封面返回
 * `null`，[paletteOf] 于是退回 [FallbackPalette]，压根不会拿纯黑来 [shade]。
 *
 * 接近纯黑但不等于黑的颜色同样安全：`factor = 上限 / 亮度` 会大于 1，被 [darken]
 * 钳到 1，结果还是原色，不会被放大成一块彩色噪声。
 */
internal fun shade(color: Color, maxLuminance: Float): Color {
    val luminance = luminanceOf(color)
    if (luminance <= maxLuminance) return color
    return darken(color, maxLuminance / luminance)
}

/** 相对亮度（WCAG 2.1 定义），0 = 黑、1 = 白。 */
internal fun luminanceOf(color: Color): Float =
    0.2126f * srgbToLinear(color.red) +
        0.7152f * srgbToLinear(color.green) +
        0.0722f * srgbToLinear(color.blue)

private fun srgbToLinear(channel: Float): Float =
    if (channel <= 0.04045f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)

private fun linearToSrgb(channel: Float): Float {
    val value = channel.coerceIn(0f, 1f)
    return if (value <= 0.0031308f) value * 12.92f else 1.055f * value.pow(1f / 2.4f) - 0.055f
}

/** HSV 饱和度，只为给平均色加权。 */
private fun saturationOf(r: Int, g: Int, b: Int): Float {
    val maxChannel = max(r, max(g, b))
    if (maxChannel == 0) return 0f
    val minChannel = min(r, min(g, b))
    return (maxChannel - minChannel).toFloat() / maxChannel
}
