package com.milan.music.ui.player

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 模糊阶梯与镜面过渡的规格测试。
 *
 * 这里守的不是"好看"，而是**接缝在结构上不可能出线**的那几条前提。2026-10-23 的
 * 十轮返工每一条都能对应到这里的一个断言，所以每条都写了红态的现场。
 */
class ReflectionLayersTest {

    /** 一个"够高"的倒影，几何上不会和阶梯末级打架。 */
    private val height = 429.dp

    private fun node(height: Dp = this.height): Dp = CoverRamp + height

    @Test
    fun `blur ramp climbs strictly`() {
        // 半径与位置都必须单调。任一处回退都会让某一级被另一级吞掉，
        // 视觉上就是"糊度往回跳一下"。
        val radii = BlurRamp.map { it.radius.value }
        val at = BlurRamp.map { it.at.value }
        assertEquals(radii.sorted(), radii)
        assertEquals(at.sorted(), at)
        assertTrue("第一级必须是清晰（半径 0）：它由封面本体承担", radii.first() == 0f)
        radii.zipWithNext().forEach { (near, far) ->
            assertTrue("radius must grow: $near -> $far", far > near)
        }
    }

    @Test
    fun `the ladder keeps four stops and the mirror line is sharp`() {
        // 每一级都是一次全屏离屏合成，半径 > 0 的那三层还要各自一次 `Modifier.blur`。
        // 第 78 批曾收到 3 层（去掉 `2dp` 与 `22dp`），第 82 批因为**起点归零**必须把
        // 清晰层也画出来（见 [DrawnLayers]），所以绘制层数回到 4 —— 但没有多一次模糊。
        //
        // 这条不写就守不住：加一级不会有任何编译或运行期提示，只有中低端机的掉帧会说
        // 出来 —— 而那时已经找不到是哪次改动加的了。
        assertEquals("阶梯 stop 数 = 绘制层数（清晰层也要画）", 4, BlurRamp.size)
        assertEquals(listOf(0f, 12f, 24f, 34f), BlurRamp.map { it.radius.value })

        // **封面侧不许再有过渡带**（用户 2026-10-27）：真正要守的是**阶梯起点**。
        // 第 84 批起 `CoverRamp` 不再是过渡带，而是出血（= 最大模糊半径），变大不糊封面；
        // 但起点一旦回到负数，"封面底部那条条状模糊"就回来了。
        assertEquals("阶梯起点必须在镜面线", 0.dp, BlurRamp.first().at)
        assertEquals("镜面线处必须清晰", 0.dp, BlurRamp[SeamStopIndex].radius)

        // 等效曲线：0dp 清晰 → 16dp 糊到 12dp → 64dp 到 24dp → 160dp 到 34dp。
        // 前两段是**清晰层的让位距离**（第 83 批从 32dp 收短到 16dp）：它决定倒影顶端
        // 有多少内容还读得出原样，是用户"倒影顶端太清楚"那条反馈的唯一旋钮。
        assertEquals(0f, blurAt(0.dp).value, 0.01f)
        assertEquals(12f, blurAt(16.dp).value, 0.01f)
        assertEquals(24f, blurAt(64.dp).value, 0.01f)
        assertEquals(34f, blurAt(160.dp).value, 0.01f)
        assertEquals(6f, blurAt(8.dp).value, 0.01f)
    }

    @Test
    fun `adjacent steps differ by less than a factor of three`() {
        // 相邻两级差得越远，交接处越像"换了一层" —— 那正是上一版被否掉的重影感。
        val radii = BlurRamp.map { it.radius.value }.filter { it > 0f }
        radii.zipWithNext().forEach { (near, far) ->
            assertTrue("$near -> $far 跳得太狠", far / near < 3f)
        }
    }

    @Test
    fun `blur only ever grows across the mirror line`() {
        // 从封面的过渡带顶端一路量到倒影深处，等效半径必须单调不减：
        // 用户要的是"封面的模糊和倒影的模糊一起慢慢增强"，中间任何回落都是一道坎。
        var last = -1f
        var y = BlurRamp.first().at
        while (y <= BlurRamp.last().at) {
            val r = blurAt(y).value
            assertTrue("blur fell back at $y: $last -> $r", r >= last - 1e-4f)
            last = r
            y += 4.dp
        }
    }

