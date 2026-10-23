package com.milan.music.ui.player

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 模糊阶梯上的一级：**半径 [radius] 的那一层，在距镜面线 [at] 处占满**。
 *
 * [at] 以镜面线（= 封面框底）为 0，负数在上。整条阶梯就是"越往下越糊"的离散化：
 * 每一层只在它 [at] 附近占主导，相邻两层用平滑曲线交接。
 */
internal data class BlurStop(val radius: Dp, val at: Dp)

/**
 * 封面的模糊阶梯 —— 这是这一版的**全部**设计。
 *
 * 上一版（第 45~53 批）是"同一张图的清晰版与糊版按透明度交叉淡入"，那在物理上就是
 * **重影**：中段同时看得见清晰的边和它的糊影，读起来既不像照片也不像倒影，真机截图
 * 里过渡带整段 RMS 掉到 0.36~0.51（清晰封面是 5.9）—— 这就是用户说的"模糊不好看、
 * 不自然"。正确做法是让**半径**连续变化，而每一级都保持满不透明。
 *
 * 阶梯在**起点附近压得很平**（0 → 2 → 5），把半径的增长让给最后三分之一 ——
 * 封面正文（这里正好压着专辑名那一行）因此还能读，而接缝两侧已经糊得足够。
 * 相邻两级最多差 2.5 倍，交接时看不出"换了一层"。镜面线（0）正好落在一级的正中间，所以两侧共用**同一次**模糊、
 * **同一张位图的相邻两行** —— 接缝在结构上不可能出现，不是调参调没的。
 *
 * 第 0 级（半径 0）不由这个组件画，它由**清晰封面本体**承担：阶梯最上面那一段的
 * 权重天然让位给封面，所以过渡带的起点没有任何边界（那里阶梯权重恰好为 0）。
 */
internal val BlurRamp: List<BlurStop> = listOf(
    BlurStop(radius = 0.dp, at = -128.dp),
    BlurStop(radius = 2.dp, at = -86.dp),
    BlurStop(radius = 5.dp, at = -44.dp),
    BlurStop(radius = 12.dp, at = 0.dp),
    BlurStop(radius = 22.dp, at = 96.dp),
    BlurStop(radius = 34.dp, at = 240.dp),
)

/** 阶梯盖住封面的那一段高度（镜面线以上）。 */
internal val CoverRamp: Dp = -BlurRamp.first().at

/**
 * 镜面线所在的那一级：它在镜面线处权重恰为 1。
 *
 * 这一条是"接缝不可能出现"的全部理由 —— 镜面线两侧都被**同一个图层、同一次模糊、
 * 同一张位图的相邻两行**画出来，两侧的糊度与亮度按构造相等，不是靠参数凑出来的。
 */
internal val SeamStopIndex: Int = BlurRamp.indexOfFirst { it.at == 0.dp }

/**
 * 镜面线下方这段保持满 alpha —— 倒影"贴近物体处最实"。
 *
 * 不能从 0 就开始衰减：那样镜下第一个像素就只剩 0.99，往下掉得比真实反射快，
 * 倒影读起来像"一上来就没了"。用户要的是"和封面相近的倒影清楚一点，再往下
 * 慢慢模糊"，所以先给一段实的，再化开。
 */
internal val FadeHold: Dp = 24.dp

/**
 * 倒影可见长度：镜面线下方这么远之内化进色场。
 *
 * **这一条是"封面出现两次"的解药。** 倒影是封面的镜像，内容必然重复，重复多少由
 * **可见长度**决定：可见 150dp 时翻下来的是封面最后三分之一的红领与暗部，够认人；
 * 收到 104dp（约封面高度的四分之一）之后，翻下去只剩衬衫下摆和背景，
 * 读起来是"封面底下透出来的一层光"，而不是第二张封面。
 *
 * 配上 [coverRowAt] 的逐行映射，这个长度是**可算的**：可见段 = 封面最后 104dp。
 */
internal val FadeEnd: Dp = 104.dp

/**
 * 纵向包络的采样点数：**按节点高度定，不是常数**。
 *
 * 包络是"权重 × 衰减"的乘积，写不进几个拐点 —— 只能采样成多段线性渐变。采样太稀
 * 会读出带状：阶梯里最窄的一段（0→4dp 那一级）只跨 42dp，采样步长一旦超过它的一半，
 * 线性化误差就会顶出 2%（≈5/255）的台阶。所以步长固定 4dp、上下封口。
 */
