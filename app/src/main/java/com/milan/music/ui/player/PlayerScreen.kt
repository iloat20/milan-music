package com.milan.music.ui.player

import android.app.Activity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import android.os.Build
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milan.music.R
import com.milan.music.data.media.ArtworkRepository
import com.milan.music.data.model.Track
import com.milan.music.play.PlaybackProgress
import com.milan.music.play.PlayerController
import com.milan.music.ui.components.AnimatedPlayPauseIcon
import com.milan.music.ui.components.FavoriteButton
import com.milan.music.ui.text.artistText
import com.milan.music.ui.text.titleText
import com.milan.music.ui.theme.MilanMotion
import com.milan.music.ui.util.formatDuration
import com.milan.music.ui.util.formatPosition
import com.milan.music.ui.util.noRippleClickable
import com.milan.music.ui.util.rememberDragOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

private val OnDark = Color(0xFFF6F1E8)
private val OnDarkMuted = Color(0xB3F6F1E8)
private val FallbackTint = Color(0xFF241C15)

/**
 * Height the information area needs under the hero: title, progress bar, controls.
 *
 * 300dp，不是 280dp：`FieldTopPadding` 从 30dp 涨到 59dp 之后，信息区自身的内在高度
 * （实测截图 ≈ 247dp → 277dp）离 280 只剩 3dp，短窗口里再有点字号放大就会把控制
 * 挤出屏幕。留 23dp 余量。
 *
 * Portrait never reaches this floor. It exists so a short window (640dp tall is still
 * common) shortens the cover instead of clipping the controls.
 */
private val MinTransportHeight = 300.dp

/**
 * 背景模糊层的半径与不透明度。
 *
 * 半径必须大到抹掉封面下缘的一切细节（版权字、边缘暗角）：源图是 512 px 宽、只取最下
 * 20%（约 100 px 高），纵向放大到整页是十余倍，72dp 之后剩下的只有明暗起伏 —— 那正是
 * "封面化成了背景"而不是"背景上贴了一张放大到失真的图"。
 *
 * 不透明度 0.40：底色是渐变层，这层只提供纹理。再高就会把镜面线以下那一段压成一整块
 * 位图色，渐变也就白做了。
 */
private val BackdropBlur = 72.dp
private const val BackdropTextureAlpha = 0.40f

/** 信息区（标题、进度、控制）的左右边距。 */
private val PlayerSidePadding = 24.dp

/**
 * 进度条两端的位置：**左端与歌曲名对齐，右端与收藏图标对齐**，所以两根边距不一样长。
 *
 * 右端不是 [PlayerSidePadding]：收藏图标是 24dp 的图形装在 48dp 的触摸盒里、盒再靠右
 * 边距收进来，所以图标的右缘比盒子右缘更靠里。实测截图（873×1920，≈2.221 px/dp）里心形
 * 右缘距屏幕右边 93px ≈ **42dp**，盒子的右缘才是 24dp 那一条。用户要的是"从歌曲名到
 * 喜欢的图标的长度"，取图形右缘才是那根线；用 24dp 会让进度条探出去约 18dp。
 */
private val ProgressStartInset = PlayerSidePadding
private val ProgressEndInset = 42.dp

/**
 * 封面下缘与标题之间的距离：色场先裸一段，标题才不贴在图上。
 *
 * 59dp = 30dp 起步 + 20px（≈9dp，用户第一次要求"整体下移 20px"）+ 20dp（第二次
 * 要求"信息区再下移 20dp"）。只加在这里 —— 三段间距任选一段推开都会连带改变内部
 * 节奏，而"整体下移"只该动这一处，信息、进度、控制三者的相对距离不变。
 */
private val FieldTopPadding = 59.dp

/**
 * 歌曲信息与进度条之间的距离。
 *
 * 26dp → **4dp**（第 79 批）：进度条的命中区抬到 [ProgressTouchHeight] 之后，多出来的
 * 22dp 全部从这里让出来。这样条的中心、时间标签、控制条**一格都没动** —— 只是把原来
 * 的空白变成了可点区域。别把它改回去，否则整块信息区会往下沉 22dp。
 */
internal val ProgressTopGap = 4.dp

/** 进度条自己画出来的高度（含拖动时变粗的那几个 dp 的余量）。 */
internal val ProgressBarHeight = 26.dp

/**
 * 进度条的**命中区**高度。
 *
 * 48dp 是 Material 的最小触摸目标；条本身只有 [ProgressBarHeight] 高，手指按在条上下一
 * 两个像素就会落空。命中区与绘制区因此分开：命中区 48dp，条画在它的底部 ——
 * 向上扩不向下扩是有意的，见 [AppleMusicProgress] 里的说明。
 */