    @Test
    fun `the mirror line owns a step of its own`() {
        // 镜面线必须落在一级的正中间，而不是两级之间：落了才谈得上"这里的内容由
        // 恰好一个图层决定"。第 82 批起它就是阶梯的第 0 级 —— 半径 0，也就是清晰镜像。
        assertEquals("镜面线必须是第 0 级", 0, SeamStopIndex)
        assertEquals(0f, BlurRamp[SeamStopIndex].at.value, 1e-4f)
        assertEquals(BlurRamp[SeamStopIndex].radius, blurAt(0.dp))
        assertEquals("镜面处不许有模糊", 0.dp, blurAt(0.dp))
    }

    @Test
    fun `layer weights are a partition of unity`() {
        // 整个方案的地基。只要它成立，过渡带里就不可能漏出底下的清晰封面（重影），
        // 也不可能透出色场（暗带）—— 两个毛病都曾各自白送好几轮。
        var y = BlurRamp.first().at - 40.dp
        while (y <= height + 40.dp) {
            val sum = BlurRamp.indices.sumOf { layerWeight(it, y).toDouble() }
            assertEquals("Σw at $y", 1.0, sum, 1e-4)
            y += 3.dp
        }
    }

    @Test
    fun `no layer ever has a negative weight`() {
        // 权重为负 = DstIn 的反向操作，画面会出现洞。数值上必须先守住。
        var y = BlurRamp.first().at - 40.dp
        while (y <= height + 40.dp) {
            for (index in BlurRamp.indices) {
                assertTrue("w[$index] at $y", layerWeight(index, y) >= 0f)
            }
            y += 3.dp
        }
    }

    @Test
    fun `the cover is never touched by a blurred layer`() {
        // 第 82 批的核心：封面侧（镜面线以上）只有清晰层，且权重恒为 1。
        // 任何一层模糊层在这里拿到非 0 权重，封面就会被糊掉一块 —— 那就是用户
        // 2026-10-27 说的"上面的一个条状模糊"。
        for (y in listOf(-200.dp, -128.dp, -64.dp, -8.dp, -0.5.dp)) {
            for (index in 1..BlurRamp.lastIndex) {
                assertEquals("w[$index] 越界到封面侧 $y", 0f, layerWeight(index, y), 1e-4f)
            }
            assertEquals("封面侧 $y 应全由清晰层承担", 1f, layerWeight(0, y), 1e-4f)
        }
    }

    @Test
    fun `the ladder starts exactly at the mirror line`() {
        // 阶梯起点 = 镜面线 = 0dp。大于 0 就是"封面底部又被糊了一段"，
        // 小于 0 就是过渡带压回封面上 —— 两个方向都红。
        assertEquals(0f, BlurRamp.first().at.value, 1e-4f)
    }

    @Test
    fun `the mirror line is drawn by exactly the sharp layer`() {
        // 镜面线上：清晰层满权重（它就是封面最后一行倒过来的那一行，与封面同内容），
        // 所有模糊层为 0。接缝两侧因此是同一份内容、同一个清晰度 —— 不是调参调没的。
        assertEquals(1f, layerWeight(0, 0.dp), 1e-4f)
        for (index in 1..BlurRamp.lastIndex) {
            assertEquals("w[$index] at seam", 0f, layerWeight(index, 0.dp), 1e-4f)
        }
    }

    @Test
    fun `the sharp layer must be drawn by the overlay`() {
        // 第 82 批最容易踩空的一条：清晰层的权重以前由"不透明的封面本体"补上，
        // 现在封面在镜面线就结束了 —— 叠层若不画它，镜面下方的覆盖率只剩
        // `1 − Π(1−w_i)`：16dp 处 0.75、32dp 处直接归零，色场透出一条背景色带。
        assertEquals("叠层必须含清晰层", 0, DrawnLayers.first())
        assertEquals(BlurRamp.lastIndex, DrawnLayers.last)
        assertTrue("每一级都要画", DrawnLayers.count() == BlurRamp.size)

        // 缺了清晰层会掉多少：把两种画法的覆盖率算出来摆在这里。
        // （级联 alpha 有个漂亮的性质：`Π(1−a_i) = w_0`，所以不画清晰层时覆盖率
        // 恰好等于 `1 − w_0` —— 镜面线上直接是 0，也就是一条完全透明的缝。）
        fun coverage(range: IntRange, y: Dp): Float {
            var remaining = 1f
            for (index in range) remaining *= (1f - cascadeAlpha(index, y))
            return 1f - remaining
        }
        assertEquals("镜面线上只剩糊层 = 全透明", 0f, coverage(1..3, 0.dp), 1e-3f)
        assertEquals("镜下 8dp 只补得上一半", 0.5f, coverage(1..3, 8.dp), 0.01f)
        assertEquals("16dp 才追平", 1f, coverage(1..3, 16.dp), 0.01f)
        assertEquals("画了清晰层就满", 1f, coverage(DrawnLayers, 16.dp), 1e-4f)
    }

