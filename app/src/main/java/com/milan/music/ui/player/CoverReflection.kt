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
 * 封面的模糊阶梯 —— 从镜面线**往下**才开始的模糊。
 *
 * 上一版（第 45~53 批）是"同一张图的清晰版与糊版按透明度交叉淡入"，那在物理上就是
 * **重影**：中段同时看得见清晰的边和它的糊影，读起来既不像照片也不像倒影，真机截图
 * 里过渡带整段 RMS 掉到 0.36~0.51（清晰封面是 5.9）—— 这就是用户说的"模糊不好看、
 * 不自然"。正确做法是让**半径**连续变化，而不是让两层互相淡入淡出。
 *
 * ## 第 82 批：封面侧不再有过渡带（用户 2026-10-27）
 *
 * 第 54~81 批的阶梯起点在镜面线**以上** 128dp，也就是封面自己的最后 128dp 被逐级
 * 糊掉。设计意图是"接缝两侧由同一图层相邻两行画出"，代价却是**封面正文被涂糊**：
 * 真机截图（873×1920）量到 y 674→960 整段清晰度从 7.06 掉到 1.80，用户原话
 * "模糊的地方好像不对，上面的一个条状模糊应该取消"。
 *
 * 现在的几何：**封面一直清晰到镜面线**，阶梯全部落在镜面线以下 ——
 * 镜面线处半径 0（倒影第一行就是封面的最后一行，所以接缝两侧的清晰度按构造相等），
 * 往下 16dp 糊到 12dp、64dp 到 24dp、160dp 到 34dp。倒影因此读作"紧贴物体的镜面反射，
 * 越往下越化开"，而封面一个字都不糊。
 *
 * ## 第 83 批：清晰层让位从 32dp 收到 16dp
 *
 * 第 82 批把带子归零之后，镜下最内那一级（半径 0 = **未经过任何模糊的真实镜像**）
 * 铺了整整 32dp：实测镜下 8dp 处还有 84% 是真实镜像、16dp 处 50%、24dp 处 16%。
 * 用户看到的"倒影顶端太清楚"就是它 —— 封面底部那条深色斜边被原样倒过来，读起来
 * 像"封面底部又复制了一份"。
 *
 * 让位距离是这里**唯一**能动的旋钮：首级半径必须是 0，镜面线两侧清晰度相等是接缝
 * 不显形的唯一凭据，改成 6dp 就是一道清晰度断崖；层数也不必加（每层一次全屏离屏）。
 * 收到 16dp 之后权重落在 8dp 处恰好 0.50，倒影从"贴着物体"到"化开"只用 16dp。
 *
 * ## 为什么是 4 个 stop（4 个绘制层）
 *
 * 每一级都是一次全屏离屏合成，半径 > 0 的那三层还要各自一次 `Modifier.blur`，
 * 1080×2400 上每层约 5~10MB 缓冲。第 78 批曾收到 3 层（少了 `2dp` 与 `22dp`），
 * 第 82 批因为起点归零必须把**清晰层也画出来**（见 [cascadeAlpha]），所以绘制层数
 * 又回到 4 —— 但没有多出一次模糊，清晰层不挂 RenderEffect。
 *
 * 想再加一层之前，先想清楚这 10MB 换来的曲线差分是不是看得见。
 */
internal val BlurRamp: List<BlurStop> = listOf(
    BlurStop(radius = 0.dp, at = 0.dp),
    BlurStop(radius = 12.dp, at = 16.dp),
    BlurStop(radius = 24.dp, at = 64.dp),
    BlurStop(radius = 34.dp, at = 160.dp),
)

