package com.milan.music.ui.glass

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.milan.music.ui.theme.glassColorsFor
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.glass.GlassStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玻璃材质的参数契约。
 *
 * 第 19 批的教训是：测试不能只断言"我写进去了什么"，因为真正决定观感的是
 * **库解析之后**的值——我们写的每一处覆盖都会把 Clear 的校准值顶掉，而那种破坏
 * 在源码里完全看不出来（0.15 和 0.42 都是普通小数）。
 *
 * 所以这里用反射读回 `resolveGlassStyleValues` 的结果。库把它标成了 `internal`，
 * Kotlin 编译期读不到，JVM 反射可以——测试因此能断言"生效的确实是 Clear 的光学
 * 剖面"，而不只是"我调用了 glassStyleFor"。
 *
 * 守的六条，每条都对应一次真实事故或被用户否掉的现象：
 *
 *  - `whitePoint` 不为 0 → 亮背景上的面板整体提亮成奶白（用户：「面板太白了」）；
 *  - `contrast` 不为 0 → 同上，而且在 L≈0.87 上比白点更狠（+7.6 级）；
 *  - `specularIntensity` / `ambientResponse` 不为 0 → 外圈亮线回来，读成"塑料描边"；
 *  - `edgeShadow` 有 alpha → 一圈画上去的暗带回来（用户：「暗边还是很丑」）；
 *  - `refractionDetailIntensity` 归零 → 边缘没有二次折射细节（Regular 就是 0）；
 *  - `refractionStrength` 掉回 0.7 → 棱变浅，"弯折"看不出来。
 */
@OptIn(ExperimentalHazeApi::class)
class GlassMaterialTest {

    private val themes = listOf("浅色" to false, "深色" to true)

    @Test
    fun noToneChainOnTopOfTheBackdrop() {
        // 第 21 批的核心断言。Clear 的浅色链是「白点 0.17 → 对比度 0.08」，
        // 在 L≈0.87 的亮背景上依次把面板推高 5.4 和 7.6 级：背景 (238,223,184)
        // 因此变成框内 (251,238,203)。两套主题都必须归零，否则浅色面板又会浮起来。
        themes.forEach { (name, darkTheme) ->
            val whitePoint = Resolved.value(darkTheme, "whitePoint") as Float
            val contrast = Resolved.value(darkTheme, "contrast") as Float
            assertEquals(
                "$name 的白点是 $whitePoint：非零会把亮背景上的面板整体提亮，" +
                    "用户看到的「面板太白了」就是它（Clear 的浅色校准值是 0.17）。",
                0f,
                whitePoint,
                1e-6f,
            )
            assertEquals(
                "$name 的对比度是 $contrast：非零在 L≈0.87 上比白点更狠（+7.6 级），" +
                    "并且会把 edgeShadow 压出的暗边提亮到看不见。",
                0f,
                contrast,
                1e-6f,
            )
        }
    }

    @Test
    fun tintStaysTransparentSoTheBackdropKeepsItsHue() {
        // tint 是"染色"，任何 alpha 都会盖住背后内容的色相。真实玻璃无色。
        // 读的是库解析后的 tint（Clear 不写它，所以默认 Transparent 会原样留着）。
        themes.forEach { (name, darkTheme) ->
            val tint = Resolved.color(darkTheme, "tint")
            assertEquals(
                "$name 的 tint 是 $tint（alpha ${tint.alpha}）：染色会压住折射内容的色相，" +
                    "面板又变回一层幕。真实玻璃没有颜色。",
                0f,
                tint.alpha,
                1e-6f,
            )
        }
    }

    @Test
    fun edgeHasNoPaintedLayerAtAll() {
        // 第 26 批关掉亮边，第 27 批连暗边也关掉。三条路径都必须归零，缺一个边就回来：
        //   - specularIntensity：方向性镜面高光（Clear 0.55）→ 外侧亮线
        //   - ambientResponse：菲涅尔边缘提亮（Clear 0.42）→ 亮线从另一条路径回来
        //   - edgeShadow：按形状描一圈的暗带（Clear 是 Black@0.10）→ 用户「暗边还是很丑」
        // 这三项都是**绘制**出来的，不是光学。真实玻璃的边缘只由折射给出，而折射要可见
        // 得有高频背景；在这块低频背景上采样回来的颜色几乎不变，画出来反而成了描边。
        themes.forEach { (name, darkTheme) ->
            val specular = Resolved.value(darkTheme, "specularIntensity") as Float
            val ambient = Resolved.value(darkTheme, "ambientResponse") as Float
            val shadowAlpha = Resolved.color(darkTheme, "edgeShadow").alpha
            assertEquals(
                "$name 的镜面高光是 $specular：非零会留下外圈亮线，用户看到的\"塑料描边\"就是它。",
                0f,
                specular,
                1e-6f,
            )
            assertEquals(
                "$name 的环境光响应是 $ambient：它负责菲涅尔边缘提亮，非零时亮线会从这条路径回来。",
                0f,
                ambient,
                1e-6f,
            )
            assertEquals(
                "$name 的暗边 alpha 是 $shadowAlpha：非零是一圈按形状描上去的暗带，不是光学，" +
                    "在浅色背景上会被读成\"脏边\"（用户：「暗边还是很丑」）。",
                0f,
                shadowAlpha,
                1e-6f,
            )
        }
    }

