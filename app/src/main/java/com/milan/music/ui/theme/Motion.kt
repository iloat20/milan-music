package com.milan.music.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * 全应用共用的动效节奏。第 71 批改成**两层制**（Android 17 的 Expressive 口径 + iOS 27 的克制口径）：
 *
 *  - **Spatial 层**（位置、尺寸、旋转）：一律走 spring。物理量该由弹簧解算；写时长等于给
 *    一段本可以用物理描述的运动套上假表盘 —— 这正是 Android 17 把 Compose 默认曲线换掉的原因。
 *  - **Effects 层**（不透明度、颜色、图标交接）：仍走 [tween] 时长。alpha 与颜色**不能**
 *    弹簧化：过冲会把 alpha 推出 1（被裁），或让颜色越过目标值再回来（闪一下反色）。
 *
 * 两层分工是硬约束，不是偏好。判据只有一条：**这个属性过冲了会不会破坏内容**。
 * 会（alpha / 颜色）就进 Effects，不会（位置 / 尺寸）就进 Spatial。
 *
 * 第三层（材质：模糊半径、边缘高光）不在这个文件里 —— 它必须**跳档**而不是插值，
 * 见倒影的模糊阶梯铁律（`reflection.md`）。
 */
object MilanMotion {
    // ---------------------------------------------------------------- Effects 层

    const val FastMs = 140
    const val MediumMs = 240
    const val SlowMs = 420
    const val AmbientMs = 650

    /**
     * 进度条形变：条高在"细线"与"粗条"之间切换。
     *
     * **刻意留在 Effects 层**：时间轴的视觉反馈一旦弹簧化，条会看起来在"追"手指 ——
     * 手指已经到位，条还在往目标滑，读起来就是播放位置滞后。
     */
    const val ProgressMs = 160

    /**
     * "正在播放"的三条柱：基础周期，以及逐条叠加的相位差。
     *
     * 三条同相位会读成一根在呼吸的条，错开 110ms 才像频谱。这是无限循环，不属于交接，
     * 所以只给时长。
     */
    const val BarsBaseMs = 380
    const val BarsStepMs = 110

    val Standard: Easing = FastOutSlowInEasing

    // ---------------------------------------------------------------- Spatial 层

    /**
     * 按压回弹：有一点过冲，松手后立刻回位。
     *
     * 阻尼 0.72 是"看得出弹，但不至于抖两下"。这是全应用唯一允许明显过冲的档位。
     */
    const val PressDampingRatio = 0.72f
    const val PressStiffness = Spring.StiffnessMediumLow

    /**
     * 近距离交接：曲目行、迷你播放器那一行换曲时的位移。
     *
     * 行程只有自身高度的三分之一，刚度要高 —— 高刚度 + 高阻尼等于"利落到位、不晃"；
     * 低刚度会让连续切歌叠出一串没走完的弹簧。
     */
    const val SwapDampingRatio = 0.85f
    const val SwapStiffness = 520f

    /**
     * 播放页开合：整页尺度的位移与缩放。
     *
     * 比 [SwapDampingRatio] 更软、刚度更低：行程是整屏量级，同样的刚度会显得急停。
     * 0.88 的阻尼基本不振荡，末尾只剩一次极轻的收势。
     */
    const val SheetDampingRatio = 0.88f
    const val SheetStiffness = 380f

    /**
     * 入场生长：播放页从迷你播放器位置长到全屏。
     *
     * 全场最软的一档。生长是用户只看一次的动作，允许它慢；手势回弹
     * （[SheetDampingRatio]）会被反复触发，必须更快收住。
     */
    const val GrowDampingRatio = 0.90f
    const val GrowStiffness = 260f

    /**
     * 离场：播放页下滑到屏幕外。
     *
     * 阻尼 0.95 + 高刚度 = 几乎不振荡且很快收住。离场**不该**有回弹 ——
     * 手指已经把页面推走了，再弹一下等于否定用户的操作。
     */
    const val ExitDampingRatio = 0.95f
    const val ExitStiffness = 480f

    /**
     * 回位：把被拖走或放错的东西送回静止位置。
     *
     * 阻尼 1.0 —— 这一档**不许过冲**，而且理由不是审美。过冲在这里露出的是本不该出现的
     * 内容：封面行冲过 0 会露出相邻页，列表项冲过目标会和上下行重叠一帧，旧图标缩过头
     * 会先变小再长回去。
     */
    const val SettleDampingRatio = 1f
    const val SettleStiffness = Spring.StiffnessMediumLow

    /**
     * 图标交接：播放/暂停那一次明显的"弹出来"。
     *
     * 0.6 是关键阻尼比，属于刻意的大过冲。它只在图标上成立 —— 行程是图标自身的 45%，
     * 面积小，读起来是"弹"；同一组参数用在文字或面板上就是廉价感。
     */
    const val IconDampingRatio = 0.6f
    const val IconStiffness = Spring.StiffnessMedium

    /**
     * 收藏：比 [IconDampingRatio] 更软的一次弹出。
     *
     * 收藏是一次持久状态变更，动作可以比播放/暂停更夸张 —— 它不像播放键那样会被连点。
     */
    const val PopDampingRatio = 0.45f
    const val PopStiffness = Spring.StiffnessMediumLow