/**
 * 叠层向上**绘制溢出**的高度：**等于最大模糊半径（34dp）**。这不是过渡带。
 *
 * 第 82 批曾把它归零（用户要求取消封面侧那条模糊带），第 84 批恢复成 34dp 但
 * **含义完全变了**：它不再是"阶梯盖住封面的那一段"，而是给四个离屏图层留的出血边。
 * 阶梯的位置（[BlurRamp] 的 `at`）全部相对镜面线，溢出区里 `w₁..w₃ ≡ 0`，
 * 半径 0 的清晰层在那里**什么都不画**（见 [RampLayerContent]）—— 所以封面一个像素
 * 都不会糊，视觉上完全没有变化。
 *
 * 为什么非要出血（2026-10-27 真机）：
 *
 * 归零之后四个图层的顶边正好压在镜面线上。`graphicsLayer(Offscreen)` 与
 * `Modifier.blur(Rectangle)` 在图层边缘会掉 alpha，而那条边一侧是封面、一侧是倒影，
 * 缺口就成了一条横贯全宽的黑线 —— 873×1920 截图实测 y 957~962 亮度 1~4、两侧 90~105，
 * 中心正落在镜面线（对称扫描 y=959）。同样是这张图，第 54~81 批的旧版截图在同一位置
 * 是平滑过渡到背景，**没有这条线**，所以它就是"边缘落在镜面线"带来的。
 *
 * 之前溢出 128dp 时缺口落在封面上方，那里清晰层画的是封面自己的行（逐像素同源），
 * 缺口露出来也是同样的像素，所以隐形。现在出血区留空，缺口露的是封面本体 —— 同样
 * 隐形，而且不需要在封面上重叠任何东西。
 *
 * **出血量必须 ≥ 最大模糊半径**：否则 34dp 那一层的模糊核仍然跨过镜面线，
 * 边缘衰减会落在可见区里。`ReflectionLayersTest` 守这条等式。
 */
internal val CoverRamp: Dp = BlurRamp.maxOf { it.radius }

/**
 * 镜面线所在的那一级：`index = 0`，半径 0（第 82 批）。
 *
 * 它是**阶梯里唯一的满权重层**，也是倒影的第一行。以前这一级是一层 12dp 的糊版，
 * 现在它是清晰镜像 —— 因为糊度已经从镜面线处归零，往上再没有"过渡带"可分。
 */
internal val SeamStopIndex: Int = BlurRamp.indexOfFirst { it.at == 0.dp }

/**
 * 叠层要画哪几级：**含清晰层**。
 *
 * 第 82 批的硬约束。清晰层的权重 `w_0` 在镜面线处是 1，不能像以前那样交给"封面本体"
 * 承担 —— 封面在镜面线就结束了，倒影区里没人补这一份。缺了它，叠加 alpha 会掉到
 * `1 − Π(1−w_i)`（级联 alpha 有恒等式 `Π(1−a_i) = w_0`）：镜下 8dp 处只剩 0.5、
 * 16dp 处直接归零，色场透出一条背景色带（第 44 批那条老问题的翻版）。
 * 门禁 `ReflectionLayersTest` 守这一条。
 */
internal val DrawnLayers: IntRange = 0..BlurRamp.lastIndex

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
 * 倒影是封面的镜像，内容必然重复，重复多少由**可见长度**决定。第 57 批曾把 150dp
 * 收到 104dp，以压掉"第二张封面"；2026-10-24 按用户指定放宽到 128dp，让镜下多出
 * 24dp 的封面末段（那时镜上还有一条 128dp 的过渡带，两者等长）。
 *
 * 第 82 批过渡带归零之后，镜上不再有对称的那一半，128dp 全部落在镜面线以下 ——
 * 起点是清晰的镜像，往下 16dp 内就糊开（见 [BlurRamp]，第 83 批从 32dp 收短），
 * 所以"第二张封面"的可辨识度比第 57 批更低，不必再为此收短。
 *
 * 代价要明确：128dp 比 104dp 多翻下封面最后 24dp，构图里若有 logo/脸落在这一带，
 * 会比 104dp 更容易被认出来。真机若重新出现"封面两次"，旋钮仍是往回收
 * （128 → 104 → 72dp），不要改模糊阶梯来掩盖内容重复。
 *
 * 配上 [coverRowAt] 的逐行映射，这个长度是**可算的**：可见段 = 封面最后 128dp。
 */
internal val FadeEnd: Dp = 128.dp

/**
 * 纵向包络的采样点数：**按节点高度定，不是常数**。
 *
 * 包络是一条归一化曲线（级联 alpha），写不进几个拐点 —— 只能采样成多段线性渐变。
 * 采样太稀会读出带状：阶梯里最窄的一段（0→16dp 那一级）只跨 16dp，采样步长一旦超过
 * 它的一半，线性化误差就会顶出 2%（≈5/255）的台阶。所以步长固定 [MaskStepDp]、上下封口。
 *
 * [MaskStepDp] 是 2dp 而不是 4dp：第 83 批把最窄一段从 32dp 收到 16dp 之后，
 * 同样的步长误差按平方翻倍 —— 4dp 下 13dp 处的复合不透明度只剩 0.9755（门限 0.98），
 * `ReflectionLayersTest` 当场红。2dp 下最窄段有 8 段，误差回到 0.6% 量级。
 * 代价只是每层渐变多一倍 stop（`remember` 缓存，只在高度变化时重建），不增加 GPU 层数。
 */