    @Test
    fun `the sharp layer hands the top of the reflection over quickly`() {
        // 第 83 批（用户 2026-10-27："倒影顶端太清楚"）。清晰层半径是 0，所以它覆盖的
        // 那一段倒影是**未经过任何模糊的真实镜像**：让位距离 32dp 时，镜下 8dp 处
        // 84%、16dp 处 50%、24dp 处 16% 都是真的 —— 封面底部那条深色斜边被原样倒过来
        // 摆在那儿，读起来就是"封面底部又复制了一份"。
        //
        // 这条守的是**上限**：让位距离长回去就红。绝不能靠"把首级半径提上去"来解决，
        // 那会让镜面线两侧清晰度不等，出现一道清晰度断崖（第 54 批九轮返工的起点）。
        val handover = BlurRamp[1].at
        assertTrue("清晰层让位超过 20dp：${handover.value}", handover <= 20.dp)
        assertTrue("清晰层太短，镜下没有真实镜像：${handover.value}", handover >= 8.dp)
        assertEquals("镜面线处必须是真实镜像", 0.dp, BlurRamp[SeamStopIndex].radius)

        assertEquals("镜下 8dp 还有一半是真实镜像", 0.5f, layerWeight(0, 8.dp), 0.02f)
        assertEquals("镜下 16dp 已全部交给模糊层", 0f, layerWeight(0, 16.dp), 1e-4f)
    }

    @Test
    fun `fade is full through the mirror line and gone within FadeEnd`() {
        // 镜面线处衰减必须是 1：只要 < 1，倒影就是半透明，而上方封面不透明 → 亮度台阶。
        assertEquals(1f, fadeAt(0.dp, height), 1e-4f)
        assertEquals(1f, fadeAt(-CoverRamp, height), 1e-4f)
        assertEquals(0f, fadeAt(FadeEnd, height), 1e-4f)
        assertEquals(0f, fadeAt(height, height), 1e-4f)

        var last = 1.01f
        var y = 0.dp
        while (y <= height) {
            val f = fadeAt(y, height)
            assertTrue("fade must not rise at $y: $last -> $f", f <= last + 1e-4f)
            last = f
            y += 4.dp
        }
    }

    @Test
    fun `the reflection is short enough to not repeat the cover`() {
        // 倒影是封面的镜像，内容必然重复，重复多少由**可见长度**决定。第 57 批从
        // 150dp 收到 104dp 是为了压掉"第二张封面"；2026-10-24 按用户指定放宽到
        // 128dp，让镜下多出 24dp 封面末段，同时与 128dp 过渡带等长。
        //
        // 这条不再断言"四分之一"，只守两条底线：字面值必须是 128dp；仍然远短于
        // 整幅倒影，不能把封面中段翻下来。
        assertEquals("倒影可见长度", 128.dp, FadeEnd)
        assertTrue("必须远短于整幅倒影（$height）", FadeEnd * 2 < height)
        // 贴近镜面线那一段必须是满的（"和封面相近的倒影清楚一点"）。
        assertEquals("FadeHold 处仍要满", 1f, fadeAt(FadeHold, height), 1e-4f)
        assertEquals("镜下 24dp 仍要满", 1f, fadeAt(24.dp, height), 1e-4f)
        // 镜下 128dp 已经是色场：再往下的每一行都不该有倒影。
        for (y in listOf(FadeEnd, FadeEnd + 40.dp, FadeEnd + 120.dp, height)) {
            assertEquals("$y 处不该还有倒影", 0f, fadeAt(y, height), 1e-4f)
        }
    }