internal val ProgressTouchHeight = 48.dp

/** 进度条时间标签与播放控制之间的距离。控制整组因此再往下落一点，离拇指更近。 */
private val ControlsTopGap = 26.dp

/** 边缘模糊那一层的不透明度。太高会把色场盖成一张糊图，太低就又变回一条硬边。 */
private const val EdgeHazeAlpha = 0.55f

/**
 * 播放页。
 *
 * 状态和动作全部来自 [PlayerViewModel]：收藏、seek、切歌都只有一条路径，UI 也不再持有
 * [com.milan.music.di.AppContainer]（它带 `Context`/`DataStore`，拿在 composable 手里就
 * 既不能 `@Preview` 也不能只注入状态做 UI 测试）。
 *
 * [player] 与 [artwork] 是留给 [CoverCarousel] 的**显式**依赖：轮播自己会加载它要画的
 * 封面，也必须等会话确认换曲之后才落位（见 `CoverCarousel.launchSwitch`）——那段等待
 * 拿的是 `PlayerController.state`，抄一份进 ViewModel 只会让两处状态各自演化。
 */
@Composable
internal fun PlayerScreen(
    viewModel: PlayerViewModel,
    player: PlayerController,
    artwork: ArtworkRepository,
    onBack: () -> Unit,
    /**
     * 迷你播放器卡片的窗口矩形 —— 入场动画的生长起点。
     *
     * `null` 表示这一帧没有可用起点（队列未就绪、卡片正在退场）。此时进场退回
     * 纯淡入，**不阻断**页面挂载：动画拿不到起点是常态，不能变成"打不开播放页"。
     */
    origin: Rect? = null,
    /**
     * 拖动进度（0 = 完全盖住首页 / 1 = 完全退出）。
     *
     * 首页拿它做联动缩放与圆角。每帧都会回调，所以接收端只许写进
     * `graphicsLayer` 能读到的 state，不许进组合参数。
     */
    onDragProgress: (Float) -> Unit = {},
) {
    val playerState by viewModel.playback.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()

    val track = playerState.currentTrack

    val density = LocalDensity.current
    val dismissThreshold = with(density) { 110.dp.toPx() }
    // 手指越过关闭阈值时给一次阈触觉：这一下是"你已经做过界了"的物理确认，
    // 不是装饰。用 GestureThresholdActivate 而不是普通点击触觉 —— 它的语义正是
    // "手势越过了一个会改变结果的界线"，系统触觉引擎会给出更短、更硬的一记。
    val hapticFeedback = LocalHapticFeedback.current

    // 色场**故意**不跟曲目一起作废：新封面解码完成前沿用上一套配色，否则每次切歌都会
    // 先闪一下兜底深褐。解码完成后由色场自己的颜色动画接力过渡。
    var palette by remember { mutableStateOf<PlayerPalette?>(null) }
    // 封面像素就不同了 —— 它们按曲目归属作废（见 [PlayerArtwork]）：`null` 表示这一帧
    // 没有封面，而不是"沿用上一首"。上一首的镜像/糊版画在下一首上，是错误内容。
    var loaded by remember { mutableStateOf<PlayerArtwork?>(null) }
    // 轮播**此刻画在中央**的那一页。它和 `track`（会话当前曲目）会短暂不同：会话先换曲，
    // 轮播要等翻页动画走完并确认之后才换页。倒影带子和上缘糊版必须跟这个，不能跟 `track`
    // —— 跟错了就是"带子已经是新封面、上面那张还是旧封面"的那一下闪（第 59 批）。
    var shownTrack by remember { mutableStateOf(track) }
    // 封面横向滑动位移。倒影叠层要跟着封面一起走（见 [CoverCarousel] 的同名参数）：
    // 它画的是"封面底部的继续"，封面被拖走时它也必须走。
    val coverOffset = rememberDragOffset()
    LaunchedEffect(track?.id) {
        val target = track ?: return@LaunchedEffect
        val result = viewModel.loadArtwork(target)
        loaded = result
        result.palette?.let { palette = it }
    }

    // 封面区域（清晰封面之上那条糊版、镜面线以下的倒影）的三个画源，归属都必须与
    // **轮播中央那一页**一致：优先取同步缓存（与封面同帧、无闪），缓存未命中时退回
    // 这次加载的产物 —— 但那个产物只有在属于同一首时才行。
    val shownAlbum = shownTrack?.albumId
    val heroSource = loaded?.forTrack(shownTrack?.id)
    val syncCover = remember(shownAlbum) {
        shownAlbum?.let { viewModel.cachedCover(it) }
    }
    val syncMirror = remember(shownAlbum) {
        shownAlbum?.let { viewModel.cachedMirror(it) }
    }
    val edgeHaze = heroSource?.haze
    val bandCover = syncCover ?: heroSource?.cover
    val bandMirror = syncMirror ?: heroSource?.mirror
    // 三个开关共用的唯一判据，见 [canDrawBand]。
    val bandAvailable = canDrawBand(bandCover != null, bandMirror != null, Build.VERSION.SDK_INT)

    val colors = palette ?: FallbackPalette

    // 色场现在是封面本色，浅色封面会得到一块很亮的底色：系统栏图标必须跟着翻深浅，
    // 否则白图标压在浅色封面上就是隐形的。状态栏压在上缘那条色带（top）上，导航栏
    // 压在色场（bottom）底部。阈值 0.179 是黑白对比度相等的那个交点。
    val darkStatusIcons = luminanceOf(colors.top) > 0.179f
    val darkNavIcons = luminanceOf(colors.bottom) > 0.179f

    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    DisposableEffect(window, darkStatusIcons, darkNavIcons) {
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val previousStatus = controller.isAppearanceLightStatusBars
            val previousNav = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = darkStatusIcons
            controller.isAppearanceLightNavigationBars = darkNavIcons
            onDispose {
                controller.isAppearanceLightStatusBars = previousStatus
                controller.isAppearanceLightNavigationBars = previousNav
            }
        }
    }

    // ---- 入场：从迷你播放器卡片的位置长到全屏（iOS 27 的 sheet 展开语义）----
    //
    // 0 = 尚未出现，1 = 完全就位。起点矩形可用时交给弹簧，让"落位"有物理收势；
    // 起点为空时直接 1 —— 宁可没有动画，也不能让页面停在 alpha 0 上。
    //
    // 这是**生长**而不是"从屏幕外平移"：整页以迷你播放器所在的纵向位置为重心
    // （transformOrigin 从 0.88 收到 0.5），从 0.94 放大到 1。外观差别是
    // "卡片变大" vs "一页从底下推上来"，前者不需要用户在屏幕边上等它走完。
    val grow = remember { Animatable(if (origin == null) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (grow.value < 1f) {
            grow.animateTo(1f, MilanMotion.Grow)
        }
    }

    // Interactive swipe-down: the sheet follows the finger while the home screen
    // shows through underneath. Past the threshold it animates the rest of the way
    // off-screen before popping; below it springs back.
    val offsetY = rememberDragOffset()
    val isSettling = remember { mutableStateOf(false) }

    // Measured height of the sheet, published by BoxWithConstraints below. Needed
    // because the dismissal can start from outside that scope (system back).
    val sheetHeightPx = remember { mutableFloatStateOf(0f) }

    // 首页的联动缩放读的是这个回调写出来的值。用 snapshotFlow 而不是在 DragGesture
    // 里直接回调：拖动、回弹、预测式返回、系统返回键四条路径都会改 offsetY，
    // 只有在这里收口才不会漏掉某一条（漏掉的表现是"首页卡在缩小状态上"）。
    LaunchedEffect(Unit) {
        snapshotFlow { offsetY.value to sheetHeightPx.floatValue }.collect { (y, h) ->
            onDragProgress(if (h > 0f) (y / h).coerceIn(0f, 1f) else 0f)
        }
    }

    // Single dismissal path for the swipe gesture and the system back key: the sheet
    // slides fully off-screen and only then is the overlay removed.
    // AnimatedVisibility declares no exit transition (the gesture has already moved
    // the sheet by then), so removing the overlay first would make the player vanish
    // in a single frame.
    val dismissToHome: () -> Unit = {
        if (!isSettling.value) {
            isSettling.value = true
            // Height is published by the layout; if it is still unknown the overlay is
            // simply removed rather than animating to zero.
            val target = sheetHeightPx.floatValue
            if (target > 0f) {
                // `onFinished`, not "await then call": the overlay may only be removed
                // once the sheet has actually left the screen. `DragOffset` skips the
                // callback when the animation is interrupted, so a torn-down slide-out
                // cannot pull the page out from under the next one.
                offsetY.animateTo(target, MilanMotion.Exit) { onBack() }
            } else {
                onBack()
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val dragProgress = if (size.height > 0f) {
                    (offsetY.value / size.height).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val p = grow.value
                // 生长：前 55% 就把不透明度拉满，剩下的行程只留给弹簧收势 ——
                // 观感是"先出现、再落位"，而不是"一边淡入一边滑进来"。
                val appear = (p / 0.55f).coerceIn(0f, 1f)
                // 生长阶段额外给一点上移：只有缩放会像"原地胀大"，配上 6% 屏高的位移
                // 才有"从卡片那里升上来"的方向感。p = 1 时这一项归零。
                translationY = offsetY.value + (1f - p) * size.height * 0.06f
                alpha = appear * (1f - 0.25f * dragProgress)
                // 从 0.94 长到 1；重心压在迷你播放器卡片的纵向位置上，随生长收到正中。
                // 收到 0.5 之后，下滑时的手感仍是原来的"以中心缩小"。
                val growScale = 0.94f + 0.06f * p
                val dragShrink = 1f - 0.06f * dragProgress
                scaleX = growScale * dragShrink
                scaleY = growScale * dragShrink
                val anchor = origin
                    ?.takeIf { size.height > 0f }
                    ?.let { (it.center.y / size.height).coerceIn(0.3f, 0.98f) }
                    ?: 0.88f
                transformOrigin = TransformOrigin(0.5f, anchor * (1f - p) + 0.5f * p)
            }
            // Must sit INSIDE graphicsLayer: painted before it, the backdrop would
            // stay put while the sheet slides away, hiding the home screen.
            .background(FallbackTint)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { },
                    onVerticalDrag = { change, dragAmount ->
                        if (!isSettling.value) {
                            // 同步写入，见 [DragOffset]：松手那一刻读到的就是手指停下的
                            // 位置。走 `scope.launch { snapTo }` 时，同一批指针事件里的
                            // `onDragEnd` 看到的是上一批的位移。
                            offsetY.set((offsetY.value + dragAmount).coerceAtLeast(0f))
                        }
                        change.consume()
                    },
                    onDragEnd = {
                        if (isSettling.value) return@detectVerticalDragGestures
                        if (offsetY.value > dismissThreshold) {
                            hapticFeedback.performHapticFeedback(
                                HapticFeedbackType.GestureThresholdActivate,
                            )
                            dismissToHome()
                        } else {
                            offsetY.animateTo(0f, MilanMotion.Sheet)
                        }
                    },
                    onDragCancel = {
                        if (!isSettling.value) {
                            offsetY.animateTo(0f, MilanMotion.Sheet)
                        }
                    },
                )
            },
    ) {
        // Publish the measured sheet height for the dismissal above. Keyed on the
        // constraint rather than written from a SideEffect: the height is a value
        // derived from measurement, so it changes at most once per resize, and a
        // composition-scoped effect that writes state is a write during composition.
        LaunchedEffect(constraints.maxHeight) {
            sheetHeightPx.floatValue = constraints.maxHeight.toFloat()
        }

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
                        offsetY.set(event.progress * sheetHeightPx.floatValue)
                    }
                }
            } catch (cancellation: CancellationException) {
                // The gesture was released before committing: put the sheet back.
                //
                // 这一句必须落在**组合作用域**上（[DragOffset.animateTo] 就是），不能
                // 在当前协程里 await：这个 catch 拿到的已经是一个被取消的协程，第一帧
                // 就会抛 CancellationException，位移留在原地 —— 预测式返回松手后播放页
                // 卡在半途，只剩一半在屏幕上。
                offsetY.animateTo(0f, MilanMotion.Sheet)
                throw cancellation
            }
            dismissToHome()
        }

        // 封面顶到屏幕最上面。照片按自身比例铺满宽度（高 = 页宽 × 源图比例，零裁零拉），
        // 框比它高出来的那一段由下面的毛玻璃续接 —— 框多高只决定续接多长。
        // 高度由 [CoverLayout.heroHeight] 统一算，几何只在那一处定义。
        val heroHeight = CoverLayout.heroHeight(maxHeight, MinTransportHeight)

        // 轮播一页的行程（页宽 + 页缝）。倒影叠层要跟封面走**同一个公式**，就得拿到同
        // 一个 pitch —— 它和 CoverCarousel 内部的 `maxWidth` 是同一个值（封面框满宽）。
        val pitchPx = with(density) { CoverLayout.pitch(pageWidth = maxWidth).toPx() }

        // 照片铺满整框（Crop），所以照片底边就是框底 —— 淡出终点恒为 PhotoBottom。
        // 取景负责裁切，几何这里不需要知道封面比例。
        val photoBottom = CoverLayout.PhotoBottom

        // 色场的交界点按**照片**在整页里的真实占比算（不是框）：换一台屏幕比例不同的
        // 机器，色场也不会从照片中间或者照片下方开始。
        val pageHeight = maxHeight.value.coerceAtLeast(1f)
        val coverBottom = (photoBottom * heroHeight.value / pageHeight).coerceIn(0f, 1f)

        PlayerBackdrop(palette = colors, cover = bandCover, coverBottom = coverBottom)

        val reflectionHeight = (maxHeight - heroHeight).coerceAtLeast(0.dp)

        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heroHeight),
            ) {
                // 1) 边缘模糊：把封面自己的缩略图放大铺在清晰封面底下，只在封面上缘
                //    淡出的那一段透出来。于是上缘先是"同一张图的糊版"，再落到背景色，
                //    而不是一条从图直接切到纯色的边。下缘不铺它 —— 那里接的是
                //    CoverReflection，两层都管底缘只会叠出一团糊影。
                if (edgeHaze != null) {
                    Image(
                        bitmap = edgeHaze,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(28.dp)
                            .alpha(EdgeHazeAlpha)
                            .edgeHazeMask(),
                    )
                }

                // 2) 清晰封面，上缘用 DstIn 遮罩淡出。
                //    用遮罩而不是往上盖色：色场本身在随切歌做颜色动画，盖色必须逐帧
                //    同色才看不见接缝；遮罩只是把封面自己的透明度压到 0，底下是什么
                //    就透出什么，怎么变都不会出缝。
                //
                //    下缘**不淡出**（第 44 批）：倒影带子在带内的覆盖率不是 1（逐层 over，
                //    `1 − Π(1−w_i) < Σw`），封面的不透明度就是它唯一的不透底来源，封面
                //    若在这里淡成 0，带子里就会透出一条背景色带 —— 那正是"封面下方一块
                //    平色"的来源。隔壁页由 `fadeNeighbourBottoms` 单独处理。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .heroFadeMask(fadeBottom = !bandAvailable),
                ) {
                    CoverCarousel(
                        previousTrack = playerState.previousTrack,
                        currentTrack = playerState.currentTrack,
                        nextTrack = playerState.nextTrack,
                        player = player,
                        artworkRepository = artwork,
                        // 透明：照片只占框的上面那一块，它下面必须让出位置给毛玻璃
                        // 续接。铺色的话又回到第 34 批那面"平色带"。
                        backgroundColor = Color.Transparent,
                        modifier = Modifier.fillMaxSize(),
                        offsetX = coverOffset,
                        // 隔壁页没有倒影叠层跟着它：它滑进来时必须自己淡出底边，
                        // 否则镜面线上会露出它的一条硬边。
                        fadeNeighbourBottoms = bandAvailable,
                        // 中央这一页是谁 —— 带子跟着它走，与翻页同帧。
                        onTrackShown = { shownTrack = it },
                    )
                }
            }

            // 倒影栈：位置正好是封面框底 —— 也就是镜面线。
            // 它自己**不占排版高度**（`OverlayLayoutHeight = 0`，见 CoverReflection），
            // 只负责绘制：从镜面线往下铺满整页。封面侧没有过渡带，封面从顶到底都是
            // 清晰的照片 —— 它在镜面线以上多画 34dp 是**出血**（给图层边缘留的，
            // 那里不画内容），不是模糊带：第 84 批真机上一条 8px 的黑线就是这么来的，
            // 四个离屏图层的顶边正好压在镜面线上，边缘掉 alpha。
            //   · 排在封面之后、[PlayerTransport] 之前 → 文字与控制永远在最上层，
            //     不会被倒影的半透明盖成一片洗白；
            //   · 高度必须报 0：它画满整页高度，若照实报，Column 会把控制条整体推到
            //     屏幕外（第 55 批真机：整屏只剩封面，歌名/进度/按钮全部出界）；
            //   · **横向跟着封面走**：倒影是封面的镜像，封面被手指拖走时它必须一起走。
            //     钉在屏幕上就是"一层画着封面下半部分的叠加层"（2026-10-23 第 58 批）。
            CoverReflection(
                cover = bandCover,
                mirror = bandMirror,
                heroHeight = heroHeight,
                height = reflectionHeight,
                // 与封面同一套取景：横向裁切对称，镜面线两侧的内容才严格连续。
                contentScale = CoverLayout.HeroContentScale,
                // 和封面页**同一个公式**（slot = 0）：手指拖多少，倒影就走多少。
                modifier = Modifier.offset {
                    IntOffset(pageTranslationPx(0, pitchPx, coverOffset.value).roundToInt(), 0)
                },
            )

            PlayerTransport(
                track = track,
                isFavorite = isFavorite,
                isPlaying = playerState.isPlaying,
                progress = viewModel.progress,
                onToggleFavorite = viewModel::toggleFavorite,
                onSeek = viewModel::seekTo,
                onTogglePlay = viewModel::togglePlayPause,
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
                // 不加卡片、不加圆角：封面下方就是页面本身那块色场。
                modifier = Modifier.fillMaxWidth(),
            )
        }

    }
}