internal fun maskSamplesFor(nodeHeight: Dp): Int =
    (nodeHeight.value / MaskStepDp).toInt().coerceIn(MaskMinSamples, MaskMaxSamples)

private const val MaskStepDp = 2f
private const val MaskMinSamples = 64
private const val MaskMaxSamples = 256

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
 * 第 [index] 级在位置 [y] 的**权重**（不是 alpha，alpha 见 [cascadeAlpha]）。
 *
 * 这是**单位分解**：任意 [y] 上 `Σ index 0..last` 恒等于 1。这一条是整个方案的地基 ——
 * 只有它成立，倒影才是"半径连续变化的同一张图"，而不是两层互相淡入淡出的重影；
 * 也只有它成立，级联 alpha 的分母最终归到 1，合成结果才是精确的加权平均。
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
 * 第 [index] 层的**叠加 alpha**（不是权重）。
 *
 * 为什么不能直接拿权重当 alpha：Compose 是把一层层按 `over` 叠起来的，
 * `n` 层各自 alpha = `w_i` 叠出来的结果既不是 `Σ w_i·L_i`，覆盖率也只有
 * `1 − Π(1−w_i)`。以前这不是问题 —— 过渡带压在**不透明的封面**上，缺的那点 alpha
 * 由封面自己补上。第 82 批把过渡带归零之后，倒影区下面什么都没有，缺多少就透多少
 * 背景色。
 *
 * 正确做法是**归一化级联**：先画清晰层（alpha 1，整块倒影的不透明底），再逐层往上
 * 叠，第 `i` 层的 alpha 取
 *
 * ```
 * a_i = w_i / (w_0 + w_1 + … + w_i)
 * ```
 *
 * 这样叠到第 `i` 层为止的合成结果恒等于 `Σ_{j≤i} w_j·L_j / Σ_{j≤i} w_j` —— 全部叠完
 * 就是**精确的加权平均** `Σ w_j·L_j`（因为单位分解让分母为 1），且每一行都是完全不
 * 透明的。清晰层权重为 0 的地方它自己让开（`a_0 = 0`，否则整段倒影都会被那层清晰的
 * 镜像盖住）。
 *
 * 分母为 0 表示"这一层还没轮到"：返回 0，交给下面更糊的层去铺。
 */
internal fun cascadeAlpha(index: Int, y: Dp, stops: List<BlurStop> = BlurRamp): Float {
    if (index < 0 || index > stops.lastIndex) return 0f
    val weight = layerWeight(index, y, stops)
    if (index == 0) return if (weight > 0f) 1f else 0f
    var prefix = 0f
    for (j in 0..index) prefix += layerWeight(j, y, stops)
    if (prefix <= 1e-6f) return 0f
    return (weight / prefix).coerceIn(0f, 1f)
}

/**
 * 第 [index] 层纵向包络的采样（每个采样点的 alpha，沿**图层**高度均匀分布）。
 *
 * 采样区间是 `[−bleed, bottom]`，也就是 [CoverReflection] 真正画的那一块：
 * 镜面线以上 [bleed]（= [CoverRamp]，出血，第 84 批）+ 倒影 [bottom]。
 * **不能只按 `bottom` 采**：`toBlackBrush` 把数组首尾映射到图层的上下边，
 * 少算出血那 34dp 就会让整条包络往下错 34dp —— 倒影整体被压扁，底部还空一截。
 * 出血区里清晰层恒 1、模糊层恒 0，正是采样曲线开头那段平线。
 *
 * 这里**不含** [fadeAt]：衰减由整块倒影的父层一次性做（见 `CoverReflection`）。
 * 逐层乘 fade 会把级联 alpha 压到 fade 以下 —— 镜面下方立刻暗一截。
 */
internal fun cascadeAlphas(
    index: Int,
    bottom: Dp,
    stops: List<BlurStop> = BlurRamp,
    samples: Int = 0,
    bleed: Dp = CoverRamp,
): FloatArray {
    val node = bleed + bottom
    val count = (if (samples > 0) samples else maskSamplesFor(node)).coerceAtLeast(2)
    val out = FloatArray(count)
    for (j in 0 until count) {
        val y = Dp(node.value * j / (count - 1).toFloat()) - bleed
        out[j] = cascadeAlpha(index, y, stops)
    }
    return out
}

