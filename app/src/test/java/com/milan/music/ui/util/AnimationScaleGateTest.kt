package com.milan.music.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "移除动画"必须在运行期生效——这条只能靠结构守。
 *
 * 行为本身没法在 JVM 单测里断言：真正要做的事是"向 `SettingsProvider` 注册一个
 * `ContentObserver`，并在回调里更新状态"，而起一个 `ContentResolver` 需要真实的
 * Android 环境。能钉住的只有**结构**：读这个键的地方必须只有一处，且那一处必须是
 * 观察者而不是一次性读取。
 *
 * 退化形态非常隐蔽：把 `rememberAnimationScale()` 换回
 * `remember(context) { Settings.Global.getFloat(...) }`，编译通过、首帧表现完全正确，
 * 只有在"开着应用改设置"这一条路径上才失效——而那正是无障碍用户的操作方式。
 */
class AnimationScaleGateTest {

    @Test
    fun `the animation scale is read from exactly one place`() {
        val files = sourceFiles()
        assertTrue("没扫到源码（cwd=${File("").absolutePath}）", files.size > 20)

        val readers = files
            .filter { it.readText().contains("ANIMATOR_DURATION_SCALE") }
            .map { it.name }
            .sorted()

        assertEquals(
            "ANIMATOR_DURATION_SCALE 只能由 AnimationScale.kt 读取。别处用 " +
                "`remember { ... }` 读一次，运行期改设置就不会生效：" +
                "应改用 rememberAnimationScale()",
            listOf("AnimationScale.kt"),
            readers,
        )
    }

    @Test
    fun `that one place observes the setting instead of sampling it`() {
        val reader = sourceFiles().firstOrNull { it.name == "AnimationScale.kt" }
        assertTrue("AnimationScale.kt 不见了", reader != null)
        val source = reader!!.readText()

        listOf(
            "DisposableEffect" to "没有 DisposableEffect，观察者在组合结束后不会注销",
            "unregisterContentObserver" to "没有注销观察者：列表滚一遍就漏一批回调",
            "onChange" to "没有 onChange：注册了但不会把新值写回状态",
        ).forEach { (needle, why) ->
            assertTrue("AnimationScale.kt 缺 `$needle` —— $why", source.contains(needle))
        }
        // 不能用 `contains("registerContentObserver")`：`unregisterContentObserver` 里就
        // 含这个子串，删掉注册那一句仍然"命中"。这个判据真正要钉的是**注册**调用。
        assertTrue(
            "AnimationScale.kt 没有注册观察者：读到的永远是组合期的旧值",
            Regex("""(?<!un)registerContentObserver\(""").containsMatchIn(source),
        )
    }

    @Test
    fun `the playing bars go through the observer`() {
        val trackRow = sourceFiles().firstOrNull { it.name == "TrackRow.kt" }
        assertTrue("TrackRow.kt 不见了", trackRow != null)
        val source = trackRow!!.readText()

        assertTrue(
            "PlayingBars 必须用 rememberAnimationScale() 取倍率",
            source.contains("rememberAnimationScale()"),
        )
        // 直接读设置项会绕过观察者；键本身也只允许出现在 AnimationScale.kt。
        assertTrue(
            "TrackRow.kt 不应直接读 ANIMATOR_DURATION_SCALE",
            !source.contains("ANIMATOR_DURATION_SCALE"),
        )
    }

    /** 与 `MilanMotionTest` 相同的根目录探测：Gradle 下是 `app/`，IDE 下是模块根。 */
    private fun sourceFiles(): List<File> {
        val root = listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }
}
