package com.milan.music.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith

/**
 * 全应用共用的动效节奏。
 *
 * 时长按用途分档：点按反馈必须立即被感知；内容切换要看得见但不拖沓；
 * 封面与环境色属于氛围层，可以慢半拍。曲线默认取系统标准的
 * [FastOutSlowInEasing]。
 */
object MilanMotion {
    const val FastMs = 140
    const val MediumMs = 240
    const val SlowMs = 420
    const val AmbientMs = 650

    val Standard: Easing = FastOutSlowInEasing

    /** 按压回弹：有一点过冲，松手后立刻回位。 */
    val Press: SpringSpec<Float> = spring(
        dampingRatio = 0.72f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /**
     * 同尺寸内容交接：播放页的曲目信息、迷你播放器那一行。
     *
     * 位移约 1/3 自身高度 —— 够看出"换了一条"，又不至于让整块文字飞出视野。
     */
    val NeighbourSwap: ContentTransform = contentSwap(offsetDivisor = 3)

    /**
     * 整页内容交接：曲库 / 喜欢两个页签之间。
     *
     * 位移只有 0.05 屏高：列表本身很长，位移再大就成了"翻页"而不是"切换"，
     * 滚动位置还会跟着跳一下。
     */
    val PageSwap: ContentTransform = contentSwap(offsetDivisor = 20)

    /**
     * 内容交接：新内容从下方少许升起并淡入，旧内容向上退出。
     *
     * 三处调用原本各抄了一份，参数一字不差，只有位移分母不同（见 [NeighbourSwap] /
     * [PageSwap]）。进出用不同时长（[MediumMs] 进、[FastMs] 出）是有意的：新内容要
     * 看清，旧内容让位要快，否则交接会显得拖沓。
     */
    private fun contentSwap(offsetDivisor: Int): ContentTransform {
        val enter = slideInVertically(tween(MediumMs)) { it / offsetDivisor } +
            fadeIn(tween(MediumMs))
        val exit = slideOutVertically(tween(FastMs)) { -it / offsetDivisor } +
            fadeOut(tween(FastMs))
        return enter togetherWith exit
    }

    /**
     * 随状态显隐的浮层：从下方升起，消失时同向退出。
     *
     * 用于迷你播放器跟随队列出现 / 消失。与 [contentSwap] 的唯一区别是退出方向：
     * 交接是"旧的上、新的从下进"，显隐是"原路返回"。
     */
    val RiseIn: EnterTransition = fadeIn(tween(MediumMs)) +
        slideInVertically(tween(MediumMs)) { it / 3 }

    val SinkOut: ExitTransition = fadeOut(tween(FastMs)) +
        slideOutVertically(tween(FastMs)) { it / 3 }
}
