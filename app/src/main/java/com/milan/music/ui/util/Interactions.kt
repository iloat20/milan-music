package com.milan.music.ui.util

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import com.milan.music.ui.theme.MilanMotion

/**
 * 不显示按压涟漪（indication）的点击修饰符。
 * 用于玻璃面板上的导航项与播放控制按钮，避免按压时出现整块背景色变化。
 *
 * [pressScale] 不为空时同时提供缩放反馈：按下缩小，松手回弹。
 * 传了 [pressScaleY] 就是非等比形变（横向留、纵向压），只给矩形面板用 ——
 * 圆形图标在非等比下会变成椭圆，看起来是图形被拉坏而不是被按下。见
 * `MilanMotion.PressSquashScaleX`。
 *
 * [haptic] 传具体档位而不是布尔值：**触觉要表达语义**，不是"有/没有"。
 * 播放=`ToggleOn`、暂停=`ToggleOff`、收藏=`Confirm`、手势越过阈值=
 * `GestureThresholdActivate`。所有按钮共用 `TextHandleMove` 在物理上是错的 ——
 * 那一档的语义是"光标移动"。
 *
 * 仍然**不做**音频同步触觉：那是 Android 17 的能力，但与"安静听歌"的产品意图相反。
 */
@Composable
fun Modifier.noRippleClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    pressScale: Float? = null,
    pressScaleY: Float? = null,
    haptic: HapticFeedbackType? = null,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val hapticFeedback = LocalHapticFeedback.current
    val clickable = clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        role = role,
        onClickLabel = onClickLabel,
        onClick = {
            haptic?.let(hapticFeedback::performHapticFeedback)
            onClick()
        },
    )
    return if (pressScale == null) {
        clickable
    } else {
        clickable.pressScale(interactionSource, pressScale, pressScaleY ?: pressScale)
    }
}

/**
 * [noRippleClickable] 的长按版本。
 */
@Composable
fun Modifier.noRippleCombinedClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    pressScale: Float? = null,
    pressScaleY: Float? = null,
    haptic: HapticFeedbackType? = null,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val hapticFeedback = LocalHapticFeedback.current
    val clickable = combinedClickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        role = role,
        onClickLabel = onClickLabel,
        onLongClickLabel = onLongClickLabel,
        onLongClick = onLongClick,
        onClick = {
            haptic?.let(hapticFeedback::performHapticFeedback)
            onClick()
        },
    )
    return if (pressScale == null) {
        clickable
    } else {
        clickable.pressScale(interactionSource, pressScale, pressScaleY ?: pressScale)
    }
}

/**
 * 把 [interactionSource] 的按压状态映射成图层缩放。
 *
 * 只改 graphicsLayer，不触发布局；命中区域保持原尺寸，手指不会因为
 * 视觉缩小而"滑出"按钮。
 *
 * [pressedScaleY] 默认等于 [pressedScaleX]，即等比缩放。
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScaleX: Float,
    pressedScaleY: Float = pressedScaleX,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scaleX by animateFloatAsState(
        targetValue = if (pressed) pressedScaleX else 1f,
        animationSpec = MilanMotion.Press,
        label = "press-scale-x",
    )
    val scaleY by animateFloatAsState(
        targetValue = if (pressed) pressedScaleY else 1f,
        animationSpec = MilanMotion.Press,
        label = "press-scale-y",
    )
    return graphicsLayer {
        this.scaleX = scaleX
        this.scaleY = scaleY
    }
}
