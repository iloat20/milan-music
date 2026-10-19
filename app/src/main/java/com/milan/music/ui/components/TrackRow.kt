package com.milan.music.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.milan.music.data.media.ArtworkRepository
import com.milan.music.data.model.Track

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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (isCurrent) highlight.copy(alpha = 0.10f) else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            track = track,
            artworkRepository = artworkRepository,
            modifier = Modifier.size(52.dp),
            cornerRadius = 5.dp,
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) highlight else MaterialTheme.colorScheme.onBackground,
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

        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(34.dp)) {
            Icon(
                imageVector = if (isFavorite) {
                    Icons.Rounded.Favorite
                } else {
                    Icons.Rounded.FavoriteBorder
                },
                contentDescription = if (isFavorite) "取消喜欢" else "喜欢",
                tint = if (isFavorite) {
                    com.milan.music.ui.theme.LikedRed
                } else {
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                },
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
fun PlayingBars(
    color: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 3,
) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "playing-bars")
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(barCount) { index ->
            val scale by transition.animateFloat(
                initialValue = 0.30f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(
                        durationMillis = 380 + index * 110,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing,
                    ),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
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