/**
 * 封面的 DstIn 遮罩：上缘渐显，下缘按 [fadeBottom] 决定是否渐隐到框底。
 *
 * [fadeBottom] 为 true 表示这一页**底下没有倒影叠层跟着它**：封面下面是纯色场，
 * 不淡出就是一条硬边。两种情况会走到这里 —— 画不出倒影（HARDWARE 位图读不到像素、
 * 或老设备降级失败），以及**横滑时滑进来的隔壁页**（叠层只属于当前这一页）。
 *
 * 能画倒影的那一页必须保持不透明到框底：倒影带子在带内的覆盖率不是 1（逐层 over
 * 叠加，`1 − Π(1−w_i) < Σw`），封面的不透明度就是它唯一的不透底来源，淡掉就会在
 * 接缝上方透出一条背景色带（第 44 批的老问题）。
 */
private fun Modifier.heroFadeMask(fadeBottom: Boolean): Modifier {
    val start = CoverLayout.HeroFadeOut
    val end = CoverLayout.PhotoBottom.coerceAtLeast(start + 0.02f)
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                brush = if (fadeBottom) {
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        CoverLayout.HeroFadeIn to Color.Black,
                        start to Color.Black,
                        end to Color.Transparent,
                    )
                } else {
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        CoverLayout.HeroFadeIn to Color.Black,
                    )
                },
                blendMode = BlendMode.DstIn,
            )
        }
}