internal fun maskSamplesFor(nodeHeight: Dp): Int =
    (nodeHeight.value / MaskStepDp).toInt().coerceIn(MaskMinSamples, MaskMaxSamples)

private const val MaskStepDp = 4f
private const val MaskMinSamples = 64
private const val MaskMaxSamples = 200

/** 倒影最深处压暗多少 —— 保证白字可读，且从镜面线开始连续增长（不是台阶）。 */
internal const val ScrimMax = 0.26f

/** 压暗在第几成（占倒影高度）到满值。 */
internal const val ScrimFullAt = 0.45f

/**
 * 平滑插值：两端导数为 0，所以阶梯交接处不会出现斜率突变（人眼对这种突变很敏感，
 * 会读成一条"淡淡的分界"）。
 */
internal fun smoothstep(raw: Float): Float {
    val t = raw.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** 第 [index] 级之前那一小段"升上来"的进度：在 `stops[index-1].at` 处 0、`stops[index].at` 处 1。 */
private fun rise(stops: List<BlurStop>, index: Int, y: Dp): Float {
    val lo = stops[index - 1].at
    val hi = stops[index].at
    if (hi <= lo) return if (y >= hi) 1f else 0f
    return smoothstep((y - lo) / (hi - lo))
}

/**
 * 第 [index] 级在位置 [y] 的权重（`index = 0` 是清晰层，由封面本体承担）。
 *
 * 这是**单位分解**：任意 [y] 上 `Σ index 0..last` 恒等于 1。这一条是整个方案的地基 ——
 * 只有它成立，镜面线两侧才永远是同一份内容、同一个亮度，不可能出现台阶；
 * 也只有它成立，过渡带里不会漏出底下的清晰封面（漏出来就是重影）。
 */
internal fun layerWeight(index: Int, y: Dp, stops: List<BlurStop> = BlurRamp): Float {
    if (index < 0 || index > stops.lastIndex) return 0f
    val down = if (index == 0) 1f else rise(stops, index, y)
    val up = if (index == stops.lastIndex) 0f else rise(stops, index + 1, y)
    return (down - up).coerceAtLeast(0f)
}

/** 位置 [y] 处的等效模糊半径：阶梯的线性插值，供测试与预览脚本使用。 */
internal fun blurAt(y: Dp, stops: List<BlurStop> = BlurRamp): Dp {
    if (stops.isEmpty()) return 0.dp
    if (y <= stops.first().at) return stops.first().radius
    if (y >= stops.last().at) return stops.last().radius
    for (index in 1..stops.lastIndex) {
        val lo = stops[index - 1]
        val hi = stops[index]
        if (y <= hi.at) {
            val t = (y - lo.at) / (hi.at - lo.at)
            return lo.radius + (hi.radius - lo.radius) * t
        }
    }
    return stops.last().radius
}

/**
 * 倒影整体的纵向衰减：[FadeHold] 之内恒为 1（镜面线那段必须满不透明），
 * 之后平滑收到 0，到 [FadeEnd] 收干净。
 *
 * 终点取 `min(bottom, [FadeEnd])`：位图照旧铺满整个倒影区（这样模糊的取样范围不变），
 * 但**可见**部分只到 [FadeEnd]，再往下交给色场。理由见 [FadeEnd]。
 *
 * 镜面线处必须严格等于 1：只要 < 1，倒影就是半透明的，而上方封面是不透明的 ——
 * 那一行立刻变成亮度台阶（第 53 批真机：整行 242 列同时暗 4.3%）。
 */
internal fun fadeAt(y: Dp, bottom: Dp, hold: Dp = FadeHold): Float {
    if (y <= hold) return 1f
    val end = minOf(bottom, FadeEnd)
    val span = end - hold
    if (span <= 0.dp) return 0f
    return 1f - smoothstep((y - hold) / span)
}

/**
 * 第 [index] 级的纵向包络采样（每个采样点的 alpha，沿节点高度均匀分布）。
 *
 * 节点 = 封面那 [CoverRamp] + 倒影 [bottom]，采样从节点顶端开始。
 * 采样点比拐点多，是为了让"权重 × 衰减"这条乘积曲线在分段线性下仍然平滑。
 */
internal fun maskAlphas(
    index: Int,
    bottom: Dp,
    stops: List<BlurStop> = BlurRamp,
    hold: Dp = FadeHold,
    samples: Int = 0,
): FloatArray {
    val band = -stops.first().at
    val node = band + bottom
    val count = (if (samples > 0) samples else maskSamplesFor(node)).coerceAtLeast(2)
    val out = FloatArray(count)
    for (j in 0 until count) {
        val y = Dp(node.value * j / (count - 1).toFloat()) - band
        out[j] = (layerWeight(index, y, stops) * fadeAt(y, bottom, hold)).coerceIn(0f, 1f)
    }
    return out
}

/** 单层退化路径（没有 RenderEffect、或拿不到封面）用的包络：就是纯衰减。 */
internal fun fadeAlphas(
    bottom: Dp,
    hold: Dp = FadeHold,
    samples: Int = 0,
): FloatArray {
    val count = (if (samples > 0) samples else maskSamplesFor(bottom)).coerceAtLeast(2)
    val out = FloatArray(count)
    for (j in 0 until count) {
        val y = Dp(bottom.value * j / (count - 1).toFloat())
        out[j] = fadeAt(y, bottom, hold)
    }
    return out
}

/**
 * 能不能画成"阶梯"：要 RenderEffect（API 31+），也要封面本体（上半段画的是封面）。
 *
 * 任一条不成立就退回单层倒影 —— 那时镜面线两侧的糊度会差一截，但至少画得出来。
 * 降级可以，静默空白不行。
 */
internal fun canRamp(cover: ImageBitmap?, sdkInt: Int): Boolean =
    cover != null && sdkInt >= Build.VERSION_CODES.S

/**
 * 这条带子到底画不画得出来 —— **三个开关的唯一判据**。
 *
 * 它是 [CoverReflection]、封面下缘的淡出（`heroFadeMask(fadeBottom)`）、隔壁页的底边
 * 淡出（`fadeNeighbourBottoms`）共用的同一个布尔值。三者必须同生同灭：只要有一处
 * 自己判断"有没有倒影"，切歌那一瞬间就会出现"带子没了但封面底边还在硬接"这种半截
 * 状态，读起来就是一次闪。
 *
 * **阶梯路径不需要那张翻转位图**：它从 `cover` 取行号，翻折在画布上做
 * （`translate(0, 2·band) · scale(1, -1)`）。`mirror` 只服务 API 30 及以下的单层
 * 降级，那里没有 `Modifier.blur`，只能把整张翻转图交给 `Image` 铺。
 *
 * 这一条是 2026-10-23 第 59 批的根因：旧判据是 `mirror == null`，而 `mirror` 由一个
 * 异步解码 + 512² 翻转产出（`reflectionCache` 的预算还小于单张位图，必然 miss），
 * 切歌后要 10~30ms 才到 —— 这段时间整条带子（含压住封面末段 128dp 的过渡带）先是
 * 旧内容、再整体换掉，用户看到的就是"闪一下"。
 */
internal fun canDrawBand(hasCover: Boolean, hasMirror: Boolean, sdkInt: Int): Boolean =
    (hasCover && sdkInt >= Build.VERSION_CODES.S) || hasMirror

/**
 * 叠层**向 Column 报告**的排版高度：**恒为 0**。
 *
 * 这一条是硬约束，不是优化。叠层的绘制高度是 `CoverRamp + 倒影高`（约 557dp），
 * 而它挂在 Column 的镜面线上、下面紧跟 [com.milan.music.ui.player.PlayerTransport]。
 * 只要它报告真实高度，Column 就会把它当占位，把信息区与控制条整体推下去 ——
 * 2026-10-23 第 55 批真机：557dp 直接吃掉整屏，歌名、进度条、播放键全部出界，
 * 屏幕上只剩封面和倒影。
 */
internal val OverlayLayoutHeight: Dp = 0.dp

/**
 * 叠层真正要画的纵向尺寸（从镜面线以上 [CoverRamp] 一直到页底）。
 *
 * 与 [OverlayLayoutHeight] 的差值就是它"看得见但摸不着"的那一段 —— 这点很重要：
 * 布局高度为 0 并不代表不绘制，Compose 允许子元素画到自己的边界之外。
 */
internal fun overlayDrawHeight(bottom: Dp, ramp: Boolean = true): Dp =
    (if (ramp) CoverRamp else 0.dp) + bottom

/**
 * 只绘制、不占位的修饰符：按 [drawHeight] 测量内容，却向父级报告 [OverlayLayoutHeight]。
 *
 * 用 `layout {}` 而不是 `height(0.dp)`：后者会把子内容的测量约束压成 0，图就没了。
 */
private fun Modifier.overlayOnly(drawHeight: Dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(
            Constraints.fixed(constraints.maxWidth, drawHeight.roundToPx()),
        )
        layout(constraints.maxWidth, OverlayLayoutHeight.roundToPx()) {
            placeable.place(0, 0)
        }
    }

/**
 * 倒影 + 跨缝过渡带，一个组件画完。
 *
 * **坐标系原点就是镜面线**：调用点必须把它放在封面框底（`CoverLayout.heroHeight`）上，
 * 组件自己向上溢出 [CoverRamp] 盖住封面末段、向下铺满整页。之所以要溢出到封面上，
 * 是因为过渡带里"封面那半段"必须画在**清晰封面之上**才看得见（第 51 批：画在下面
 * 被封面整个盖掉，真机就是"带子里 121px 一点没糊"）。
 *
 * 画在 Column 里（封面之后、控件之前）是这个组件唯一的工作位置：
 * 上面压住封面末段，下面被歌曲信息与控制压住 —— 文字永远在最上层，不会像上一版
 * 那样被 0.9 alpha 的倒影盖成一片洗白。
 *
 * [heroHeight] 是封面框高：镜面线以下的每一行都由 [coverRowAt] 从**封面框**取行号，
 * 所以倒影与封面共用同一套取景，接缝两侧是同一张位图的相邻两行。
 * [height] 是倒影区高度（页底到镜面线）；它只决定"最多能画多长"，
 * 真正可见的长度由 [FadeEnd] 决定。
 *
 * [contentScale] 必须传**封面自己用的那一个**（`CoverLayout.HeroContentScale`）：
 * 封面是裁切 + 纵向 5% 拉伸取景的，倒影若按整幅铺满，横向会差 1.5%（方图）到
 * 7.4%（宽图），人物轮廓会在镜面线两侧错开。
 *
 * **API 30 及以下没有 `Modifier.blur`**（那是 API 31 的 RenderEffect），那些设备上
 * 靠源尺寸制造软度：仓库把倒影缩到 48px，放大到整屏只剩色斑。降级路径仍在用
 * `Image` + [CoverLayout.HeroMirrorAlignment] 铺那张翻转位图 —— 它只画一层、没有
 * 阶梯，定位误差最多是半行，不像阶梯路径那样会把封面中段搬下来。
 */
@Composable
internal fun CoverReflection(
    cover: ImageBitmap?,
    mirror: ImageBitmap?,
    heroHeight: Dp,
    height: Dp,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    if (height <= 0.dp) return
    // 判据见 [canDrawBand]：阶梯路径只认 `cover`，别拿那张它根本不画的翻转位图当门槛。
    if (!canDrawBand(cover != null, mirror != null, Build.VERSION.SDK_INT)) return

    val ramp = canRamp(cover, Build.VERSION.SDK_INT)
    val band = if (ramp) CoverRamp else 0.dp
    val nodeHeight = overlayDrawHeight(height, ramp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 只画不占位：报告 0 高度，见 [OverlayLayoutHeight]。
            .overlayOnly(nodeHeight)
            .offset(y = -band),
    ) {
        if (!ramp || cover == null) {
            val alphas = remember(height) { fadeAlphas(height) }
            val brush = remember(alphas) { alphas.toBlackBrush() }
            // 降级路径由 [canDrawBand] 保证 `mirror` 非空。
            val degraded = mirror ?: return@Box
            Image(
                bitmap = degraded,
                contentDescription = null,
                contentScale = contentScale,
                alignment = CoverLayout.HeroMirrorAlignment,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .rampMask(brush),
            )
            return@Box
        }

        // 由远到近：最糊的先画。近处那几级的包络在镜面线附近才满值，后画才盖得住。
        for (index in BlurRamp.lastIndex downTo 1) {
            val stop = BlurRamp[index]
            val alphas = remember(index, height) { maskAlphas(index, height) }
            val brush = remember(alphas) { alphas.toBlackBrush() }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(nodeHeight)
                    // 包络在**模糊外面**：`Modifier.blur` 若在外，包络的 alpha 会被
                    // 模糊一起稀释，镜面处从 1.0 掉到 0.7，更暗的底层透出来 ——
                    // 2026-10-23 真机量到 y=960 整行 242 列同时暗 11/255（4.3%）。
                    .rampMask(brush)
                    .blur(radius = stop.radius, edgeTreatment = BlurredEdgeTreatment.Rectangle),
            ) {
                RampLayerContent(
                    cover = cover,
                    band = band,
                    heroHeight = heroHeight,
                    height = height,
                    contentScale = contentScale,
                )
            }
        }

        // 越往下压暗一点，白字才立得住。从镜面线开始连续增长，没有起跳点。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .offset(y = band)
                .background(ScrimBrush),
        )
    }
}

