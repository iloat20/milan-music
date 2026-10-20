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
import androidx.compose.ui.semantics.Role
import com.milan.music.ui.theme.MilanMotion

/**
 * 不显示按压涟漪（indication）的点击修饰符。
 * 用于玻璃面板上的导航项与播放控制按钮，避免按压时出现整块背景色变化。
 *
 * [pressScale] 不为空时同时提供缩放反馈：按下缩小，松手回弹。
 */
@Composable
fun Modifier.noRippleClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    pressScale: Float? = null,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val clickable = clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        role = role,
        onClickLabel = onClickLabel,
        onClick = onClick,
    )
    return if (pressScale == null) {
        clickable
    } else {
        clickable.pressScale(interactionSource, pressScale)
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
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val clickable = combinedClickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        role = role,
        onClickLabel = onClickLabel,
        onLongClickLabel = onLongClickLabel,
        onLongClick = onLongClick,
        onClick = onClick,
    )
    return if (pressScale == null) {
        clickable
    } else {
        clickable.pressScale(interactionSource, pressScale)
    }
}

/**
 * 把 [interactionSource] 的按压状态映射成图层缩放。
 *
 * 只改 graphicsLayer，不触发布局；命中区域保持原尺寸，手指不会因为
 * 视觉缩小而"滑出"按钮。
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = MilanMotion.Press,
        label = "press-scale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
