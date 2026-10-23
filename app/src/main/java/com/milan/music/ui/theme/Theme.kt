package com.milan.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.LocalHazePerformanceMode

private val LightColors = lightColorScheme(
    primary = Amber,
    onPrimary = Color.White,
    primaryContainer = AmberPale,
    onPrimaryContainer = GoldInk,
    secondary = AmberBright,
    onSecondary = GoldInk,
    background = Paper,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = PaperDeep,
    onSurfaceVariant = InkMuted,
    outline = Color(0x331B1713),
    outlineVariant = Color(0x1F1B1713),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = AmberBright,
    onPrimary = Color(0xFF2E2410),
    primaryContainer = Color(0xFF4A3714),
    onPrimaryContainer = Moonlight,
    secondary = Amber,
    onSecondary = Moonlight,
    background = Night,
    onBackground = Moonlight,
    surface = NightRaised,
    onSurface = Moonlight,
    surfaceVariant = Color(0xFF2A2217),
    onSurfaceVariant = NightMuted,
    outline = Color(0x3DFFFFFF),
    outlineVariant = Color(0x1FFFFFFF),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

/**
 * 玻璃材质在主题里的全部可变项 —— 现在只剩「要不要投影」这一条。
 *
 * 第 20 批还剩 `tint`，第 21 批也删掉了。原因是实测：`GlassStyle.clear` 的浅色链
 * 自带 **白点 0.17 + 对比度 0.08**，这两项作用在 L≈0.87 的亮背景上会把整块面板推高
 * 15 级（背景 238,223,184 → 框内 251,238,203，实测吻合），而我们的 tint 只占其中的
 * 1.9 级。所以压 tint 一直压不出效果 —— 主项不在我们手里，在库的校准值里。
 *
 * 更要紧的是连带伤害：同一条链会把 `edgeShadow` 压出的暗带从 −22 级提亮到 −3。
 * 暗带消失后，边缘只剩一条孤立亮线，整块面板就读成"描边贴纸"。
 *
 * 现在的策略是**不做任何色调偏移**（白点 0、对比度 0、tint 用库默认的 Transparent），
 * 让面板内部与背景等价、只在边界上留下光学。真实玻璃在均匀介质上本来就不可见，
 * 可见性全部来自边界 —— 这正是前几批一直缺的那一半。
 */
data class GlassColors(
    /** 深色主题不投影：近黑底上一圈黑影只会把面板压成一块脏斑。 */
    val isDark: Boolean,
)

/**
 * 玻璃材质的主题开关。
 *
 * 抽成纯函数是为了让 `GlassMaterialTest` 能直接断言**主题里真正生效的那组值**。
 * 早先测试自己在用例里复制了一份 color/shadow/intensity，改主题不会让测试变红——
 * 那等于只测了测试自己。
 */
internal fun glassColorsFor(darkTheme: Boolean): GlassColors = GlassColors(isDark = darkTheme)

val LocalGlassColors = staticCompositionLocalOf { glassColorsFor(darkTheme = false) }

/**
 * 墨色前景——图标、选中的 tab、搜索框里的放大镜与排序——的唯一来源。
 *
 * 浅色主题给**纯黑**，深色主题给 [Moonlight]。两条理由都不是审美：
 *
 *  - 不能退回 `MaterialTheme.colorScheme.onBackground`：它指向 [Ink]（`0xFF2A2419`，
 *    暖黑），在浅金底上叠一层 alpha 之后读起来是"灰棕"，不是黑。用户对搜索框的
 *    放大镜与排序图标提过一次「变成纯黑色」，那次前的取值正是 `onBackground @0.62/0.72`。
 *  - 也不能全用纯黑：[Moonlight] 换成纯黑在夜色背景上只有约 1.05:1，等于隐形。
 */
internal fun iconInkFor(darkTheme: Boolean): Color = if (darkTheme) Moonlight else Color.Black

@Composable
fun MilanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val glass = glassColorsFor(darkTheme)

    // 玻璃此前只做模糊时，`Performance` 是划算的：模糊半径会被同样的系数缩小，
    // 只是丢掉了模糊前的细节，而 30dp 的模糊本来就留不住细节。
    //
    // 换成 `hazeGlass` 之后这个前提不成立了——**折射就是细节本身**。输入被降采样
    // 一半，边缘那道被压缩的内容环会一起变软，等于把这次要加回来的东西又削掉一截。
    // 所以回到 `Default`（Balanced），先用文档推荐的档位，等真机测到帧率再说。
    CompositionLocalProvider(
        LocalGlassColors provides glass,
        LocalHazePerformanceMode provides HazePerformanceMode.Default,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = MilanTypography,
            content = content,
        )
    }
}