    /**
     * 按压形变：横向几乎不动，纵向压下去。
     *
     * Android 17 的按压反馈是**体积近似守恒的挤压**，不是整体缩小。等比缩放在一张卡片上
     * 读起来是"这张卡片退后了一点"，非等比才读成"被手指按下去"。
     *
     * 两个数的乘积 0.994 × 0.976 ≈ 0.970，与旧的等比 0.985²（≈0.970）面积相同 ——
     * 换的是形变方向，不是按压力度。所以这不是"更用力"，只是"更像按压"。
     *
     * **只给矩形面板用**。圆形图标按钮（播放键、切歌、收藏）保持等比：
     * 圆在非等比缩放下会变成椭圆，读起来是图形被拉坏，而不是被按下。
     */
    const val PressSquashScaleX = 0.994f
    const val PressSquashScaleY = 0.976f

    /**
     * 系统关掉动画时，"正在播放"三条柱的静止高度（占满格的比例）。
     *
     * 取 0.55 是因为它落在正常呼吸区间的中段（0.30–1.0）：静态画面看起来仍像"在响"，
     * 而不是三根齐平的短棍。
     */
    const val BarsRestScale = 0.55f

    /**
     * 排序面板的圆角：展开前的初值，与展开后的 [SortMenuCorner]。
     *
     * 差 8dp 是一次形状收敛 —— 面板从更圆的初始态压到终态，正是 M3 Expressive 的
     * "menu 从触发点的形状长成面板"里可复现的那一半（完整形状库在 material3 1.5+，
     * 当前 1.4.0 没有 `MaterialShapes`）。
     */
    val SortMenuCornerFrom: Dp = 28.dp
    val SortMenuCorner: Dp = 20.dp

    val Press: SpringSpec<Float> = spring(PressDampingRatio, PressStiffness)

    val Swap: SpringSpec<Float> = spring(SwapDampingRatio, SwapStiffness)

    val Sheet: SpringSpec<Float> = spring(SheetDampingRatio, SheetStiffness)

    val Grow: SpringSpec<Float> = spring(GrowDampingRatio, GrowStiffness)

    val Exit: SpringSpec<Float> = spring(ExitDampingRatio, ExitStiffness)

    val Settle: SpringSpec<Float> = spring(SettleDampingRatio, SettleStiffness)

    val Icon: SpringSpec<Float> = spring(IconDampingRatio, IconStiffness)

    val Pop: SpringSpec<Float> = spring(PopDampingRatio, PopStiffness)

    /**
     * 位移用的整型版本。
     *
     * `slideInVertically` 要的是 `FiniteAnimationSpec<IntOffset>`，吃不下
     * [SpringSpec]<Float>。这里用同一组常量再声明一份，保证两种类型是**同一根**弹簧：
     * 各写一份数字迟早漂移，而漂移之后的现象是"同一次切换里位移和缩放不同步"，极难归因。
     */
    private val SwapOffset: FiniteAnimationSpec<IntOffset> =
        spring(SwapDampingRatio, SwapStiffness)

    /**
     * 回位用的整型版本。
     *
     * `animateItem` 的 `placementSpec` 要的是 `FiniteAnimationSpec<IntOffset>`，吃不下
     * [SpringSpec]<Float>。与 [SwapOffset] 同理，用同一组常量再声明一份，保证列表落位
     * 与封面回位是**同一根**弹簧。
     */
    val SettleOffset: FiniteAnimationSpec<IntOffset> =
        spring(SettleDampingRatio, SettleStiffness)

    /**
     * [Sheet] 的 Dp 版本。
     *
     * `animateDpAsState` 要的是 `AnimationSpec<Dp>`，同样吃不下 `SpringSpec<Float>`。
     * 排序面板的圆角收敛走这一根，保证"面板长大"和"圆角收拢"是同一次运动，而不是
     * 两个各自为政的动画叠在一起。
     */
    val SheetDp: FiniteAnimationSpec<Dp> = spring(SheetDampingRatio, SheetStiffness)

    // ---------------------------------------------------------------- 组合动作

    /**
     * 同尺寸内容交接：播放页的曲目信息、迷你播放器那一行。
     *
     * 位移约 1/3 自身高度 —— 够看出"换了一条"，又不至于让整块文字飞出视野。
     * 位移走 Spring，淡变走 [MediumMs] / [FastMs]：新内容要看清，旧内容让位要快。
     */
    val NeighbourSwap: ContentTransform = ContentTransform(
        targetContentEnter = slideInVertically(SwapOffset) { it / 3 } +
            fadeIn(tween(MediumMs)),
        initialContentExit = slideOutVertically(SwapOffset) { -it / 3 } +
            fadeOut(tween(FastMs)),
    )

    /**
     * 整页内容交接：曲库 / 喜欢两个页签之间。
     *
     * **刻意留在 Effects 层**（第 71 批确认：页签位移不动）。0.05 屏高的行程换成弹簧
     * 既看不出物理感，又会在长列表滚动后留下一记多余的收势；位移再大就成了"翻页"，
     * 滚动位置还会跟着跳一下。
     */
    val PageSwap: ContentTransform = ContentTransform(
        targetContentEnter = slideInVertically(tween(MediumMs)) { it / 20 } +
            fadeIn(tween(MediumMs)),
        initialContentExit = slideOutVertically(tween(FastMs)) { -it / 20 } +
            fadeOut(tween(FastMs)),
    )

    /**
     * 随状态显隐的浮层：从下方升起，消失时同向退出。
     *
     * 用于迷你播放器跟随队列出现 / 消失。进走 Spring，退走 [FastMs]：退出的时间必须是
     * 可预期的，否则底部导航栏会在一个尚未收完的弹簧上开始布局。
     */
    val RiseIn: EnterTransition = fadeIn(tween(MediumMs)) +
        slideInVertically(SwapOffset) { it / 3 }

    val SinkOut: ExitTransition = fadeOut(tween(FastMs)) +
        slideOutVertically(tween(FastMs)) { it / 3 }
}