/**
 * 封面位图在「框 873 × heroH」里的实际放缩与偏移 —— 与 `HeroContentScale` 逐字同源。
 *
 * [offsetX] 通常为负（铺满时横向溢出、左右对称裁掉），[offsetY] 恒为 0（TopCenter）。
 * 把这两个数算出来，才能在画布上**按像素**定位封面与倒影，而不是把定位交给
 * `Image` 的 `contentScale` + `alignment`。
 */
internal data class CoverFit(
    val scaleX: Float,
    val scaleY: Float,
    val offsetX: Float,
    val offsetY: Float,
)

/** 由 `ContentScale.computeScaleFactor` 的结果推出框内偏移（TopCenter 取景）。 */
internal fun coverFit(
    scaleX: Float,
    scaleY: Float,
    srcWidth: Int,
    srcHeight: Int,
    dstWidth: Float,
): CoverFit = CoverFit(
    scaleX = scaleX,
    scaleY = scaleY,
    offsetX = (dstWidth - srcWidth * scaleX) / 2f,
    offsetY = 0f,
)

/**
 * 节点第 [nodeY] 行对应封面框里的第几行（框内坐标，0 = 封面顶、[heroHeight] = 镜面线）。
 *
 * 镜面线以上是封面本体（[band] 那一段），以下是以镜面线为轴的镜像：往下走一行，
 * 封面往上退一行。**这一条是整个倒影几何的唯一真相** —— 画布照它取像素，
 * 测试照它验算，两边不可能各说各话。
 */