/**
 * 边缘模糊层的遮罩：只在封面的上缘显形，往下很快收干净。
 *
 * 底缘不归它管 —— 封面下半段沉进的是 [CoverReflection] 那块倒影，
 * 两层都管底缘只会叠出一团糊影。所以从上往下读到的顺序是「背景色 → 模糊封面 →
 * 清晰封面」，这就是"模糊 + 背景色渐变"。
 */
private fun Modifier.edgeHazeMask(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.07f to Color.Black,
                    0.24f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }

/**
 * 页面背景：上下两段由封面顶端 10%、底端 20% 两条色带推出来的色场。
 *
 * 封面贴到页面最上面，所以顶端这一段不靠"留白"露出来：页面从 y = 0 就是
 * [PlayerPalette.top]，封面自己的上缘在那里淡出，露出的正是这层颜色 —— 状态栏因此
 * 压在一块确定的底色上，不必再另铺暗角。走到封面底边换成 [PlayerPalette.bottom]，
 * 再往下整片平铺成信息区的底。
 *
 * 颜色动画收在这个小 composable 里：切歌时只有这一层逐帧重绘，标题、进度条和封面
 * 轮播不跟着每帧重组。
 */
/**
 * 播放页背景：**封面最下 20% 的色带 → 纵向渐变 → 再垫一层大半径模糊的同一色带**。
 *
 * 2026-10-23 用户的要求是「取色只取封面最下 20%，并且要渐变、模糊」，三件事分别落在：
 *
 *  1. **取色**：`PlayerPalette` 的两个色都来自封面 80%–100% 那一条（见 `CoverPalette`），
 *     封面顶部完全不参与；
 *  2. **渐变**：三个色点由 [backdropStopsOf] 给 —— 页面顶端 → 镜面线（色带本色）→
 *     页面底端再暗一档。最后这一档是"渐变"的保底：色带自身的纵向差可能接近 0
 *     （纯色封面），只靠色带内色差，下半页仍是一块死色；
 *  3. **模糊**：把同一条色带纵向拉伸铺满整页、`blur` 到大半径，垫在渐变**之上**。
 *     它是纹理层不是底色层（alpha 见 [BackdropTextureAlpha]）：底色仍是那条渐变，
 *     这层只负责让色场不像一块平涂的塑料。
 *
 * 模糊层有三条硬约束：
 *
 *  - `Modifier.blur` 在 **API ≤ 30 上是 no-op**。5 倍拉伸的条带以清晰状态出现时是一条
 *    放大的色块（比纯色还难看），所以够不到 31 就**干脆不画**，退回纯渐变。
 *  - 位图源只取 `BOTTOM_BAND_START..1` 这一段。整张封面缩放铺满，屏幕上露出的会是
 *    封面中上部，与"只取最下 20%"直接矛盾。
 *  - 取景用 `drawImage(srcOffset/srcSize/dstOffset/dstSize)` 显式给矩形，不用
 *    `Image(contentScale, alignment)` 推：后者的取景随比例漂移，倒影那次"封面出现了
 *    两次"就是同一个坑。
 */
