package com.milan.music.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
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

/**
 * Album artwork with a deterministic gradient fallback. The fallback is derived
 * from the track id so the same song always renders the same colour, which keeps
 * the list visually stable while scrolling.
 */
@Composable
fun CoverArt(
    track: Track?,
    artworkRepository: ArtworkRepository,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
    iconSize: Dp = 22.dp,
    adaptiveRatio: Boolean = false,
    trimMargins: Boolean = false,
    contentScale: ContentScale = ContentScale.Crop,
    backgroundColor: Color = Color(0x1A1B1713),
) {
    var bitmap by remember(track?.id, trimMargins) {
        mutableStateOf(
            // Cache is album-keyed; same-album tracks share one bitmap.
            if (trimMargins) null else track?.let { artworkRepository.cached(it.albumId) },
        )
    }

    LaunchedEffect(track?.id, trimMargins) {
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

    // When the artwork is wide the square container leaves bands above and below.
    // Deriving the container ratio from the bitmap removes the bands entirely;
    // the clamp only trims the extremes (panoramas / very tall scans).
    val ratio = if (adaptiveRatio && image != null) {
        (image.width.toFloat() / image.height.toFloat()).coerceIn(0.72f, 1.55f)
    } else {
        1f
    }

    val sized = if (adaptiveRatio) modifier.aspectRatio(ratio) else modifier

    Box(
        modifier = sized
            .clip(shape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = contentScale,
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
