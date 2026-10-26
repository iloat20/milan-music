package com.milan.music.ui.theme

import androidx.compose.animation.core.SpringSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 两层制的门禁（第 72 批）。
 *
 * 两层分工写在 [MilanMotion] 的 KDoc 里：位置与尺寸走弹簧，透明度与颜色走时长。
 * 但"写在该写的地方"这件事，靠人记住是守不住的 —— 一个 `tween(220)` 手滑写进某个
 * 组件里，编译通过、视觉也说得过去，只有把整套动效当成一个系统看时才露馅。
 *
 * 所以这里守三条：
 *
 *  1. **Effect 时长单调递增**，且都落在 100–700ms 的可感知区间。抄错一位（2400）不会崩，
 *     只会让某一次切换变得莫名其妙地慢。
 *  2. **Spatial 档位的阻尼与刚度在可用区间内**。阻尼为 0 是永不停止的振荡，大于 1 是
 *     拖延；刚度太小则几十毫秒内看不出运动。
 *  3. **除 [MilanMotion] 之外不允许出现动画参数字面量**。这条是真正的价值所在：
 *     只要参数只能在 token 里出现，"两层制"就不会被下一次改动悄悄绕开。
 */
class MilanMotionTest {

    @Test
    fun `effect durations step up from fast to ambient`() {
        val ladder = listOf(
            MilanMotion.FastMs,
            MilanMotion.MediumMs,
            MilanMotion.SlowMs,
            MilanMotion.AmbientMs,
        )
        assertEquals("四档必须严格递增：$ladder", ladder.sorted(), ladder)
        assertEquals("四档不许重复：$ladder", ladder.distinct().size, ladder.size)

        val all = ladder + listOf(MilanMotion.ProgressMs, MilanMotion.BarsBaseMs)
        all.forEach { ms ->
            assertTrue("${ms}ms 不在可感知区间 100–700ms 内", ms in 100..700)
        }
        // 进度条的形变要比"内容交接"快，否则松手后条还在往目标滑。
        assertTrue(
            "ProgressMs=${MilanMotion.ProgressMs} 必须快于 MediumMs=${MilanMotion.MediumMs}",
            MilanMotion.ProgressMs < MilanMotion.MediumMs,
        )
    }

    @Test
    fun `every spatial token is a usable spring`() {
        spatialTokens().forEach { (name, spec) -> assertUsable(name, spec) }
    }

    /**
     * 回位必须临界阻尼。
     *
     * 这一条不是风格问题：封面行回位时冲过 0 会露出相邻页，列表项冲过落点会和相邻行
     * 重叠一帧。想把 [MilanMotion.Settle] 调出弹性之前，先确认上面两个现象可以被接受。
     */
    @Test
    fun `settle never overshoots`() {
        assertEquals(1f, MilanMotion.Settle.dampingRatio, 0f)
    }

    /**
     * 图标交接可以弹，但只有图标可以。
     *
     * 阻尼低于 0.4 的弹簧会在小行程上抖两下；用在文字或面板上就是廉价感。
     */
    @Test
    fun `icon and pop overshoot but stay outside the wobble range`() {
        listOf(
            "Icon" to MilanMotion.Icon,
            "Pop" to MilanMotion.Pop,
        ).forEach { (name, spec) ->
            assertTrue(
                "$name 的阻尼 ${spec.dampingRatio} 应落在 0.4f–1f",
                spec.dampingRatio in 0.4f..1f,
            )
            assertTrue("$name 应当过冲（阻尼 < 1）", spec.dampingRatio < 1f)
        }
        assertTrue(
            "Pop 是比 Icon 更软的一档",
            MilanMotion.Pop.dampingRatio < MilanMotion.Icon.dampingRatio,
        )
    }