@Composable
private fun PlayerBackdrop(
    palette: PlayerPalette,
    cover: ImageBitmap?,
    coverBottom: Float,
    modifier: Modifier = Modifier,
) {
    val animatedTop by animateColorAsState(
        targetValue = palette.top,
        animationSpec = tween(MilanMotion.AmbientMs, easing = MilanMotion.Standard),
        label = "backdrop-top",
    )
    val animatedBottom by animateColorAsState(
        targetValue = palette.bottom,
        animationSpec = tween(MilanMotion.AmbientMs, easing = MilanMotion.Standard),
        label = "backdrop-bottom",
    )

    // 渐变与色场一起做颜色动画：切歌时底色是"从旧色带走到新色带"，不是一次跳变。
    val stops = backdropStopsOf(
        palette = PlayerPalette(top = animatedTop, bottom = animatedBottom),
        coverBottom = coverBottom,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            // 命名参数不是为了好看：位置参数只匹配 vararg 重载，`Array` 必须 spread 才能进去。
            .background(Brush.verticalGradient(colorStops = stops.toTypedArray())),
    ) {
        if (cover != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(BackdropBlur)
                    .alpha(BackdropTextureAlpha),
            ) {
                val sourceTop = cover.height * BOTTOM_BAND_START
                drawImage(
                    image = cover,
                    srcOffset = IntOffset(0, sourceTop.roundToInt().coerceIn(0, cover.height - 1)),
                    srcSize = IntSize(
                        width = cover.width,
                        height = (cover.height - sourceTop.roundToInt()).coerceAtLeast(1),
                    ),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    // 源图被放大十余倍，高采样只会白花 GPU；模糊本来就要抹掉细节。
                    filterQuality = FilterQuality.Low,
                )
            }
        }
    }
}

