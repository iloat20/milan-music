package com.milan.music.ui.player

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milan.music.data.media.ArtworkRepository
import com.milan.music.data.model.Track
import com.milan.music.data.userdata.UserData
import com.milan.music.di.AppContainer
import com.milan.music.play.PlaybackProgress
import com.milan.music.ui.components.AnimatedPlayPauseIcon
import com.milan.music.ui.components.CoverArt
import com.milan.music.ui.theme.LikedRed
import com.milan.music.ui.theme.MilanMotion
import com.milan.music.ui.util.formatDuration
import com.milan.music.ui.util.noRippleClickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

private val OnDark = Color(0xFFF6F1E8)
private val OnDarkMuted = Color(0xB3F6F1E8)
private val OnDarkFaint = Color(0x66F6F1E8)
private val FallbackTint = Color(0xFF241C15)

/** Fraction of the cover a swipe may expose when there is no neighbour on that side. */
private const val EDGE_RESISTANCE = 0.16f

/** How long a committed swipe waits for the session to confirm the new track. */
private const val SWITCH_TIMEOUT_MS = 600L

/**
 * The three covers mounted around the current track, in playback order.
 *
 * Kept as local state by the player screen instead of reading [com.milan.music.play.PlayerUiState]
 * directly: a committed swipe shifts the row and resets its offset in one frame,
 * which is only pixel-continuous if the position and the pages change together.
 */
private data class CoverPages(
    val previous: Track? = null,
    val current: Track? = null,
    val next: Track? = null,
)