    @Test
    fun edgeRefractionDetailIsOn() {
        // Clear = 0.76，Regular = 0。这是"边缘有没有弯折"的开关，一旦被覆盖成 0，
        // 边缘就只剩光照、没有折射细节。
        themes.forEach { (name, darkTheme) ->
            val detail = Resolved.opticsFloat(darkTheme, "refractionDetailIntensity")
            assertTrue(
                "$name 的边缘折射细节是 $detail：低于 0.5 说明走的是 Regular 的剖面" +
                    "（它是 0），边缘不会被弯折——用户上一批的原话就是「边缘没弯折」。",
                detail >= 0.5f,
            )
        }
    }

    @Test
    fun refractionIsStrongerThanRegular() {
        // Clear 0.85 / Regular 0.7。掉回 0.7 表示样式被换成 Regular 或被覆盖。
        themes.forEach { (name, darkTheme) ->
            val strength = Resolved.opticsFloat(darkTheme, "refractionStrength")
            assertTrue(
                "$name 的折射强度是 $strength：低于 0.80 说明底座不是 Clear。",
                strength >= 0.80f,
            )
        }
    }

    @Test
    fun chromaticAberrationStaysInTheDocumentedBand() {
        // Haze 文档：起步 0.1~0.25，再高边缘出彩虹。守的是"别调成棱镜"。
        assertTrue(
            "色散强度 $GlassChromaticAberration 落在文档建议的 0.1~0.25 之外。",
            GlassChromaticAberration in 0.1f..0.25f,
        )
    }

    @Test
    fun overridesAreAppliedButDoNotReplaceTheBuiltIn() {
        // 覆盖必须真的写进去（否则圆角是库默认的 16dp），但也不能把 Clear 本体
        // 换成另一个对象——那意味着 Clear 的整条剖面被整体替换了。
        val style = glassStyleFor(RoundedCornerShape(20.dp))
        assertNotEquals(
            "Style 与 GlassStyle.clear 是同一个对象：shape 的覆盖写入没有生效。",
            GlassStyle.clear,
            style,
        )
    }

    /**
     * 读回 Haze 解析后的 `GlassStyleValues`。
     *
     * 库把这两个类型标成 `internal`，所以只能走反射。字段名与
     * `resolveGlassStyleValues(localStyle, explicitStyle, appearance)` 的签名是
     * haze-glass 2.0.1 的实际结构；升级库时若这里先报错，正好提醒复核校准值。
     */
    private object Resolved {

        private val resolveMethod = run {
            val styleKt = Class.forName("dev.chrisbanes.haze.glass.GlassStyleKt")
            styleKt.declaredMethods
                .first { it.name == "resolveGlassStyleValues" && it.parameterCount == 3 }
                .also { it.isAccessible = true }
        }

        private val appearanceClass = Class.forName("dev.chrisbanes.haze.glass.GlassSystemAppearance")

        fun value(darkTheme: Boolean, path: String): Any {
            var current: Any = resolve(darkTheme)
            for (name in path.split('.')) {
                val field = current.javaClass.getDeclaredField(name)
                field.isAccessible = true
                current = field.get(current)!!
            }
            return current
        }

        fun opticsFloat(darkTheme: Boolean, name: String): Float = value(darkTheme, "optics.$name") as Float

        /**
         * `Color` 是 Kotlin 的 value class，JVM 上擦除成 `long` —— 反射读出来是 [Long]，
         * 直接强转会抛 ClassCastException。这里按它的打包格式还原。
         */
        fun color(darkTheme: Boolean, path: String): Color =
            Color((value(darkTheme, path) as Long).toULong())

        private fun resolve(darkTheme: Boolean): Any {
            val appearance = appearanceClass.enumConstants.orEmpty()
                .first { (it as Enum<*>).name == if (darkTheme) "Dark" else "Light" }
            val style = glassStyleFor(RoundedCornerShape(20.dp))
            return resolveMethod.invoke(null, GlassStyle, style, appearance)!!
        }
    }
}