internal fun coverRowAt(nodeY: Float, band: Float, heroHeight: Float): Float =
    if (nodeY <= band) heroHeight - band + nodeY
    else heroHeight - (nodeY - band)

/**
 * 一层阶梯的内容：上半是封面的最后 [band]（底边贴镜面线），下半是倒影。
 *
 * **用画布按像素画，不用 `Image`。** 上一版把定位交给 `Image` 的 `contentScale` +
 * `BottomCenter`：那条路径的正确性依赖"缩放后的位图高度恰好等于节点高度"，源图一竖
 * （裁切量不为 0）这个前提就破了 —— 倒影会整体上下滑一截，把封面的**中段**搬进过渡带。
 * 2026-10-23 真机就是这么"封面出现两次"的：过渡带里出现倒着的面具、倒着的 SWING 和
 * 倒着的脸，而原图对应那几行只是衬衫和背景。
 *
 * 现在两半都从 [coverRowAt] 取行号：[band] 处取到 [heroHeight]（封面最底一行），
 * 往下严格逐行退回。翻折用 `translate(0, 2·band) · scale(1, -1)` 绕镜面线做，
 * 不经过任何 layout 节点 —— 偏移量为 0，没有可以"滑掉"的自由度。
 */
@Composable
private fun RampLayerContent(
    cover: ImageBitmap,
    band: Dp,
    heroHeight: Dp,
    height: Dp,
    contentScale: ContentScale,
) {
    val bandPx = with(LocalDensity.current) { band.toPx() }
    val heroPx = with(LocalDensity.current) { heroHeight.toPx() }
    val heightPx = with(LocalDensity.current) { height.toPx() }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // 取景换算放在绘制里做：`size` 只有到这里才是最终宽度。
        val factor = contentScale.computeScaleFactor(
            Size(cover.width.toFloat(), cover.height.toFloat()),
            Size(size.width, heroPx),
        )
        val fit = coverFit(factor.scaleX, factor.scaleY, cover.width, cover.height, size.width)

        // 镜面线以上：封面的最后 band 行，原样（不翻折）。
        drawCoverRows(
            cover = cover,
            fit = fit,
            frameTop = heroPx - bandPx,
            frameRows = bandPx,
            dstTop = 0f,
            dstHeight = bandPx,
        )
        // 镜面线以下：绕 y = bandPx 翻折。先画到 [band-mirrorPx, band]，翻折后正好落到
        // [band, band+mirrorPx]，且行序反转 —— 这就是镜像。
        //
        // 只翻折封面高度那么长的像素：倒影区往往比封面高（页底到镜面线），多出来的
        // 那截本来就该是纯色场。截断成"镜像的镜像"会把封面顶再次搬回来。
        val mirrorPx = minOf(heightPx, heroPx)
        withTransform({
            translate(left = 0f, top = 2f * bandPx)
            scale(scaleX = 1f, scaleY = -1f, pivot = Offset.Zero)
        }) {
            drawCoverRows(
                cover = cover,
                fit = fit,
                frameTop = heroPx - mirrorPx,
                frameRows = mirrorPx,
                dstTop = bandPx - mirrorPx,
                dstHeight = mirrorPx,
            )
        }
    }
}

