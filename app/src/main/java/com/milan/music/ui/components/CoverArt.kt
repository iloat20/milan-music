package com.milan.music.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.milan.music.data.media.ArtworkRepository
import com.milan.music.data.model.Track
import com.milan.music.ui.theme.MilanMotion

/**
 * 列表与迷你播放器里那块小封面的圆角。
 *
 * 4dp 是按参考图（Apple Music 的开始收听列表）量出来的：52dp 的缩略图圆角约 3.4dp，
 * 占边长 6.4%。这是个**固定值而不是边长比例**——小图的圆角本来就该由视觉重量决定，
 * 按比例缩放会让 46dp 和 52dp 的封面出现两种弧度，同屏时一眼能看出来。
 *
 * 以前这里是 10dp（52dp 的 19%）：方图被磨成接近于 squircle，和参考图里的"方片"不是
 * 一回事。播放页的大封面（`CoverCarousel` 传 0dp）不受这个值影响。
 */
internal val CoverCornerRadius = 4.dp

/**
 * Album artwork with a deterministic gradient fallback. The fallback is derived
 * from the track id so the same song always renders the same colour, which keeps
 * the list visually stable while scrolling.
 *
 * The container is always filled; the caller decides its shape and how the artwork fits
 * into it — list rows hand in a 52dp square and fit the image whole, while the player
 * hands in a half-screen frame and fills it completely
 * ([androidx.compose.ui.layout.ContentScale.Crop]) pinned to the top, so the cover always
 * occupies exactly half the screen whatever its aspect ratio and the overflow is cropped
 * at the bottom, where the fade band sits anyway.
 */
@Composable
fun CoverArt(
    track: Track?,
    artworkRepository: ArtworkRepository,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CoverCornerRadius,
    iconSize: Dp = 22.dp,
    trimMargins: Boolean = false,
    contentScale: ContentScale = ContentScale.Crop,
    /**
     * 图在框内的落位。[contentScale] 留出余量、或者铺满后有多余部分要裁时才有意义：
     * 播放页传 [Alignment.TopCenter]，让裁切全部落在底部 —— 那里正好是淡出带，
     * 裁掉的画面对用户不可见；顶部 0 → 16% 是渐显带，任何空隙都会被看见。
     */
    contentAlignment: Alignment = Alignment.Center,
    backgroundColor: Color = Color(0x1A1B1713),
) {
    var bitmap by remember(track?.albumId, trimMargins) {
        mutableStateOf(
            // Cache is album-keyed; same-album tracks share one bitmap.
            //
            // The player cache matters to the cover carousel: both neighbouring
            // pages are decoded before the swipe, and the one that becomes current
            // must paint its real cover on the very first frame instead of a
            // placeholder.
            track?.let {
                if (trimMargins) artworkRepository.cachedPlayer(it.albumId)
                else artworkRepository.cached(it.albumId)
            },
        )
    }

    LaunchedEffect(track?.albumId, trimMargins) {
        val target = track ?: return@LaunchedEffect
        if (bitmap == null) {
            bitmap = if (trimMargins) {
                artworkRepository.loadPlayerArtwork(target)
            } else {
                artworkRepository.load(target)
            }
        }
    }

    val shape = RoundedCornerShape(cornerRadius)
    val image = bitmap

    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        // 占位渐变与真实封面之间做一次短淡入。缓存命中时 target 在同一帧
        // 就已是新图，Crossfade 不会额外插入一帧占位图。
        Crossfade(
            targetState = image,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(MilanMotion.MediumMs, easing = MilanMotion.Standard),
            label = "cover-art",
        ) { shown ->
            if (shown != null) {
                Image(
                    bitmap = shown,
                    contentDescription = null,
                    contentScale = contentScale,
                    alignment = contentAlignment,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(placeholderBrush(track?.id ?: 0L)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        }
    }
}

private fun placeholderBrush(seed: Long): Brush {
    val palette = listOf(
        Color(0xFF8C6A3F),
        Color(0xFF6E7C6B),
        Color(0xFF7A6A78),
        Color(0xFF8A5A4A),
        Color(0xFF5D6B7A),
        Color(0xFF6B6357),
    )
    val index = ((seed % palette.size + palette.size) % palette.size).toInt()
    val base = palette[index]
    return Brush.linearGradient(
        listOf(base, base.copy(alpha = 0.76f)),
    )
}