    @Test
    fun `the stacked layers reproduce the exact weighted average`() {
        // 把 Compose 的合成过程照原样演一遍：按 [DrawnLayers] 的顺序（清晰 → 糊）
        // 一层层"盖上去"，每层 alpha 取 [cascadeAlpha]。两条结论必须同时成立：
        //
        //  - 颜色等于**精确的加权平均** `Σ w_i·L_i`（不是近似，也不是重影）；
        //  - 合成完的 alpha 恒为 1 —— 倒影下方不许透出色场。
        //
        // 第 82 批之前这两条都靠"封面不透明"顶着：过渡带压在封面上，缺的 alpha 由
        // 封面补。封面侧归零之后没人补，只能靠归一化级联自己站住。
        val layers = listOf(0.2f, 0.55f, 0.8f, 1f) // 四个任意"像素值"
        for (y in listOf(0.dp, 4.dp, 16.dp, 24.dp, 32.dp, 56.dp, 100.dp, 160.dp, 260.dp)) {
            var color = 0f
            var alpha = 0f
            for (index in DrawnLayers) {
                val a = cascadeAlpha(index, y)
                color = a * layers[index] + (1f - a) * color
                alpha = a + (1f - a) * alpha
            }
            val expected = BlurRamp.indices
                .sumOf { (layerWeight(it, y) * layers[it]).toDouble() }
                .toFloat()
            assertEquals("$y 处不是加权平均", expected, color, 1e-4f)
            assertEquals("$y 处透底了", 1f, alpha, 1e-4f)
        }
    }

    @Test
    fun `mask samples start at the top of the node and end at the page bottom`() {
        for (index in DrawnLayers) {
            val alphas = cascadeAlphas(index, height)
            assertEquals("采样段数必须按节点高度定", maskSamplesFor(node()), alphas.size)
            if (index == 0) {
                // 清晰层：镜面线上满 α（它是倒影的第一行），第一个采样点不可能是 0。
                assertEquals("w[0] at node top", 1f, alphas.first(), 1e-4f)
            } else {
                assertEquals("w[$index] at node top", 0f, alphas.first(), 1e-4f)
            }
        }
        // 衰减不在这几层里 —— 它在父层，必须自己收干净。
        val fade = fadeAlphas(height)
        assertEquals("衰减在镜面线上必须满", 1f, fade.first(), 1e-4f)
        assertEquals("衰减在页底必须收干净", 0f, fade.last(), 1e-4f)
    }

    @Test
    fun `the fade and the cascade share one sample range`() {
        // 两条曲线画在**同一个** Box 上（fade 父层 + 各层自己的 mask），采样区间必须逐点
        // 对齐，区间长度也必须等于节点高度。分头采样的后果 2026-10-27 第 85 批见过真机：
        // 顶层 Box 只铺 `bottom` 高、包络却按 `bleed + bottom` 采，再加上图层的翻折轴在
        // 自身 y = bleed 处，整块倒影被下推 34dp —— 镜下留出一条 **34.0dp 的空白带**
        // （实测 std 22，正常倒影 60~80，宽度正好等于 `CoverRamp`）。
        val fade = fadeAlphas(height)
        assertEquals("衰减段数必须按节点高度定", maskSamplesFor(node()), fade.size)
        for (index in DrawnLayers) {
            assertEquals(
                "layer $index 与 fade 的采样段数必须相同",
                fade.size,
                cascadeAlphas(index, height).size,
            )
        }
        // 出血区里衰减必须是满的：DstIn 一旦把出血裁掉，图层就被裁短、倒影同样下移。
        assertEquals("出血顶端衰减必须满", 1f, fade.first(), 1e-4f)
        // 镜面线在节点内的位置是 `CoverRamp`（不是 0）：清晰层在那里必须满 α，
        // 否则倒影顶端会先空一段再开始 —— 与"图层被下推"是同一种可见结果。
        assertEquals(
            "镜面线上的清晰层必须满 α",
            1f,
            sampleAt(cascadeAlphas(0, height), node(), CoverRamp),
            1e-4f,
        )
    }

