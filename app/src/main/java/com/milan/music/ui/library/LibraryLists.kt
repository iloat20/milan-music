package com.milan.music.ui.library

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.milan.music.data.media.ArtworkRepository
import com.milan.music.data.model.Track
import com.milan.music.ui.components.EmptyState
import com.milan.music.ui.components.TrackRow
import com.milan.music.ui.theme.MilanMotion

@Composable
fun SongsList(
    tracks: List<Track>,
    artworkRepository: ArtworkRepository,
    currentTrackId: Long?,
    isPlaying: Boolean,
    favoriteIds: Set<Long>,
    onPlay: (List<Track>, Int) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
) {
    if (tracks.isEmpty()) {
        // A first launch paints an empty library for as long as the MediaStore scan
        // takes; claiming "no songs" during that window is simply wrong.
        if (isLoading) {
            EmptyState(
                icon = Icons.Rounded.LibraryMusic,
                title = "正在扫描音乐库",
                message = "首次启动需要读取设备上的音频文件，请稍候。",
                modifier = modifier,
            )
        } else {
            EmptyState(
                icon = Icons.Rounded.LibraryMusic,
                title = "这里还没有歌曲",
                message = "把音频文件放到手机的 Music 目录，或稍后重新扫描音乐库。",
                modifier = modifier,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            TrackRow(
                track = track,
                artworkRepository = artworkRepository,
                isCurrent = track.id == currentTrackId,
                isPlaying = isPlaying,
                isFavorite = track.id in favoriteIds,
                onClick = { onPlay(tracks, index) },
                onToggleFavorite = { onToggleFavorite(track.id) },
                // 搜索过滤或增删歌曲时，落位用弹簧、出现/消失用短淡入淡出。
                // 只对可见项生效，长列表没有额外成本。
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(MilanMotion.MediumMs),
                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    fadeOutSpec = tween(MilanMotion.FastMs),
                ),
            )
        }
    }
}