@Composable
fun PlayerScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val playerState by container.player.state.collectAsStateWithLifecycle()
    val userData by container.userData.data
        .collectAsStateWithLifecycle(initialValue = UserData())
    val scope = rememberCoroutineScope()

    val track = playerState.currentTrack
    val isFavorite = track != null && userData.favorites.contains(track.id)

    val density = LocalDensity.current
    val dismissThreshold = with(density) { 110.dp.toPx() }

    // 不按 track.id 重置：新封面解码完成前继续显示上一张，
    // 否则每次切歌都会先闪一下纯色底。解码成功后 Crossfade 再接管。
    var backdrop by remember { mutableStateOf<ImageBitmap?>(null) }
    var dominant by remember { mutableStateOf<Color?>(null) }

    LaunchedEffect(track?.id) {
        val target = track ?: return@LaunchedEffect
        val small = container.artwork.loadBackdrop(target)
        backdrop = small
        dominant = small?.let { image ->
            withContext(Dispatchers.Default) { dominantColorOf(image) }
        }
    }

    val coverBackground = dominant?.let { blend(it, Color.Black, 0.38f) } ?: FallbackTint
    val gradientTop = dominant?.let { blend(it, Color.White, 0.20f) } ?: FallbackTint
    val gradientBottom = dominant?.let { blend(it, Color.Black, 0.62f) } ?: Color(0xFF0B0A09)

    // The cover fills the whole screen, so the system bars sit on arbitrary artwork.
    // Force light system icons for as long as this screen is visible.
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    DisposableEffect(window) {
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val previousStatus = controller.isAppearanceLightStatusBars
            val previousNav = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            onDispose {
                controller.isAppearanceLightStatusBars = previousStatus
                controller.isAppearanceLightNavigationBars = previousNav
            }
        }
    }

    // Interactive swipe-down: the sheet follows the finger while the home screen
    // shows through underneath. Past the threshold it animates the rest of the way
    // off-screen before popping; below it springs back.
    val offsetY = remember { Animatable(0f) }
    val isSettling = remember { mutableStateOf(false) }

    // 页面自下而上滑入的同时，封面做一次很轻的 0.94→1 落位。
    // 不是共享元素，只是给进场一个视觉重心，幅度刻意控制在几乎察觉不到的程度。
    val coverEntrance = remember { Animatable(0.94f) }
    LaunchedEffect(Unit) {
        coverEntrance.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.82f,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }

    // Cover carousel: the previous and next artwork are mounted one page to
    // either side of the current one. Both are decoded while the player is open,
    // so a drag reveals the real cover instead of the blurred backdrop.
    //
    // `coverPages` is a local snapshot rather than a direct read of `playerState`:
    // a committed swipe has to shift the row and reset the offset in the same
    // frame, while the session still reports the outgoing track for a few
    // milliseconds after the seek.
    var coverPages by remember {
        mutableStateOf(
            CoverPages(
                previous = playerState.previousTrack,
                current = playerState.currentTrack,
                next = playerState.nextTrack,
            ),
        )
    }
    val coverOffsetX = remember { Animatable(0f) }
    val isCoverAnimating = remember { mutableStateOf(false) }

    // Track changes the gesture did not initiate: song end, notification,
    // Bluetooth, widget. Swipes update `coverPages` themselves and wait for the
    // session, so their transient state must not overwrite the shifted row.
    LaunchedEffect(playerState.previousTrack, playerState.currentTrack, playerState.nextTrack) {
        if (!isCoverAnimating.value) {
            coverPages = CoverPages(
                previous = playerState.previousTrack,
                current = playerState.currentTrack,
                next = playerState.nextTrack,
            )
        }
    }

    // Measured height of the sheet, published by BoxWithConstraints below. Needed
    // because the dismissal can start from outside that scope (system back).
    val sheetHeightPx = remember { mutableFloatStateOf(0f) }

    // Single dismissal path for the swipe gesture and the system back key: the sheet
    // slides fully off-screen and only then is the overlay removed.
    // AnimatedVisibility declares no exit transition (the gesture has already moved
    // the sheet by then), so removing the overlay first would make the player vanish
    // in a single frame.
    val dismissToHome: () -> Unit = {
        if (!isSettling.value) {
            isSettling.value = true
            scope.launch {
                // Height is published by the layout; if it is still unknown the
                // overlay is simply removed rather than animating to zero.
                val target = sheetHeightPx.floatValue
                if (target > 0f) {
                    offsetY.animateTo(target, tween(240, easing = FastOutSlowInEasing))
                }
                onBack()
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val progress = if (size.height > 0f) {
                    (offsetY.value / size.height).coerceIn(0f, 1f)
                } else {
                    0f
                }
                translationY = offsetY.value
                alpha = 1f - 0.25f * progress
                val shrink = 1f - 0.06f * progress
                scaleX = shrink
                scaleY = shrink
            }
            // Must sit INSIDE graphicsLayer: painted before it, the backdrop would
            // stay put while the sheet slides away, hiding the home screen.
            .background(FallbackTint)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { },
                    onVerticalDrag = { change, dragAmount ->
                        if (!isSettling.value) {
                            scope.launch {
                                offsetY.snapTo((offsetY.value + dragAmount).coerceAtLeast(0f))
                            }
                        }
                        change.consume()
                    },
                    onDragEnd = {
                        if (isSettling.value) return@detectVerticalDragGestures
                        if (offsetY.value > dismissThreshold) {
                            dismissToHome()
                        } else {
                            scope.launch {
                                offsetY.animateTo(
                                    0f,
                                    spring(stiffness = Spring.StiffnessMediumLow),
                                )
                            }
                        }
                    },
                    onDragCancel = {
                        if (!isSettling.value) {
                            scope.launch {
                                offsetY.animateTo(
                                    0f,
                                    spring(stiffness = Spring.StiffnessMediumLow),
                                )
                            }
                        }
                    },
                )
            },
    ) {
        // Publish the measured sheet height for the dismissal above.
        SideEffect { sheetHeightPx.floatValue = constraints.maxHeight.toFloat() }

        // System back mirrors the swipe-down dismiss so the user can never be
        // stranded on a screen whose only exit is a gesture.
        //
        // Predictive back (API 34+) feeds the same `offsetY` the touch gesture
        // drives, so the sheet follows the system's back preview instead of
        // jumping. The guard is on the progress value itself, not on the SDK
        // level: platforms that do not preview the gesture report a single 1.0
        // event, and snapping to that would make the player vanish in one frame
        // instead of running the 240 ms slide-out.
        //
        // The handler stays registered for the whole dismissal: `enabled` is
        // deliberately *not* tied to `isSettling`. Removing it while the sheet
        // slides out would let a second back press (typical double-press interval
        // is 150-250 ms, same order as the 240 ms animation) fall through to the
        // activity's default handler and finish the app. The re-entry guard
        // belongs inside the callback instead.
        PredictiveBackHandler { progress ->
            if (isSettling.value) return@PredictiveBackHandler
            try {
                progress.collect { event ->
                    val isIntermediate = event.progress > 0f && event.progress < 1f
                    if (isIntermediate) {
                        offsetY.snapTo(event.progress * sheetHeightPx.floatValue)
                    }
                }
            } catch (cancellation: CancellationException) {
                // The gesture was released before committing: put the sheet back.
                offsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                throw cancellation
            }
            dismissToHome()
        }

        // Square cover sized to the width, but capped so it never collides with the
        // controls on short screens.
        val coverSide = minOf(maxWidth, maxHeight * 0.52f)
        // 背景：模糊封面叠在由封面主色生成的渐变上。方形封面若拉伸到
        // 9:19.5 的屏幕会被放大 ~2.2 倍并裁掉大半，所以清晰封面保持 1:1，
        // 只让背景层铺满。颜色与图片各自做慢速交叉淡入，切歌不闪白/闪黑。
        PlayerBackdrop(
            top = gradientTop,
            middle = coverBackground,
            bottom = gradientBottom,
            backdrop = backdrop,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.28f),
                        0.42f to Color.Black.copy(alpha = 0.04f),
                        0.70f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Black.copy(alpha = 0.90f),
                    ),
                ),
        )

        val coverSidePx = with(density) { coverSide.toPx() }

        // Past the threshold the row finishes the slide, the player changes track,
        // and the offset resets only once the session confirms the incoming track.
        // The cover under the finger then becomes the current page pixel for pixel:
        // no blank gap, no second slide-in.
        val launchCoverSwitch: (Boolean) -> Unit = { toNext ->
            if (!isCoverAnimating.value) {
                val incoming = if (toNext) coverPages.next else coverPages.previous
                if (incoming != null) {
                    isCoverAnimating.value = true
                    scope.launch {
                        val target = if (toNext) -coverSidePx else coverSidePx
                        coverOffsetX.animateTo(
                            target,
                            tween(160, easing = FastOutSlowInEasing),
                        )

                        // The transport's previous() restarts the current song when
                        // it has been playing for more than 3 s. A swipe revealed the
                        // previous cover, so it must move to that track instead.
                        if (toNext) container.player.next() else container.player.skipToPrevious()

                        // The controller is asynchronous. Hold the row on the
                        // incoming page until the session actually reports it, then
                        // adopt its neighbours and reset the offset together.
                        val switched = withTimeoutOrNull(SWITCH_TIMEOUT_MS) {
                            container.player.state.first { it.currentTrack?.id == incoming.id }
                        } != null
                        if (switched) {
                            val latest = container.player.state.value
                            coverPages = CoverPages(
                                previous = latest.previousTrack,
                                current = latest.currentTrack,
                                next = latest.nextTrack,
                            )
                            coverOffsetX.snapTo(0f)
                        } else {
                            // The seek never landed (session gone): put the row
                            // back rather than showing a track that is not playing.
                            coverOffsetX.animateTo(
                                0f,
                                spring(stiffness = Spring.StiffnessMediumLow),
                            )
                        }
                        isCoverAnimating.value = false
                    }
                }
            }
        }

        // Read inside the gesture detector, never as pointerInput keys: a track
        // change during a drag updates the reveal target without cancelling the
        // gesture in progress.
        val latestCoverPages by rememberUpdatedState(coverPages)
        val latestLaunchCoverSwitch by rememberUpdatedState(launchCoverSwitch)

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 20.dp)
                .size(coverSide)
                .graphicsLayer {
                    scaleX = coverEntrance.value
                    scaleY = coverEntrance.value
                }
                // The neighbouring pages sit outside this square at rest; clip
                // them so they only enter through the edges while dragging.
                .clipToBounds()
                .pointerInput(coverSidePx) {
                    // Cover follows the finger horizontally; on release past the
                    // threshold the carousel finishes the page, otherwise it springs
                    // back. Level with the vertical drag on the root: the gesture
                    // system routes each drag by its dominant direction.
                    //
                    // Pages and the switch action are read through
                    // rememberUpdatedState so a track change during the gesture
                    // updates what the next frame reveals without tearing down the
                    // drag detector (the pointerInput key is only the page width).
                    val swipeThreshold = 80.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { },
                        onHorizontalDrag = { change, dragAmount ->
                            if (!isCoverAnimating.value) {
                                // A missing neighbour (single-track queue) may only
                                // stretch a little, never far enough to commit.
                                val min = if (latestCoverPages.next != null) {
                                    -coverSidePx
                                } else {
                                    -coverSidePx * EDGE_RESISTANCE
                                }
                                val max = if (latestCoverPages.previous != null) {
                                    coverSidePx
                                } else {
                                    coverSidePx * EDGE_RESISTANCE
                                }
                                val target = (coverOffsetX.value + dragAmount)
                                    .coerceIn(min, max)
                                scope.launch { coverOffsetX.snapTo(target) }
                            }
                            change.consume()
                        },
                        onDragEnd = {
                            if (isCoverAnimating.value) {
                                return@detectHorizontalDragGestures
                            }
                            when {
                                coverOffsetX.value < -swipeThreshold &&
                                    latestCoverPages.next != null -> latestLaunchCoverSwitch(true)
                                coverOffsetX.value > swipeThreshold &&
                                    latestCoverPages.previous != null -> latestLaunchCoverSwitch(false)
                                else -> scope.launch {
                                    coverOffsetX.animateTo(
                                        0f,
                                        spring(stiffness = Spring.StiffnessMediumLow),
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            if (!isCoverAnimating.value) {
                                scope.launch {
                                    coverOffsetX.animateTo(
                                        0f,
                                        spring(stiffness = Spring.StiffnessMediumLow),
                                    )
                                }
                            }
                        },
                    )
                }
                .combinedClickable(
                    onClick = { },
                    onDoubleClick = { latestLaunchCoverSwitch(true) },
                ),
        ) {
            // Both neighbours stay composed for the whole session so their
            // artwork is decoded before the finger moves. Draw order is left,
            // current, right; pure translation keeps them from overlapping.
            CoverSlot(
                track = coverPages.previous,
                slot = -1,
                coverSidePx = coverSidePx,
                offsetX = coverOffsetX,
                artworkRepository = container.artwork,
                backgroundColor = coverBackground,
            )
            CoverSlot(
                track = coverPages.current,
                slot = 0,
                coverSidePx = coverSidePx,
                offsetX = coverOffsetX,
                artworkRepository = container.artwork,
                backgroundColor = coverBackground,
            )
            CoverSlot(
                track = coverPages.next,
                slot = 1,
                coverSidePx = coverSidePx,
                offsetX = coverOffsetX,
                artworkRepository = container.artwork,
                backgroundColor = coverBackground,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(bottom = 100.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 标题/艺人整块做上下交接：新内容从下方少许进入，旧内容向上退出。
                // 只在曲目 id 变化时触发，播放进度与收藏状态不会打断文字。
                AnimatedContent(
                    targetState = track,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        val enter = slideInVertically(tween(MilanMotion.MediumMs)) { it / 3 } +
                            fadeIn(tween(MilanMotion.MediumMs))
                        val exit = slideOutVertically(tween(MilanMotion.FastMs)) { -it / 3 } +
                            fadeOut(tween(MilanMotion.FastMs))
                        enter togetherWith exit
                    },
                    contentAlignment = Alignment.CenterStart,
                    contentKey = { it?.id },
                    label = "player-track-text",
                ) { item ->
                    Column {
                        Text(
                            text = item?.displayTitle ?: "未在播放",
                            style = MaterialTheme.typography.headlineMedium,
                            color = OnDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = item?.displayArtist ?: "",
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnDarkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 与列表里的收藏按钮保持一致：不用 IconButton，
                // 避免按压时在封面上铺出一块圆形底色。
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .noRippleClickable(
                            role = Role.Button,
                            onClick = {
                                val id = track?.id ?: return@noRippleClickable
                                scope.launch { container.userData.toggleFavorite(id) }
                            },
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
                        label = "player-favorite",
                    ) { liked ->
                        Icon(
                            imageVector = if (liked) {
                                Icons.Rounded.Favorite
                            } else {
                                Icons.Rounded.FavoriteBorder
                            },
                            contentDescription = if (liked) "取消喜欢" else "喜欢",
                            tint = if (liked) LikedRed else OnDark,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            AppleMusicProgress(
                progress = container.player.progress,
                fallbackDurationMs = track?.duration ?: 0L,
                onSeek = container.player::seekTo,
            )

            Spacer(Modifier.height(10.dp))

            PlaybackControls(
                isPlaying = playerState.isPlaying,
                onTogglePlay = container.player::togglePlayPause,
                onPrevious = container.player::previous,
                onNext = container.player::next,
            )
        }
    }
}

/**
 * 播放页背景：主色渐变 + 模糊封面。
 *
 * 把颜色动画收在这个小 composable 里，切歌时只重绘背景层，
 * 不会让标题、进度条、封面轮播跟着每帧重组。
 */
@Composable
private fun PlayerBackdrop(
    top: Color,
    middle: Color,
    bottom: Color,
    backdrop: ImageBitmap?,
) {
    val animatedTop by animateColorAsState(
        targetValue = top,
        animationSpec = tween(MilanMotion.AmbientMs, easing = MilanMotion.Standard),
        label = "backdrop-top",
    )
    val animatedMiddle by animateColorAsState(
        targetValue = middle,
        animationSpec = tween(MilanMotion.AmbientMs, easing = MilanMotion.Standard),
        label = "backdrop-middle",
    )
    val animatedBottom by animateColorAsState(
        targetValue = bottom,
        animationSpec = tween(MilanMotion.AmbientMs, easing = MilanMotion.Standard),
        label = "backdrop-bottom",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(animatedTop, animatedMiddle, animatedBottom)),
            ),
    ) {
        Crossfade(
            targetState = backdrop,
            modifier = Modifier
                .fillMaxSize()
                // 模糊放在 Crossfade 外层：过渡期间只做一次全屏模糊，
                // 而不是新旧两张图各模糊一次。
                .blur(48.dp)
                .alpha(0.55f),
            animationSpec = tween(MilanMotion.AmbientMs, easing = MilanMotion.Standard),
            label = "player-backdrop-image",
        ) { image ->
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * One page of the three-cover carousel.
 *
 * The page is laid out at the centre of the clipping box and translated by its
 * slot position plus the drag offset. Reading the offset inside `graphicsLayer`
 * keeps the drag out of recomposition: only the layer's translation changes per
 * pointer event.
 *
 * `null` neighbours are omitted entirely (a one-track queue has none); the
 * current slot still renders [CoverArt]'s deterministic placeholder when nothing
 * is queued, matching the previous single-cover behaviour.
 */
@Composable
private fun CoverSlot(
    track: Track?,
    slot: Int,
    coverSidePx: Float,
    offsetX: Animatable<Float, AnimationVector1D>,
    artworkRepository: ArtworkRepository,
    backgroundColor: Color,
) {
    if (track == null && slot != 0) return

    // 轮播落位要求"手指下的那张图"在同一帧变成当前页，不能做 Crossfade。
    // 以 track.id 重建 CoverArt 的淡入状态：同曲目内的占位图→封面仍会淡入，
    // 但切到另一首歌时直接以缓存封面起画。
    key(track?.id) {
        CoverArt(
            track = track,
            artworkRepository = artworkRepository,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = slot * coverSidePx + offsetX.value
                },
            cornerRadius = 0.dp,
            iconSize = 72.dp,
            trimMargins = true,
            contentScale = ContentScale.Crop,
            backgroundColor = backgroundColor,
        )
    }
}

/** Most frequent saturated-ish colour, ignoring near-black and near-white pixels. */
private fun dominantColorOf(image: ImageBitmap): Color {
    val source = image.asAndroidBitmap()
    // getPixels() throws on a HARDWARE bitmap, and `loadThumbnail` may return one.
    // The copy only happens on that path, and the backdrop is 96 px.
    val bitmap = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false) ?: return FallbackTint
    } else {
        source
    }
    val histogram = IntArray(16 * 16 * 16)

    val stepX = max(1, bitmap.width / 32)
    val stepY = max(1, bitmap.height / 32)

    // One bulk JNI copy instead of a getPixel call per sample point.
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            val color = pixels[y * bitmap.width + x]
            val r = ((color shr 16) and 0xFF) shr 4
            val g = ((color shr 8) and 0xFF) shr 4
            val b = (color and 0xFF) shr 4
            val luma = (r * 3 + g * 6 + b) / 10
            if (luma in 2..13) {
                histogram[r * 256 + g * 16 + b]++
            }
            x += stepX
        }
        y += stepY
    }

    var bestIndex = -1
    var bestCount = 0
    for (index in histogram.indices) {
        if (histogram[index] > bestCount) {
            bestCount = histogram[index]
            bestIndex = index
        }
    }
    if (bestIndex < 0) return FallbackTint

    val r = ((bestIndex / 256) shl 4) or 8
    val g = (((bestIndex / 16) % 16) shl 4) or 8
    val b = ((bestIndex % 16) shl 4) or 8
    return Color(r, g, b)
}

private fun blend(base: Color, target: Color, amount: Float): Color = Color(
    red = base.red + (target.red - base.red) * amount,
    green = base.green + (target.green - base.green) * amount,
    blue = base.blue + (target.blue - base.blue) * amount,
    alpha = 1f,
)

/**
 * Apple Music style progress: a hairline that thickens while dragging, no
 * permanent thumb, tap anywhere to seek.
 *
 * This is the only composable that collects [progress]. Reading the flow in
 * [PlayerScreen] made every 500 ms tick recompose the whole sheet — cover,
 * backdrop, title and controls included — even though only the bar and the two
 * time labels change. Collecting in the smallest scope keeps that invalidation
 * local.
 *
 * `derivedStateOf` is deliberately not used as a throttle: it only suppresses
 * recomposition when the *derived value* is equal. A progress bar's ratio changes
 * on every tick, so a derived ratio would still change; moving the read down is
 * what actually prevents the parent from recomposing.
 */
@Composable
private fun AppleMusicProgress(
    progress: StateFlow<PlaybackProgress>,
    fallbackDurationMs: Long,
    onSeek: (Long) -> Unit,
) {
    val playbackProgress by progress.collectAsStateWithLifecycle()
    val positionMs = playbackProgress.positionMs
    val durationMs = playbackProgress.durationMs.takeIf { it > 0L } ?: fallbackDurationMs

    var dragging by remember { mutableStateOf(false) }
    var dragRatio by remember { mutableFloatStateOf(0f) }

    val realRatio = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shownRatio = if (dragging) dragRatio else realRatio

    val barHeight by animateDpAsState(
        targetValue = if (dragging) 6.dp else 3.dp,
        animationSpec = tween(160),
        label = "progress-bar-height",
    )
    // 松手后拖动圆点不是瞬间消失，而是跟着进度条一起收细。
    val thumbScale by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = tween(MilanMotion.FastMs, easing = MilanMotion.Standard),
        label = "progress-thumb",
    )

    val displayPosition = if (dragging) (dragRatio * durationMs).toLong() else positionMs

    Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragRatio = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            if (durationMs > 0L) {
                                onSeek((dragRatio * durationMs).toLong())
                            }
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, _ ->
                            dragRatio = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            change.consume()
                        },
                    )
                }
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        if (durationMs > 0L) {
                            onSeek((offset.x / size.width.toFloat() * durationMs).toLong())
                        }
                    }
                },
        ) {
            val height = barHeight.toPx()
            val centerY = size.height / 2f
            val corner = CornerRadius(height / 2f, height / 2f)

            drawRoundRect(
                color = OnDark.copy(alpha = 0.28f),
                topLeft = Offset(0f, centerY - height / 2f),
                size = Size(size.width, height),
                cornerRadius = corner,
            )

            val playedWidth = size.width * shownRatio
            drawRoundRect(
                color = OnDark,
                topLeft = Offset(0f, centerY - height / 2f),
                size = Size(playedWidth, height),
                cornerRadius = corner,
            )

            if (thumbScale > 0f) {
                drawCircle(
                    color = OnDark,
                    radius = height * 1.15f * thumbScale,
                    center = Offset(playedWidth, centerY),
                )
            }
        }

        Spacer(Modifier.height(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(displayPosition),
                style = MaterialTheme.typography.labelSmall,
                color = OnDarkMuted,
            )
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = OnDarkMuted,
            )
        }
    }
}

@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(40.dp))

        // 深色播放页去掉涟漪，改用按压缩放：手指按下时控件轻微收紧，
        // 松手回弹，比在黑色背景上铺一层水波更干净。
        Box(
            modifier = Modifier
                .size(68.dp)
                .noRippleClickable(
                    role = Role.Button,
                    pressScale = 0.88f,
                    onClick = onPrevious,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = "上一首",
                tint = OnDark,
                modifier = Modifier.size(62.dp),
            )
        }

        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .noRippleClickable(
                    role = Role.Button,
                    pressScale = 0.90f,
                    onClick = onTogglePlay,
                ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedPlayPauseIcon(
                isPlaying = isPlaying,
                tint = OnDark,
                iconSize = 62.dp,
            )
        }

        Box(
            modifier = Modifier
                .size(68.dp)
                .noRippleClickable(
                    role = Role.Button,
                    pressScale = 0.88f,
                    onClick = onNext,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = "下一首",
                tint = OnDark,
                modifier = Modifier.size(62.dp),
            )
        }

        Spacer(Modifier.size(40.dp))
    }
}