    @Test
    fun `sampling the envelope keeps the composite within two percent`() {
        // 包络（级联 alpha）只能采样成多段线性渐变，采样步长 4dp。阶梯里最窄的一段
        // （0 → 32dp）跨 32dp，步长超过它的一半就会顶出 2%（≈5/255）的台阶。
        //
        // 判的是**合成结果**，不是单层的 alpha：某一层的 alpha 会在它的权重刚归零
        // 的那一刻从 1 跳到 0（它已经交棒给下一层），但那时它的权重也是 0，
        // 采样误差乘上去只剩几百分之一 —— 单层上会红，画面上看不见。
        val total = node()
        val layers = listOf(0f, 0.35f, 0.7f, 1f)
        for (yInt in 0..height.value.toInt()) {
            val y = yInt.dp
            var sampledColor = 0f
            var sampledAlpha = 0f
            for (index in DrawnLayers) {
                val a = sampleAt(cascadeAlphas(index, height), total, y + CoverRamp)
                sampledColor = a * layers[index] + (1f - a) * sampledColor
                sampledAlpha = a + (1f - a) * sampledAlpha
            }
            val exact = BlurRamp.indices
                .sumOf { (layerWeight(it, y) * layers[it]).toDouble() }
                .toFloat()
            assertEquals("$y 处采样后的颜色", exact, sampledColor, 0.02f)
            assertEquals("$y 处采样后的不透明度", 1f, sampledAlpha, 0.02f)
        }
    }

    @Test
    fun `the reflection is opaque on every row below the mirror line`() {
        // 第 82 批的底线：镜头下方每一行的覆盖率都必须是 1。少一分就透一分色场，
        // 而那个位置的色场是 palette.bottom 与渐变 —— 透出来就是一条横向色带。
        for (y in 0..height.value.toInt() step 1) {
            var remaining = 1f
            for (index in DrawnLayers) remaining *= (1f - cascadeAlpha(index, y.dp))
            assertEquals("$y dp 处透底", 0f, remaining, 1e-4f)
        }
    }

    @Test
    fun `ramp degrades without a cover bitmap`() {
        // 拿不到封面就画不出"封面那一半"，只能退回单层倒影：掉一点 alpha，
        // 但至少画得出来 —— 静默画一片空白不是降级。
        assertEquals(false, canRamp(null, 34))
        assertEquals(false, canRamp(null, 30))
    }

    @Test
    fun `the bleed covers exactly the largest blur radius`() {
        // 第 84 批（用户 2026-10-27「中间接缝为什么会黑色」）。
        //
        // 叠层是 4 个离屏图层（`graphicsLayer(Offscreen)` + 三层 `Modifier.blur`），
        // 它们的顶边如果正好压在镜面线上，边缘会掉 alpha —— 一侧是封面、一侧是倒影，
        // 缺口就成了一条横贯全宽的黑线。873×1920 真机实测：y 957~962 亮度 1~4，
        // 两侧 90~105，中心正落在镜面线（镜像对称扫描 y=959）；而第 54~81 批的旧版
        // 截图在同一位置是平滑过渡，**没有这条线** —— 因为那时顶边在封面上方 128dp。
        //
        // 出血量必须 ≥ **最大**模糊半径：否则最糊那一层的模糊核仍然跨过镜面线，
        // 边缘衰减落在可见区里。这条等式就是"够不够"的唯一判据。
        val maxRadius = BlurRamp.maxOf { it.radius }
        assertEquals("出血必须正好是最大模糊半径", maxRadius.value, CoverRamp.value, 1e-4f)
        assertTrue("出血不能缩到最大半径以下", CoverRamp >= maxRadius)

        // 出血区里不许有任何模糊层：那里留空，露出来的就是封面本体。
        var y = -CoverRamp
        while (y < 0.dp) {
            for (index in 1..BlurRamp.lastIndex) {
                assertEquals("w[$index] at $y 必须为 0", 0f, layerWeight(index, y), 1e-4f)
            }
            assertEquals("清晰层横跨整段出血", 1f, layerWeight(0, y), 1e-4f)
            y += 2.dp
        }
    }

