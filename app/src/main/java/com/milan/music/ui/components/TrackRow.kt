package com.milan.music.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.milan.music.data.media.ArtworkRepository
import com.milan.music.data.model.Track
import com.milan.music.ui.theme.MilanMotion
import com.milan.music.ui.util.noRippleClickable

@Composable
fun TrackRow(
    track: Track,
    artworkRepository: ArtworkRepository,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlight = MaterialTheme.colorScheme.primary

    // 当前曲目只靠标题色与跳动波形标识，整行不再铺 primary 底色：
    // 列表是多行并排的，一块半透明色带会把行与行切成两截。
    val titleColor by animateColorAsState(
        targetValue = if (isCurrent) highlight else MaterialTheme.colorScheme.onBackground,
        animationSpec = tween(MilanMotion.MediumMs, easing = MilanMotion.Standard),
        label = "track-row-title",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 52dp 方图配 4dp 圆角（≈8%），和参考版的列表缩略图同一个形状：
        // 是"方片带一点倒角"，不是 squircle。弧度值只有一处定义，见 CoverCornerRadius。
        CoverArt(
            track = track,
            artworkRepository = artworkRepository,
            modifier = Modifier.size(52.dp),
            cornerRadius = CoverCornerRadius,
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = track.displayArtist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (isCurrent && isPlaying) {
            Spacer(Modifier.width(6.dp))
            PlayingBars(color = highlight)
        }

        Spacer(Modifier.width(6.dp))

        // 收藏按钮不使用 IconButton：它的涟漪会在按压时铺出一块圆形底色，
        // 在列表里非常突兀。这里只保留图标本身的切换动画作为反馈，
        // 48dp 的命中区域与 Material 的最小触摸目标一致。
        Box(
            modifier = Modifier
                .size(48.dp)
                .noRippleClickable(
                    role = Role.Button,
                    onClick = onToggleFavorite,
                ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = isFavorite,
                transitionSpec = {
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
                label = "track-favorite",
            ) { liked ->
                Icon(
                    imageVector = if (liked) {
                        Icons.Rounded.Favorite
                    } else {
                        Icons.Rounded.FavoriteBorder
                    },
                    contentDescription = if (liked) "取消喜欢" else "喜欢",
                    tint = if (liked) {
                        com.milan.music.ui.theme.LikedRed
                    } else {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
fun PlayingBars(
    color: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 3,
) {
    val transition = rememberInfiniteTransition(label = "playing-bars")
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(barCount) { index ->
            val scale by transition.animateFloat(
                initialValue = 0.30f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 380 + index * 110,
                        easing = FastOutSlowInEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bar-$index",
            )
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(13.dp * scale)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}
