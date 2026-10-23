package com.milan.music.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.milan.music.R
import com.milan.music.ui.theme.MilanMotion

/**
 * 播放/暂停图标切换。
 *
 * 旧图标缩小淡出，新图标带一点回弹放大淡入；两个图标占用同一尺寸，
 * 切换过程中按钮本身不会发生布局跳动。
 */
@Composable
fun AnimatedPlayPauseIcon(
    isPlaying: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 30.dp,
) {
    AnimatedContent(
        targetState = isPlaying,
        modifier = modifier,
        transitionSpec = {
            val enter = scaleIn(
                initialScale = 0.55f,
                animationSpec = spring(
                    dampingRatio = 0.6f,
                    stiffness = Spring.StiffnessMedium,
                ),
            ) + fadeIn(tween(MilanMotion.FastMs))
            val exit = scaleOut(
                targetScale = 0.65f,
                animationSpec = tween(MilanMotion.FastMs),
            ) + fadeOut(tween(MilanMotion.FastMs / 2))
            enter togetherWith exit
        },
        contentAlignment = Alignment.Center,
        contentKey = { it },
        label = "play-pause-icon",
    ) { playing ->
        Icon(
            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (playing) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play),
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}
