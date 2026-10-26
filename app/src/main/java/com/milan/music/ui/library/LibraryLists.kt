package com.milan.music.ui.library

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.milan.music.BuildConfig
import com.milan.music.R
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
    showBuildStamp: Boolean = false,
) {
    if (tracks.isEmpty()) {
        // A first launch paints an empty library for as long as the MediaStore scan
        // takes; claiming "no songs" during that window is simply wrong.
        if (isLoading) {
            EmptyState(
                icon = Icons.Rounded.LibraryMusic,
                title = stringResource(R.string.library_scanning_title),
                message = stringResource(R.string.library_scanning_body),
                modifier = modifier,
            )
        } else {
            EmptyState(
                icon = Icons.Rounded.LibraryMusic,
                title = stringResource(R.string.library_empty_title),
                message = stringResource(R.string.library_empty_body),
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
                // 落位走 Settle（不许过冲）：冲过目标会和相邻行重叠一帧。
                // 只对可见项生效，长列表没有额外成本。
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(MilanMotion.MediumMs),
                    placementSpec = MilanMotion.SettleOffset,
                    fadeOutSpec = tween(MilanMotion.FastMs),
                ),
            )
        }
        // 只在曲库页出现。两个页签共用这一个列表，不设开关的话喜欢页也会滚出一行
        // 版本号 —— 那是调试信息，不该出现在"我喜欢的东西"列表底部。
        if (showBuildStamp) {
            item(key = "build-stamp") {
                BuildStamp()
            }
        }
    }
}

/**
 * 列表末尾的版本戳，`1.0.63 (64) · RELEASE`。
 *
 * 存在的唯一理由是可辨认：这个工程每批都要打测试包，debug 包带 `.debug` 后缀、装出来是
 * 第二个图标，历史上为了确认"装的到底是哪个包"耗掉过整轮排查。放在列表末尾而不是顶栏，
 * 是为了不进首屏构图 —— 滚到底才出现；并且**只挂在曲库页**（`showBuildStamp`），
 * 喜欢页不显示。
 */
@Composable
private fun BuildStamp() {
    Text(
        text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
            if (BuildConfig.DEBUG) "DEBUG" else "RELEASE",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 6.dp),
    )
}
