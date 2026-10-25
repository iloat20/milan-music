package com.milan.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.milan.music.di.AppContainer
import com.milan.music.ui.library.HomeScreen
import com.milan.music.ui.player.PlayerScreen
import com.milan.music.ui.player.PlayerViewModel
import com.milan.music.ui.theme.MilanTheme

/**
 * Player as an overlay, not a navigation destination: HomeScreen stays composed
 * underneath, so the swipe-down gesture reveals it directly — a real "sheet over
 * page" transition that Navigation-Compose transitions cannot do (the previous
 * destination is not composed during a pop).
 */
@Composable
fun MilanApp(container: AppContainer, playRequest: Long = 0L) {
    var playerOpen by remember { mutableStateOf(false) }
    // 容器只在这里出现一次：两个 ViewModel 从这里取依赖，UI 组件只收到状态和回调。
    val playerViewModel: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(container))

    MilanTheme {
        Box(Modifier.fillMaxSize()) {
            HomeScreen(
                container = container,
                onOpenPlayer = { playerOpen = true },
                isCovered = playerOpen,
                playRequest = playRequest,
            )

            AnimatedVisibility(
                visible = playerOpen,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(320, easing = FastOutSlowInEasing),
                ),
                // The gesture-driven slide inside PlayerScreen has already
                // moved the sheet off-screen by the time onBack() fires.
                exit = ExitTransition.None,
            ) {
                PlayerScreen(
                    viewModel = playerViewModel,
                    // 只有封面轮播需要这两个：它自己加载封面，并等会话确认换曲后落位。
                    player = container.player,
                    artwork = container.artwork,
                    onBack = { playerOpen = false },
                )
            }
        }
    }
}