/**
 * 下半页的信息区：标题、进度条、播放控制。
 *
 * 直接画在色场上，没有卡片、没有圆角、没有底板 —— 参考图里下半页就是页面本身，
 * 控件浮在那块颜色上。三组内容从封面底边往下依次排开、都不吸底：进度条与播放控制
 * 紧贴在歌曲信息下面，屏幕下方留给色场。
 */
@Composable
private fun PlayerTransport(
    track: Track?,
    isFavorite: Boolean,
    isPlaying: Boolean,
    progress: StateFlow<PlaybackProgress>,
    onToggleFavorite: () -> Unit,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 左右边距不放在这一层：进度条要和封面一样满宽（封面是 edge-to-edge 的），
    // 被 Column 统一加边距的话它只能缩在 24dp 里。所以边距下沉到真正需要它的
    // 两处 —— 歌曲信息、播放控制；进度条自己决定哪部分要贴边。
    Column(modifier = modifier) {
        Spacer(Modifier.height(FieldTopPadding))

        Row(
            modifier = Modifier.padding(horizontal = PlayerSidePadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 标题/艺人整块做上下交接。只在曲目 id 变化时触发，播放进度与收藏
            // 状态不会打断文字。
            AnimatedContent(
                targetState = track,
                modifier = Modifier.weight(1f),
                transitionSpec = { MilanMotion.NeighbourSwap },
                contentAlignment = Alignment.CenterStart,
                contentKey = { it?.id },
                label = "player-track-text",
            ) { item ->
                Column {
                    Text(
                        text = if (item == null) stringResource(R.string.player_nothing_playing) else item.titleText(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = OnDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (item == null) "" else item.artistText(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnDarkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            FavoriteButton(
                isFavorite = isFavorite,
                onToggle = onToggleFavorite,
                iconSize = 24.dp,
                unselectedTint = OnDark,
                label = "player-favorite",
            )
        }

        // 进度条从歌曲名的左缘拉到收藏图标的右缘（见 [ProgressStartInset] /
        // [ProgressEndInset]），既不是满屏、也不是对称的 24dp 边距。
        Spacer(Modifier.height(ProgressTopGap))

        AppleMusicProgress(
            progress = progress,
            fallbackDurationMs = track?.duration ?: 0L,
            onSeek = onSeek,
        )

        Spacer(Modifier.height(ControlsTopGap))

        PlaybackControls(
            isPlaying = isPlaying,
            onTogglePlay = onTogglePlay,
            onPrevious = onPrevious,
            onNext = onNext,
            modifier = Modifier.padding(horizontal = PlayerSidePadding),
        )
    }
}

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
        animationSpec = tween(MilanMotion.ProgressMs),
        label = "progress-bar-height",
    )
    // 圆点常驻，拖动时只放大一点。以前是"拖动才出现"，停下就看不到播到哪儿了，
    // 而参考图里进度条上一直有一个点标着当前位置。
    val thumbScale by animateFloatAsState(
        targetValue = if (dragging) 1.4f else 1f,
        animationSpec = tween(MilanMotion.FastMs, easing = MilanMotion.Standard),
        label = "progress-thumb",
    )

    val displayPosition = if (dragging) (dragRatio * durationMs).toLong() else positionMs

    // 必须在组合里先取出：`semantics { }` 是普通 lambda，里面调不到 `stringResource`。
    val progressDescription = stringResource(
        R.string.progress_state_description,
        formatPosition(displayPosition),
        formatDuration(durationMs),
    )

    // 手势里读的是"最新值"，不是组合那一刻的值：`pointerInput` 的 key 若带上时长，
    // 时长一变（首帧 0 → 会话报出真实值、或切歌）就会把正在进行的拖动**取消**掉 ——
    // 手指还在屏幕上，条却弹回去了。
    val currentDuration by rememberUpdatedState(durationMs)
    val currentOnSeek by rememberUpdatedState(onSeek)

    Column {
        // **触摸区与绘制区分开**：命中区 48dp（Material 最小目标），条本身仍画在底部
        // 那 26dp 里。多出来的 22dp **全部向上**扩 —— 配合 [ProgressTopGap] 由 26dp
        // 收到 4dp，条的位置、时间标签的位置、控制条的位置都不动一格。
        // 若改成居中放大、上下各借 11dp，条与标签会整体下移 11dp，那是一次可见的位移。
        Box(
            modifier = Modifier
                // padding 在 fillMaxWidth 之前 = 先让出两端，再在剩下的宽度里铺满。
                // 手势坐标也用这个宽度归一化，所以点到哪儿就是哪儿。
                .padding(start = ProgressStartInset, end = ProgressEndInset)
                .fillMaxWidth()
                .height(ProgressTouchHeight)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragRatio = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            if (currentDuration > 0L) {
                                currentOnSeek((dragRatio * currentDuration).toLong())
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
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (currentDuration > 0L) {
                            currentOnSeek(
                                (offset.x / size.width.toFloat() * currentDuration).toLong(),
                            )
                        }
                    }
                }
                // The bar is drawn, not a Material Slider, so nothing about it is
                // exposed to a screen reader by default: without this block TalkBack
                // reads it as an anonymous canvas and the only way to seek is by
                // touching a precise x coordinate. `setProgress` is what makes the
                // accessibility scroll gestures seek instead of scrolling the sheet.
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(shownRatio, 0f..1f)
                    stateDescription = progressDescription
                    if (currentDuration > 0L) {
                        setProgress { target ->
                            currentOnSeek((target.coerceIn(0f, 1f) * currentDuration).toLong())
                            true
                        }
                    }
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ProgressBarHeight),
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

                // 位置夹进两端：0:00 时圆点不该有一半被屏幕边缘切掉。
                val thumbRadius = height * 1.15f * thumbScale
                drawCircle(
                    color = OnDark,
                    radius = thumbRadius,
                    center = Offset(
                        x = playedWidth.coerceIn(thumbRadius, size.width - thumbRadius),
                        y = centerY,
                    ),
                )
            }
        }

        Spacer(Modifier.height(2.dp))

        // 时间标签跟着条走：左右两端与进度条首尾对齐，否则"3:47"会探到条的右端之外。
        Row(
            modifier = Modifier
                .padding(start = ProgressStartInset, end = ProgressEndInset)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatPosition(displayPosition),
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
    modifier: Modifier = Modifier,
) {
    // Three equal cells instead of a spacer at each end. The play button is then on the
    // exact centre of the row whatever the cover turns out to be wide — with fixed 40 dp
    // spacers the row needed 300 dp, so a narrower cover made it overflow and shifted
    // every control off the centre line.
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // 深色播放页去掉涟漪，改用按压缩放：手指按下时控件轻微收紧，
            // 松手回弹，比在色场上铺一层水波更干净。
            // 图标尺寸按参考图收小：控件在色场上是"浮着的一排"，不是占满一行的方块。
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .noRippleClickable(
                        role = Role.Button,
                        pressScale = 0.88f,
                        onClick = onPrevious,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = stringResource(R.string.cd_previous),
                    tint = OnDark,
                    modifier = Modifier.size(36.dp),
                )
            }
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .noRippleClickable(
                        role = Role.Button,
                        pressScale = 0.90f,
                        // 播放/暂停：图标会翻转一次，触觉与那一次翻转同拍，且按翻转方向
                        // 分档（开始=ToggleOn，暂停=ToggleOff）。
                        // 切歌按钮不加 —— 连续点下一首会变成一串震动。
                        haptic = if (isPlaying) {
                            HapticFeedbackType.ToggleOff
                        } else {
                            HapticFeedbackType.ToggleOn
                        },
                        onClick = onTogglePlay,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedPlayPauseIcon(
                    isPlaying = isPlaying,
                    tint = OnDark,
                    iconSize = 48.dp,
                )
            }
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .noRippleClickable(
                        role = Role.Button,
                        pressScale = 0.88f,
                        onClick = onNext,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = stringResource(R.string.cd_next),
                    tint = OnDark,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}