/**
 * 整块倒影的衰减采样（每个采样点的 alpha，沿**节点**高度均匀分布）。
 *
 * 节点 = 出血 [bleed] + 倒影 [bottom]，与 [cascadeAlphas] 同一个区间 —— 这两条曲线
 * 挂在**同一个** Box 上（`CoverReflection` 里那个 fade 父层），区间不一致就会错位：
 * 2026-10-27 第 85 批实测，只按 `bottom` 采、Box 却只铺 `bottom` 高时，整块倒影被
 * 下推 34dp，镜下留出一条 **34.0dp 的空白带**（std 22，正常倒影 60~80）。
 *
 * 出血区里 `fadeAt(y ≤ hold) = 1`，所以开头那段是平线，不会把出血区裁掉。
 */
internal fun fadeAlphas(
    bottom: Dp,
    hold: Dp = FadeHold,
    samples: Int = 0,
    bleed: Dp = CoverRamp,
): FloatArray {
    val node = bleed + bottom
    val count = (if (samples > 0) samples else maskSamplesFor(node)).coerceAtLeast(2)
    val out = FloatArray(count)
    for (j in 0 until count) {
        val y = Dp(node.value * j / (count - 1).toFloat()) - bleed
        out[j] = fadeAt(y, bottom, hold)
    }
    return out
}

/**
 * 能不能画成"阶梯"：要 RenderEffect（API 31+），也要封面位图（每一层都从它取行号）。
 *
 * 任一条不成立就退回单层倒影 —— 那时镜面处的清晰度会断一截，但至少画得出来。
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
 * 异步解码 + 512² 翻转产出（当时 `reflectionCache` 的预算还小于单张位图，必然 miss；
 * API 31+ 现在根本不生产它，见 `ArtworkRepository.loadReflection`），
 * 切歌后要 10~30ms 才到 —— 这段时间整条倒影先是旧内容、再整体换掉，
 * 用户看到的就是"闪一下"。
 */
internal fun canDrawBand(hasCover: Boolean, hasMirror: Boolean, sdkInt: Int): Boolean =
    (hasCover && sdkInt >= Build.VERSION_CODES.S) || hasMirror

/**
 * 叠层**向 Column 报告**的排版高度：**恒为 0**。
 *
 * 这一条是硬约束，不是优化。叠层的绘制高度是 `CoverRamp + 倒影高`，而它挂在 Column
 * 的镜面线上、下面紧跟 [com.milan.music.ui.player.PlayerTransport]。只要它报告真实
 * 高度，Column 就会把它当占位，把信息区与控制条整体推下去 ——
 * 2026-10-23 第 55 批真机：557dp 直接吃掉整屏，歌名、进度条、播放键全部出界，
 * 屏幕上只剩封面和倒影。
 */
internal val OverlayLayoutHeight: Dp = 0.dp