/**
 * 把封面框里 [frameTop] 起的 [frameRows] 行，按 [fit] 换算成源图矩形，画到 [dstTop]。
 *
 * `frameRows`/`dstHeight` 都按"一行源像素对应一行目标像素"的等比关系给，所以这里
 * 只有一次取整，接缝处的半像素误差不会累积。
 */
private fun DrawScope.drawCoverRows(
    cover: ImageBitmap,
    fit: CoverFit,
    frameTop: Float,
    frameRows: Float,
    dstTop: Float,
    dstHeight: Float,
) {
    val srcX = ((-fit.offsetX) / fit.scaleX).roundToInt().coerceIn(0, cover.width - 1)
    val srcWidth = (size.width / fit.scaleX).roundToInt().coerceIn(1, cover.width - srcX)
    val srcY = ((frameTop - fit.offsetY) / fit.scaleY).roundToInt().coerceIn(0, cover.height - 1)
    val srcHeight = (frameRows / fit.scaleY).roundToInt().coerceIn(1, cover.height - srcY)
    // 多画 1px：镜像首行与带子末行是同一份内容，重叠不会露缝，取整却可能留缝。
    drawImage(
        image = cover,
        srcOffset = IntOffset(srcX, srcY),
        srcSize = IntSize(srcWidth, srcHeight),
        dstOffset = IntOffset(0, floor(dstTop).toInt()),
        dstSize = IntSize(size.width.roundToInt(), ceil(dstHeight).toInt() + 1),
        filterQuality = FilterQuality.Low,
    )
}

/** 倒影区的压暗渐变：镜面线处透明，到倒影 [ScrimFullAt] 处到 [ScrimMax]，一直保持到页底。 */
private val ScrimBrush: Brush = Brush.verticalGradient(
    0f to Color.Transparent,
    ScrimFullAt to Color.Black.copy(alpha = ScrimMax),
    1f to Color.Black.copy(alpha = ScrimMax),
)

private fun FloatArray.toBlackBrush(): Brush {
    val stops = Array(size) { index ->
        (index.toFloat() / (size - 1).toFloat()) to Color.Black.copy(alpha = this[index])
    }
    return Brush.verticalGradient(*stops)
}

/**
 * 纵向包络：按 [Brush] 的 alpha 把内容 DstIn 掉。
 *
 * **调用方必须把它写在 `blur` 的外面**（`.rampMask(...).blur(...)`）：写在里面的话
 * 包络自己会被模糊，`Rectangle` 的边缘掉光也一并落进来。
 */
private fun Modifier.rampMask(brush: Brush): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(brush = brush, blendMode = BlendMode.DstIn)
        }