    @Test
    fun `reflection overlay must not claim layout height`() {
        // 叠层画 557dp（CoverRamp + 倒影高），但**必须向 Column 报告 0 高度**。
        // 2026-10-23 第 55 批真机：照实报告后 Column 把它当占位，PlayerTransport 从
        // 986dp 开始排，整屏只剩封面，歌名 / 进度条 / 播放键全部出界。
        assertEquals("叠层报告的排版高度", 0.dp, OverlayLayoutHeight)
        assertTrue("但绘制高度必须远大于 0", overlayDrawHeight(height) > 0.dp)
        assertEquals("绘制高度 = 出血 + 倒影", CoverRamp + height, overlayDrawHeight(height))
        // 退化路径（没有 RenderEffect）不向上溢出，几何上就是倒影本身。
        assertEquals(height, overlayDrawHeight(height, ramp = false))
    }

    @Test
    fun `overlay draws what it refuses to occupy`() {
        // 报告 0 ≠ 不画：Compose 允许画到边界外。这条把两个量钉在一起，
        // 防止有人"为了让倒影显示出来"把叠层改回 height(nodeHeight)。
        val drawn = overlayDrawHeight(height)
        assertTrue("画出来的比占的多，多出来的部分全靠越界绘制", drawn > OverlayLayoutHeight)
        assertEquals(
            "差值只能是出血本身",
            CoverRamp,
            drawn - OverlayLayoutHeight - height,
        )
    }

    @Test
    fun `the player page still fits after the overlay`() {
        // 页面竖向只有三块：封面框 + 叠层 + 控制区。叠层只要不是 0 高度，
        // 控制区就整体下沉 —— 2026-10-23 第 55 批真机：557dp 的叠层把歌名、
        // 进度条、播放键全部推出屏幕，整屏只剩封面和倒影。
        // 这条把"能不能装下"变成一个可计算的等式，不再靠装机看。
        val minTransport = 300.dp
        for (screen in listOf(640.dp, 844.dp, 926.dp)) {
            val hero = CoverLayout.heroHeight(screen, minTransport)
            val used = hero + OverlayLayoutHeight + minTransport
            assertTrue(
                "屏高 $screen：封面 $hero + 叠层 $OverlayLayoutHeight + 控制区 $minTransport " +
                    "= $used，溢出 ${used - screen}",
                used <= screen,
            )
        }
    }

    @Test
    fun `mirror bitmap aligns to the bottom`() {
        // 倒影画的是上下翻转过的图：封面 TopCenter 裁掉源图底部，翻转后那一截落在
        // 位图顶部，必须对齐底部才贴得住镜面线。对齐错成 Center 时竖版封面在接缝处
        // 跳内容 —— 2026-10-23《餓狼傳說》真机：白边在倒影里消失，亮度断层 0.19。
        //
        // 只对**降级路径**（API < 31，没有 RenderEffect）成立：走向上的阶梯时，
        // 倒影由 [coverRowAt] 按像素取行，不再经过任何 `alignment`。
        assertEquals(CoverLayout.HeroMirrorAlignment, Alignment.BottomCenter)
        assertEquals(CoverLayout.HeroContentAlignment, Alignment.TopCenter)
    }

    // ---- 倒影的逐行映射：这是"封面出现两次"的唯一防线 ----

    /** 873×1920 真机的实际尺寸（px）。 */
    private val heroPx = 960f
    private val bandPx = 286f

    @Test
    fun `the seam row maps to the cover's last row`() {
        // 镜面线那一行，倒影侧取的必须正好是封面最底一行 —— 否则接缝两侧不是同一份
        // 内容，亮度/清晰度都会跳。
        assertEquals(heroPx, coverRowAt(bandPx, bandPx, heroPx), 0.001f)
    }

    @Test
    fun `the band above the seam shows the cover in order`() {
        // 带子（镜面线以上 band 那么高）里是封面的最后一段，正序：带顶取到封面倒数第
        // band 行。
        assertEquals(heroPx - bandPx, coverRowAt(0f, bandPx, heroPx), 0.001f)
        assertEquals(heroPx - bandPx + 100f, coverRowAt(100f, bandPx, heroPx), 0.001f)
    }