/**
 * 叠层真正要画的纵向尺寸（从镜面线以上 [CoverRamp] 一直到页底）。
 *
 * 向上出血多少只由 [CoverRamp] 一处定义，**不要在这里手写 34dp** —— 它必须等于
 * 最大模糊半径，跟着 [BlurRamp] 一起变。
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
 * 倒影，一个组件画完。
 *
 * **坐标系原点就是镜面线**：调用点必须把它放在封面框底（`CoverLayout.heroHeight`）上，
 * 组件向下铺满整页。它会向上多画 [CoverRamp]（= 最大模糊半径）**但那里不画内容** ——
 * 纯粹是给图层边缘留的出血，封面从顶到底仍然是自己的本体，一个字都不糊。
 *
 * 画在 Column 里（封面之后、控件之前）是这个组件唯一的工作位置：倒影被歌曲信息与
 * 控制压住 —— 文字永远在最上层，不会像上一版那样被 0.9 alpha 的倒影盖成一片洗白。
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
            // 降级路径不画出血（图层就是倒影本身），所以采样必须显式 `bleed = 0.dp`：
            // 默认值是 [CoverRamp]，照用会让包络整体上错 34dp、底部提前淡完。
            val alphas = remember(height) { fadeAlphas(height, bleed = 0.dp) }
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

        // 倒影本体：先按级联 alpha 叠成**精确的加权平均**（见 [cascadeAlpha]），
        // 再由这一层父级一次性按 [fadeAt] 淡出。
        //
        // fade 必须在父级，不能在每层的 mask 里乘：级联 alpha 的分母是权重之和，
        // 乘上 fade 之后叠加结果会掉到 fade 以下 —— 镜面下方立刻暗一截，而且越往下
        // 越暗。父级 DstIn 只作用一次，等比压整块倒影。
        val fade = remember(height, band) { fadeAlphas(height, bleed = band) }
        val fadeBrush = remember(fade) { fade.toBlackBrush() }
        // fade 父层必须覆盖**整个节点**（出血 + 倒影），且原点与外层一致（镜面线以上 band）。
        //
        // 顶边一旦贴到镜面线（`.offset(y = band).height(height)`），子图层就会被 DstIn
        // 的矩形裁在镜面线以下 —— 而图层的翻折轴在自身 y = band 处，于是整块倒影
        // 被下推 band。2026-10-27 第 85 批真机就是这么在镜下留出一条 **34.0dp 空白带**
        // （实测 std 22，正常倒影 60~80，宽度正好等于 [CoverRamp]）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(nodeHeight)
                .rampMask(fadeBrush),
        ) {
            // 由清晰到糊：**清晰层先画**，它是整块倒影的不透明底（见 [DrawnLayers]）。
            //
            // 循环里调 `remember` **合法但脆**：`remember` 认的是 slot table 里的位置，
            // 也就是"第几次被调用"，不是源码里的变量名。这里成立靠两个前提，改任何一条
            // 都要重新查一遍：
            //  1. [BlurRamp] 是顶层 `val`，长度恒为 4 —— 循环上界不依赖运行时状态，
            //     所以每一趟组合走的迭代序列完全相同，slot 不会错位；
            //  2. `index` 与 `height` 都进了 key —— 高度变化（旋转、分屏）时包络重算，
            //     不会把旧高度下算好的 alpha 拿去用。
            // 反例：把 `BlurRamp` 改成由参数传入、或长度随屏幕变化的 list，第 n 层的
            // mask 会被第 n+1 层复用，阶梯直接串色 —— 而且只在部分设备上出现。
            for (index in DrawnLayers) {
                val stop = BlurRamp[index]
                val alphas = remember(index, height) { cascadeAlphas(index, height) }
                val brush = remember(alphas) { alphas.toBlackBrush() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(nodeHeight)
                        // 包络在**模糊外面**：`Modifier.blur` 若在外，包络的 alpha 会被
                        // 模糊一起稀释，镜面处从 1.0 掉到 0.7，更暗的底层透出来 ——
                        // 2026-10-23 真机量到 y=960 整行 242 列同时暗 11/255（4.3%）。
                        .rampMask(brush)
                        .then(
                            // 清晰层（半径 0）不挂 RenderEffect：`blur(0.dp)` 只会白开
                            // 一层离屏缓冲，边缘还可能被 `Rectangle` 裁掉半个像素。
                            if (stop.radius > 0.dp) {
                                Modifier.blur(
                                    radius = stop.radius,
                                    edgeTreatment = BlurredEdgeTreatment.Rectangle,
                                )
                            } else {
                                Modifier
                            },
                        ),
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
 * 一层阶梯的内容：镜面线以上 [band] 留空，以下画镜像。
 *
 * **用画布按像素画，不用 `Image`。** 上一版把定位交给 `Image` 的 `contentScale` +
 * `BottomCenter`：那条路径的正确性依赖"缩放后的位图高度恰好等于节点高度"，源图一竖
 * （裁切量不为 0）这个前提就破了 —— 倒影会整体上下滑一截，把封面的**中段**搬进过渡带。
 * 2026-10-23 真机就是这么"封面出现两次"的：过渡带里出现倒着的面具、倒着的 SWING 和
 * 倒着的脸，而原图对应那几行只是衬衫和背景。
 *
 * 现在只画镜像：[band] 处取到 [heroHeight]（封面最底一行），往下严格逐行退回。
 * 翻折用 `translate(0, 2·band) · scale(1, -1)` 绕镜面线做，不经过任何 layout 节点 ——
 * 偏移量为 0，没有可以"滑掉"的自由度。
 *
 * 第 82 批把 [band] 归零（封面侧不留模糊），第 84 批把 [band] 变成纯出血 ——
 * 那时上半段本来是"把封面的最后 band 行原样画一遍"，现在**不画了**：
 *
 *  · 出血区必须存在（否则图层边缘落在镜面线上，见 [CoverRamp]）；
 *  · 但它不需要内容 —— 那里露出来的是封面本体，本来就长这样；
 *  · 而"再画一遍"要依赖 `Canvas.drawImage` 与 `Image`+`ContentScale` 两套取景
 *    逐像素重合，半像素偏差就是一层淡重影。留着出血、不填内容，风险为零。
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

        // 镜面线以上那 [band] 是**留空**的出血区（见 [CoverRamp]）：不画任何东西，
        // 只是为了把图层的上边缘挪到封面里看不见的地方。画反而有害 —— 那会在封面上
        // 叠一层"同一张图的第二次绘制"，半像素差异就会读成重影。
        //
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
