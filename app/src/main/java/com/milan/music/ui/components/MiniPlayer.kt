package com.milan.music.ui.components

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.milan.music.data.media.ArtworkRepository
import com.milan.music.play.PlayerUiState
import com.milan.music.R
import com.milan.music.ui.glass.GlassSurface
import com.milan.music.ui.text.artistText
import com.milan.music.ui.text.titleText
import com.milan.music.ui.theme.MilanMotion
import com.milan.music.ui.util.noRippleClickable
import dev.chrisbanes.haze.HazeState

@Composable
fun MiniPlayer(
    state: PlayerUiState,
    artworkRepository: ArtworkRepository,
    hazeState: HazeState,
    onClick: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 这张卡片在窗口坐标系里的位置与尺寸。
     *
     * 播放页的入场动画要用它当起点（iOS 27 的"卡片长大成整页"）：没有这个矩形，
     * 入场只能退回"从屏幕外滑入"，两种观感的差别就在这一步。
     */
    onBoundsChange: ((Rect) -> Unit)? = null,
) {
    val track = state.currentTrack ?: return

    GlassSurface(
        hazeState = hazeState,
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                onBoundsChange?.invoke(coordinates.boundsInWindow())
            }
            // 整张卡片是一个点击目标；内层播放/下一首按钮会先消费点击。
            // 玻璃面板上不加涟漪，改用轻微缩放作为按压反馈。
            .noRippleClickable(
                role = Role.Button,
                pressScale = 0.985f,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(26.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 9.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 46dp 方图用同一个 4dp 圆角：和列表 52dp 那块是一套形状，
            // 不是"按边长等比缩放"的两种弧度。见 CoverCornerRadius。
            CoverArt(
                track = track,
                artworkRepository = artworkRepository,
                modifier = Modifier.size(46.dp),
                cornerRadius = CoverCornerRadius,
                iconSize = 18.dp,
            )

            Spacer(Modifier.width(11.dp))

            // 切歌时只让封面、标题、艺人这一块做短距离交接；
            // 右侧播放控制保持原位，避免整行一起晃动。
            AnimatedContent(
                targetState = track,
                modifier = Modifier.weight(1f),
                transitionSpec = { MilanMotion.NeighbourSwap },
                contentAlignment = Alignment.CenterStart,
                contentKey = { it.id },
                label = "mini-player-track",
            ) { item ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = item.titleText(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.artistText(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .noRippleClickable(
                            role = Role.Button,
                            pressScale = 0.86f,
                            // 播放/暂停是"状态真的翻了"的动作，给一次轻触觉。
                            haptic = true,
                            onClick = onTogglePlay,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedPlayPauseIcon(
                        isPlaying = state.isPlaying,
                        tint = MaterialTheme.colorScheme.onBackground,
                        iconSize = 30.dp,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .noRippleClickable(
                            role = Role.Button,
                            pressScale = 0.86f,
                            onClick = onNext,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = stringResource(R.string.cd_next),
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(46.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}