    @Test
    fun `below the seam the cover walks back exactly one row per row`() {
        // 镜像的定义：往下走一行，封面往上退一行，**步长恒为 1**。
        //
        // 2026-10-23 第 56 批真机就是这条不成立：倒影整体比封面矮一截（定位交给
        // `Image` 的 contentScale + BottomCenter，缩放后高度与节点高度不等），于是
        // 镜面线下方出现的不是封面底部，而是封面的**中段** —— 过渡带里凭空多出一张
        // 倒着的脸、一段倒着的 SWING。肉眼读作"封面出现了两次"，几何上就是这里跳了
        // 几百行。
        var previous = coverRowAt(bandPx, bandPx, heroPx)
        for (k in 1..420) {
            val row = coverRowAt(bandPx + k, bandPx, heroPx)
            assertEquals(
                "距离缝 $k px 处跳了 ${previous - row} 行（应为 1）",
                1f,
                previous - row,
                0.001f,
            )
            previous = row
        }
    }

    @Test
    fun `the mirror bottoms out at the cover's top, never above it`() {
        // 可见段再长也不许越过封面顶部去取行号：越过之后取到的是"镜像的镜像"，
        // 封面的脸会第二次正着出现。
        assertEquals(0f, coverRowAt(bandPx + heroPx, bandPx, heroPx), 0.001f)
        assertTrue(coverRowAt(bandPx + heroPx * 2, bandPx, heroPx) < 0f)
    }

    @Test
    fun `fit centres the horizontal crop and never shifts vertically`() {
        // 方图铺满 873×960：纵向多给 5%，所以缩放后宽 960/1.05 = 914px，左右各裁
        // (914−873)/2 = 20.6px（占 2.3%，与 `HeroContentScale` 文档的实测口径一致）。
        // 纵向偏移必须恒为 0 —— 一旦不为 0，镜面线两侧会整体错开，那正是上一版的位置。
        val drawn = 960f / 1.05f
        val fit = coverFit(
            scaleX = drawn / 512f,
            scaleY = 960f / 512f,
            srcWidth = 512,
            srcHeight = 512,
            dstWidth = 873f,
        )
        assertEquals(0f, fit.offsetY, 0.001f)
        assertEquals((873f - drawn) / 2f, fit.offsetX, 0.5f)
        assertEquals("左右各裁 2.3%", 2.3f, -fit.offsetX / drawn * 100f, 0.15f)
    }

    @Test
    fun `the visible reflection stays within a third of the hero`() {
        // 设计不变量（844dp 参考屏）：可见倒影不超过封面高度的三分之一。超过这个
        // 比例，翻下去的就不只是封面末段，会重新读出"第二张封面"。
        val hero = CoverLayout.heroHeight(844.dp, 300.dp)
        val share = FadeEnd.value / hero.value
        assertTrue("可见倒影占封面 $share（应 ≤ 1/3）", share <= 1f / 3f)
    }

    @Test
    fun `the reflection rides the cover and not the page pitch`() {
        // 2026-10-23 第 58 批真机："滑动封面时能清楚看到一个叠加层，画的是封面下半部分"。
        // 那一层就是钉在屏幕上的倒影：封面被手指拖走了，它没走。
        // 倒影与当前页（slot = 0）必须**逐像素同步**，且这个同步不该随屏宽变化 ——
        // slot 0 的行程里没有 pitch 这一项，倒影也不许自己加一个。
        for (pitch in listOf(873f, 1080f, 1440f)) {
            for (drag in listOf(0f, -1f, -80f, -420f, 420f, 870f)) {
                assertEquals(
                    "pitch $pitch / drag $drag：倒影位移必须等于当前页位移",
                    pageTranslationPx(slot = 0, pitchPx = pitch, offsetPx = drag),
                    drag,
                    0.001f,
                )
            }
        }
    }

    @Test
    fun `one pitch carries the row exactly one page`() {
        // 滑动一整页的判断依据：隔壁页在 offset = ∓pitch 时正好落在正中。
        // 倒影用的是同一个公式，所以这条同时保证"倒影和隔壁页不会各算各的"。
        val pitch = 873f + 12f * 2.2385f
        assertEquals(pitch, pageTranslationPx(slot = 1, pitchPx = pitch, offsetPx = 0f), 0.001f)
        assertEquals(0f, pageTranslationPx(slot = 1, pitchPx = pitch, offsetPx = -pitch), 0.001f)
        assertEquals(0f, pageTranslationPx(slot = -1, pitchPx = pitch, offsetPx = pitch), 0.001f)
    }

