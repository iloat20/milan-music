package com.milan.music.ui.player

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milan.music.data.media.ArtworkRepository
import com.milan.music.data.model.Track
import com.milan.music.data.userdata.UserData
import com.milan.music.di.AppContainer
import com.milan.music.ui.components.CoverArt
import com.milan.music.ui.theme.LikedRed
import com.milan.music.ui.util.formatDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

private val OnDark = Color(0xFFF6F1E8)
private val OnDarkMuted = Color(0xB3F6F1E8)
private val OnDarkFaint = Color(0x66F6F1E8)
private val FallbackTint = Color(0xFF241C15)

@Composable
fun PlayerScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val playerState by container.player.state.collectAsStateWithLifecycle()
    val progress by container.player.progress.collectAsStateWithLifecycle()
    val userData by container.userData.data
        .collectAsStateWithLifecycle(initialValue = UserData())
    val scope = rememberCoroutineScope()

    val track = playerState.currentTrack
    val isFavorite = track != null && userData.favorites.contains(track.id)

    val density = LocalDensity.current
    val dismissThreshold = with(density) { 110.dp.toPx() }

    var backdrop by remember(track?.id) { mutableStateOf<ImageBitmap?>(null) }
    var dominant by remember(track?.id) { mutableStateOf<Color?>(null) }

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

    // Cover page-flip: horizontal swipes and double-taps animate the cover out in
    // the swipe direction, switch tracks, then slide the new cover in from the far
    // side. `isCoverAnimating` guards against re-entry mid-flight.
    val coverOffsetX = remember { Animatable(0f) }
    val isCoverAnimating = remember { mutableStateOf(false) }

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
        // Background: blurred artwork tinted by its own dominant colour. Scaling a
        // square cover to a 9:19.5 screen would zoom it ~2.2x and crop away most of
        // the image, so the crisp cover stays 1:1 and only the backdrop stretches.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(gradientTop, coverBackground, gradientBottom))),
        )

        val backdropImage = backdrop
        if (backdropImage != null) {
            Image(
                bitmap = backdropImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(48.dp)
                    .alpha(0.55f),
            )
        }

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

        // Slide the cover out in the swipe direction, switch tracks, slide the new
        // one in from the opposite edge.
        val launchCoverSwitch: (Boolean) -> Unit = { toNext ->
            if (!isCoverAnimating.value) {
                isCoverAnimating.value = true
                scope.launch {
                    val dir = if (toNext) -1f else 1f
                    coverOffsetX.animateTo(
                        dir * coverSidePx,
                        tween(180, easing = FastOutSlowInEasing),
                    )
                    if (toNext) container.player.next() else container.player.previous()
                    coverOffsetX.snapTo(-dir * coverSidePx)
                    coverOffsetX.animateTo(
                        0f,
                        tween(240, easing = FastOutSlowInEasing),
                    )
                    isCoverAnimating.value = false
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 20.dp)
                .size(coverSide)
                .graphicsLayer { translationX = coverOffsetX.value }
                .pointerInput(Unit) {
                    // Cover follows the finger horizontally; on release past the
                    // threshold the page-flip completes, otherwise it springs back.
                    // Level with the vertical drag on the root: the gesture system
                    // routes each drag by its dominant direction.
                    val swipeThreshold = 80.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { },
                        onHorizontalDrag = { change, dragAmount ->
                            if (!isCoverAnimating.value) {
                                scope.launch { coverOffsetX.snapTo(coverOffsetX.value + dragAmount) }
                            }
                            change.consume()
                        },
                        onDragEnd = {
                            if (isCoverAnimating.value) return@detectHorizontalDragGestures
                            when {
                                coverOffsetX.value < -swipeThreshold -> launchCoverSwitch(true)
                                coverOffsetX.value > swipeThreshold -> launchCoverSwitch(false)
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
                    onDoubleClick = { launchCoverSwitch(true) },
                ),
        ) {
            CoverArt(
                track = track,
                artworkRepository = container.artwork,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 0.dp,
                iconSize = 72.dp,
                trimMargins = true,
                contentScale = ContentScale.Crop,
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track?.displayTitle ?: "未在播放",
                        style = MaterialTheme.typography.headlineMedium,
                        color = OnDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = track?.displayArtist ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = OnDarkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = {
                        val id = track?.id ?: return@IconButton
                        scope.launch { container.userData.toggleFavorite(id) }
                    },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = if (isFavorite) {
                            Icons.Rounded.Favorite
                        } else {
                            Icons.Rounded.FavoriteBorder
                        },
                        contentDescription = "喜欢",
                        tint = if (isFavorite) LikedRed else OnDark,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            AppleMusicProgress(
                positionMs = progress.positionMs,
                durationMs = progress.durationMs.takeIf { it > 0L }
                    ?: (track?.duration ?: 0L),
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
 */
@Composable
private fun AppleMusicProgress(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
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

            if (dragging) {
                drawCircle(
                    color = OnDark,
                    radius = height * 1.15f,
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

        IconButton(onClick = onPrevious, modifier = Modifier.size(68.dp)) {
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
                .clickable(onClick = onTogglePlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = OnDark,
                modifier = Modifier.size(62.dp),
            )
        }

        IconButton(onClick = onNext, modifier = Modifier.size(68.dp)) {
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
