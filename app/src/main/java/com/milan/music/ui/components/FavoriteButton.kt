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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.milan.music.R
import com.milan.music.ui.theme.LikedRed
import com.milan.music.ui.theme.MilanMotion
import com.milan.music.ui.util.noRippleClickable

/**
 * 收藏按钮，列表行与播放页共用。
 *
 * **不用 `IconButton`**：它的涟漪会在按压时铺出一块圆形底色，在列表里非常突兀，
 * 在播放页的封面色场上更明显。反馈只保留图标本身的缩放交接。
 *
 * 两处只有两个差异，都做成了参数，因为它们各自有依据而不是复制走样：
 *
 *  - [iconSize]：18dp 配 52dp 的列表缩略图，24dp 配满宽的播放页封面。
 *  - [unselectedTint]：列表行在浅色页面上用半透明前景色，播放页在深色色场上
 *    用不透明的米白 —— 那里 alpha 会被色场吃成一块灰。
 */
@Composable
fun FavoriteButton(
    isFavorite: Boolean,
    onToggle: () -> Unit,
    iconSize: Dp,
    unselectedTint: Color,
    label: String,
    modifier: Modifier = Modifier,
) {
    // 48dp 是 Material 的最小触摸目标；图标本身比它小得多。
    Box(
        modifier = modifier
            .size(48.dp)
            .noRippleClickable(
                role = Role.Button,
                // 收藏会改一次持久状态，且图标有一次明显回弹 —— 触觉与视觉同拍。
                haptic = true,
                onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = isFavorite,
            transitionSpec = {
                // 0.45 的阻尼比这一段是收藏专属：要有一次明确的"弹出来"，而不是
                // MilanMotion.Press 那种克制的按压反馈。
                val enter = scaleIn(
                    initialScale = 0.55f,
                    animationSpec = spring(
                        dampingRatio = 0.45f,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ) + fadeIn(tween(MilanMotion.FastMs))
                val exit = scaleOut(
                    targetScale = 0.7f,
                    animationSpec = tween(MilanMotion.FastMs),
                ) + fadeOut(tween(MilanMotion.FastMs / 2))
                enter togetherWith exit
            },
            contentAlignment = Alignment.Center,
            contentKey = { it },
            label = label,
        ) { liked ->
            Icon(
                imageVector = if (liked) {
                    Icons.Rounded.Favorite
                } else {
                    Icons.Rounded.FavoriteBorder
                },
                contentDescription = if (liked) stringResource(R.string.cd_unlike) else stringResource(R.string.cd_like),
                tint = if (liked) LikedRed else unselectedTint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