    @Test
    fun `only the neighbour bottoms fade, never the current page`() {
        // 当前页底下压着倒影叠层：它若把自己的底边淡掉，带子里（逐层 over，覆盖率
        // `1 − Π(1−w_i) < 1`）就会透出一条背景色带 —— 第 44 批的老问题。
        // 隔壁页没有叠层兜底，必须自己淡，否则镜面线上露出它的一条硬边。
        assertTrue(pageFadesBottom(slot = 1, neighbourFades = true))
        assertTrue(pageFadesBottom(slot = -1, neighbourFades = true))
        assertFalse(pageFadesBottom(slot = 0, neighbourFades = true))
        // 没有倒影时整页交给共用的 heroFadeMask，隔壁页不许再叠一层。
        for (slot in -1..1) {
            assertFalse(pageFadesBottom(slot = slot, neighbourFades = false))
        }
    }

    @Test
    fun `the outgoing reflection clears the window before the next cover lands`() {
        // 一页行程 = 页宽 + 页缝（`CoverLayout.pitch`）。offset 走到 ∓pitch 时，退场的
        // 那一页连同它的倒影必须**整体**离开窗口 —— 倒影若比封面少走一截，换页完成的
        // 时刻它就还挂在屏幕边上，又是一层"没跟上的叠加层"。
        // 390dp 屏宽、873px 宽（真机 2.2385 px/dp），页缝 12dp → pitch 402dp = 900px。
        val width = 873f
        val pitch = CoverLayout.pitch(pageWidth = 390.dp)
        assertEquals(402f, pitch.value, 0.001f)
        val pitchPx = 873f + 12f * 2.2385f
        assertEquals(900f, pitchPx, 0.5f)
        val gone = pageTranslationPx(slot = 0, pitchPx = pitchPx, offsetPx = -pitchPx)
        assertEquals(-pitchPx, gone, 0.001f)
        assertTrue("退场位移 $gone 必须超过页宽 $width", abs(gone) > width)
    }

    @Test
    fun `the ramp path does not need the flipped bitmap`() {
        // 2026-10-23 第 59 批：切歌"闪一下"的根因就在这一行。
        // 阶梯路径从 `cover` 取行号、在画布上翻折，那张翻转位图它根本不画 ——
        // 却拿它当"有没有倒影"的门槛。于是切歌后 10~30ms 的异步解码窗口里，
        // 整条带子（含压住封面末段 128dp 的过渡带）要么是旧内容、要么整块消失。
        assertTrue(
            "API 31+：有封面就该画，翻转位图没到也不能拦",
            canDrawBand(hasCover = true, hasMirror = false, sdkInt = 31),
        )
        assertTrue(canDrawBand(hasCover = true, hasMirror = true, sdkInt = 31))
        // 封面没解出来时，退回单层翻转图仍然画得出。
        assertTrue(canDrawBand(hasCover = false, hasMirror = true, sdkInt = 31))
        // 两样都没有 —— 那确实画不出，此时靠封面自己淡底边兜底。
        assertFalse(canDrawBand(hasCover = false, hasMirror = false, sdkInt = 31))
    }

    @Test
    fun `below API 31 the flipped bitmap is still required`() {
        // 降级路径没有 RenderEffect，软度只能来自那张小图，它缺席就真的没有倒影。
        assertFalse(canDrawBand(hasCover = true, hasMirror = false, sdkInt = 30))
        assertTrue(canDrawBand(hasCover = true, hasMirror = true, sdkInt = 30))
        assertTrue(canDrawBand(hasCover = false, hasMirror = true, sdkInt = 30))
        assertFalse(canDrawBand(hasCover = false, hasMirror = false, sdkInt = 26))
    }

    /** 按 [alphas] 做分段线性插值，[yInNode] 是节点内坐标。 */
    private fun sampleAt(alphas: FloatArray, total: Dp, yInNode: Dp): Float {
        val t = (yInNode.value / total.value) * (alphas.size - 1)
        val index = t.toInt().coerceIn(0, alphas.size - 2)
        val f = t - index
        return alphas[index] * (1f - f) + alphas[index + 1] * f
    }
}
