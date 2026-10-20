package com.milan.music.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * 全应用共用的动效节奏。
 *
 * 时长按用途分档：点按反馈必须立即被感知；内容切换要看得见但不拖沓；
 * 封面与环境色属于氛围层，可以慢半拍。曲线默认取系统标准的
 * [FastOutSlowInEasing]，需要强调的进出场再用 [Emphasized]。
 */
object MilanMotion {
    const val FastMs = 140
    const val MediumMs = 240
    const val SlowMs = 420
    const val AmbientMs = 650

    val Standard: Easing = FastOutSlowInEasing
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 按压回弹：有一点过冲，松手后立刻回位。 */
    val Press: SpringSpec<Float> = spring(
        dampingRatio = 0.72f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** 手势取消、布局落位：比 [Press] 更稳，避免二次弹跳。 */
    val Settle: SpringSpec<Float> = spring(
        dampingRatio = 0.85f,
        stiffness = Spring.StiffnessMediumLow,
    )
}
