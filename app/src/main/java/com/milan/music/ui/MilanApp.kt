package com.milan.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.milan.music.di.AppContainer
import com.milan.music.ui.library.HomeScreen
import com.milan.music.ui.player.PlayerScreen
import com.milan.music.ui.player.PlayerViewModel
import com.milan.music.ui.theme.MilanTheme

/**
 * 播放页下滑时露出的底场。
 *
 * 首页会随下滑缩放并起圆角，四周必须有东西可露 —— 没有这一层，缩小后露出的是窗口
 * 默认底色（深色主题下纯黑，浅色主题下刺眼的纸白）。取项目深色背景的中段色，
 * 于是"卡片浮在暗场上"这个层级关系在两个主题下都成立。
 */
private val SheetBackdrop = Color(0xFF130E05)

/**
 * Player as an overlay, not a navigation destination: HomeScreen stays composed
 * underneath, so the swipe-down gesture reveals it directly — a real "sheet over
 * page" transition that Navigation-Compose transitions cannot do (the previous
 * destination is not composed during a pop).
 *
 * 第 71 批：**入场动画搬进了 [PlayerScreen]**。它需要以迷你播放器卡片的实际矩形为
 * 起点生长（iOS 27 口径），而 `AnimatedVisibility` 只能表达"整页从屏幕外平移"，
 * 拿不到那个起点。这里只保留挂载/卸载，两个 transition 都是 None。
 */
@Composable
fun MilanApp(container: AppContainer, playRequest: Long = 0L) {
    var playerOpen by remember { mutableStateOf(false) }
    // 容器只在这里出现一次：两个 ViewModel 从这里取依赖，UI 组件只收到状态和回调。
    val playerViewModel: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(container))

    /**
     * 迷你播放器卡片的窗口矩形，播放入场的生长起点。
     *
     * `null` 是合法状态（队列还没就绪、卡片正在退场）：此时入场退回"淡入 + 轻微放大"，
     * 不阻断操作。绝不为了动画把播放页压住不让进。
     */
    var miniBounds by remember { mutableStateOf<Rect?>(null) }

    /**
     * 播放页被拖动的进度（0 盖住 / 1 退出），由 [PlayerScreen] 每帧写入。
     *
     * 用 Float state 而不是组合参数：首页把它读在 `graphicsLayer` 里，于是每帧只标脏
     * 一个图层，不触发长列表重组。
     */
    val dragProgress = remember { mutableFloatStateOf(0f) }

    MilanTheme {
        Box(Modifier.fillMaxSize().background(SheetBackdrop)) {
            HomeScreen(
                container = container,
                onOpenPlayer = { playerOpen = true },
                isCovered = playerOpen,
                playRequest = playRequest,
                onMiniPlayerBounds = { miniBounds = it },
                coverProgress = { dragProgress.floatValue },
            )

            AnimatedVisibility(
                visible = playerOpen,
                enter = EnterTransition.None,
                // PlayerScreen 内的手势已经把页面滑出屏幕，这里再播一次会重复走位。
                exit = ExitTransition.None,
            ) {
                PlayerScreen(
                    viewModel = playerViewModel,
                    // 只有封面轮播需要这两个：它自己加载封面，并等会话确认换曲后落位。
                    player = container.player,
                    artwork = container.artwork,
                    origin = miniBounds,
                    onDragProgress = { dragProgress.floatValue = it },
                    onBack = {
                        dragProgress.floatValue = 0f
                        playerOpen = false
                    },
                )
            }
        }
    }
}