    @Test
    fun `no animation spec is written outside MilanMotion`() {
        val files = sourceFiles()
        // 防御"扫描什么都没找到"这种静默通过：目录解析失败时 files 会是空的。
        assertTrue("没扫到源码（cwd=${File("").absolutePath}）", files.size > 20)

        val offenders = buildList {
            files.forEach { file ->
                file.readLines().forEachIndexed { index, raw ->
                    val code = raw.substringBefore("//")
                    val trimmed = code.trimStart()
                    if (trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed
                    if (matchesSpec(code)) {
                        add("${file.name}:${index + 1}  ${raw.trim()}")
                    }
                }
            }
        }

        assertTrue(
            "动画参数只能写在 MilanMotion 里，组件侧请用 token（或 token 的算术）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * 按压形变必须"纵向压、横向几乎不动"，且面积与旧的等比缩放一致。
     *
     * 这一条守的是**形变方向**，不是力度。等比缩小在卡片上读成"卡片退后了"；
     * 只有纵向比横向收得多，才读成"被手指按下去"。同时横向不许超过 1 ——
     * 卡片横向放大 0.6% 会盖住相邻元素的边缘。
     */
    @Test
    fun `press squash compresses vertically and keeps the same area`() {
        val x = MilanMotion.PressSquashScaleX
        val y = MilanMotion.PressSquashScaleY

        assertTrue("横向 ${x} 必须小于 1：放大在卡片上会盖住邻居", x < 1f)
        assertTrue("纵向 $y 必须比横向 ${x} 收得更多，否则读成等比缩小", y < x)
        assertTrue("形变不能小到看不见（纵向 $y 应 ≤ 0.99）", y <= 0.99f)
        assertTrue("形变不能大到像故障（纵向 $y 应 ≥ 0.94）", y >= 0.94f)

        // 面积守恒：与旧等比 0.985² ≈ 0.970 同一档力度，换的只是方向。
        val area = x * y
        assertEquals("面积 ${area} 应与 0.970 一致（±0.005）", 0.970f, area, 0.005f)
    }

    /**
     * 排序面板的圆角收敛必须是"从更圆收到终态"，且差值看得见。
     *
     * 差 4dp 以下在 156dp 宽的面板上分辨不出来 —— 那就不该写进动画，直接固定圆角。
     */
    @Test
    fun `sort menu corner converges by a visible step`() {
        val from = MilanMotion.SortMenuCornerFrom.value
        val to = MilanMotion.SortMenuCorner.value

        assertTrue("初始圆角 $from 必须大于终态 $to", from > to)
        assertTrue("差值 ${from - to}dp 太小，看不出来", from - to >= 4f)
        assertTrue("终态 $to 应落在面板圆角区间 16–24dp", to in 16f..24f)
    }

    private fun assertUsable(name: String, spec: SpringSpec<Float>) {
        assertTrue(
            "$name 的阻尼 ${spec.dampingRatio} 不在 0.4f–1f：0 是永不停止的振荡，1 以上是拖延",
            spec.dampingRatio in 0.4f..1f,
        )
        assertTrue(
            "$name 的刚度 ${spec.stiffness} 不在 50–10000：太小看不出运动，太大等于瞬间到位",
            spec.stiffness in 50f..10_000f,
        )
    }

    private fun spatialTokens(): Map<String, SpringSpec<Float>> = mapOf(
        "Press" to MilanMotion.Press,
        "Swap" to MilanMotion.Swap,
        "Sheet" to MilanMotion.Sheet,
        "Grow" to MilanMotion.Grow,
        "Exit" to MilanMotion.Exit,
        "Settle" to MilanMotion.Settle,
        "Icon" to MilanMotion.Icon,
        "Pop" to MilanMotion.Pop,
    )

    /**
     * 源码根目录。
     *
     * Gradle 跑单测时的工作目录是模块目录（`app/`），但直接用 `File("src/main/java")`
     * 在 IDE 里跑会落空，所以两个候选都试。都找不到就让断言失败，不吞掉。
     */
    private fun sourceFiles(): List<File> {
        val root = listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name == "Motion.kt" }
            .toList()
    }

    private companion object {
        /** `tween(220)`、`spring(0.6f, …)`、`keyframes { durationMillis = 300 }` 的前半段。 */
        val NumericSpecOnly = Regex("""\b(tween|spring|keyframes)\(\s*\d""")

        /** `tween(durationMillis = 380, …)`：首参不是数字时，时长写在命名参数里。 */
        val NumericDuration = Regex("""\b(durationMillis|delayMillis)\s*=\s*\d""")

        /** `spring(stiffness = Spring.StiffnessMediumLow)`：借道 Compose 的常量绕开 token。 */
        val NamedSpringConstant = Regex("""\bSpring\.(Stiffness|DampingRatio)\w*""")
    }

    /** 后两条规则合并判定，避免调用点写两个 `||` 时漏一个。 */
    private fun matchesSpec(code: String): Boolean =
        NumericSpecOnly.containsMatchIn(code) ||
            NumericDuration.containsMatchIn(code) ||
            NamedSpringConstant.containsMatchIn(code)
}
