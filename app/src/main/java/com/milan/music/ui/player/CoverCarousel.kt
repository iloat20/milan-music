package com.milan.music.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.milan.music.data.media.ArtworkRepository
import com.milan.music.data.model.Track
import com.milan.music.play.PlayerController
import com.milan.music.ui.components.CoverArt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How long a committed page takes to slide out from under the finger. */
private const val SWITCH_SLIDE_MS = 240

/** How long a committed swipe waits for the session to confirm the new track. */
private const val SWITCH_TIMEOUT_MS = 600L

/**
 * Clearance between two covers while the row is in motion.
 *
 * Only ever seen mid-swipe: at rest the pitch puts the neighbours a whole gap outside
 * the window, so the player shows one cover and nothing else.
 */
private val PageGap = 12.dp

/**
 * 封面页占屏幕高度的比例。
 *
 * 2026-10-22 第 41 批定稿（方案 C）。前三轮分别试过：
 *
 *  - 第 38 批 `Fit`（Contain）：框恒 50%，但竖图左右各留 ≈9% 糊边 —— 用户否；
 *  - 第 39/40 批 `FillWidth`（不裁不拉）：一张不裁，但照片只能铺到「屏宽/屏高」那么高。
 *    去白边后的封面还不是方图（真机那张 873×767），实际只到 **40%** ——
 *    用户连着两轮反馈「封面还是没有占到屏幕 50%」。
 *
 * 结论是需求本身互斥：**框 50% + 照片不裁不拉 + 照片铺满 → 数学上不存在**。
 * 用户明确要 50%，于是本批接受裁切：`ContentScale.Crop` 让照片至少盖满整框，
 * 多出来的部分按 `TopCenter` 裁掉（横图裁左右、竖图裁底部）。
 *
 * 代价是裁切 + 纵向 5% 形变（用户选的就是这个组合，见他 2026-10-22 的「拉伸 5% + 少裁一半」）：
 *  - 方图 1:1：左右各裁 **1.5%**（真机 2.3%）—— 纯 Crop 时是 3.8% / 4.5%，正好少一半；
 *  - 去白边的宽图 0.879（真机那张 873×767）：左右各裁 **7.4%**（真机 8.0%）；
 *  - 竖图 3:4：**底部裁掉 18.8%**（真机 19.6%）—— 竖图不拉伸，与纯 Crop 相同。
 *
 * 换来的是**任何封面都正好占屏高 50%**，下方没有一条"什么都填不满"的空档，
 * 淡出带固定为「清晰 40% + 淡出 10%」（用户 2026-10-22 亲口给的比例）。
 */
private const val HERO_HEIGHT_FRACTION = 0.50f

/**
 * The numbers that decide the carousel's geometry.
 *
 * Plain Dp maths rather than inline arithmetic, so the properties that were easy to get
 * wrong — hero height, neighbours out of sight at rest — are pinned by unit tests instead
 * of only by looking at a screenshot.
 */
internal object CoverLayout {
    /**
     * 封面顶端渐显结束的位置（占 hero 高度的比例）。
     *
     * 和 [HeroFadeOut] 一起被 `PlayerScreen` 的 `heroFadeMask` 直接使用：上缘 0 → 0.16
     * 渐显（封面从色场里浮出来），下缘 [HeroFadeOut] → 框底（[PhotoBottom]）渐隐
     * （沉进封面自己的模糊续接）。
     */
    const val HeroFadeIn = 0.16f

    /**
     * 封面底端开始渐隐的位置，**占 hero 高度的比例**（不是占屏幕）。
     *
     * 用户 2026-10-22 的要求是「清晰封面要 40%（占屏幕）、淡出带 10%」，而 hero 是屏高的
     * [HERO_HEIGHT_FRACTION]，所以这里是 `0.40 / 0.50 = 0.80`；剩下 20% 框高
     * （= 10% 屏高）就是淡出带。
     *
     * 照片现在铺满整框（[HeroContentScale] = `Crop`），底边恒为 [PhotoBottom]，
     * 带子长度不再随封面比例浮动 —— 第 39 批"整条带子落到照片下方"那种情况
     * 在几何上已经不可能发生。
     */
    const val HeroFadeOut = 0.40f / HERO_HEIGHT_FRACTION

    /**
     * 照片底边在 hero 里的位置，也就是淡出遮罩的终点：**恒为 1（框底）**。
     *
     * `Crop` 的定义就是"照片至少盖满整个框"，所以照片底边永远在框底或更下面。
     * 淡出必须在框底收到 0，否则那条边界会露出一道"清晰 → 糊版"的台阶
     * （第 34 批量到过纹理落差 73%）。
     */
    const val PhotoBottom = 1f


    /**
     * 纵向多看的比例（用户 2026-10-22 认可的量）。
     *
     * 铺满 50% 的框意味着照片要放大；放大的量固定，多出来的宽度就得裁。纵向多给 5%，
     * 需要放大的量就少 5%，横向裁切跟着少一截 —— 方图上正好把裁切减半
     * （真机 4.53% → 2.26%，390×844 上 3.79% → 1.48%）。
     *
     * 只在**纵向确实能补上**的时候生效：竖图的高度本来就溢出，再拉伸只会多裁底部，
     * 所以那类封面保持不拉（见 [HeroContentScale]）。
     */
    const val PhotoStretch = 1.05f

    /**
     * 封面的取景方式：**铺满整个框，多出来的部分裁掉，纵向放宽 [PhotoStretch]**。
     *
     * 这是唯一能让"照片正好占屏高 50%"成立的取景 —— 第 38 批的 `Fit` 会留白、
     * 第 39/40 批的 `FillWidth` 只到 40%，都被用户否掉了。纯 `Crop` 也能铺满，但裁得
     * 多；纵向多给 5% 之后，需要放大的比例回落，横向裁切同步变少。
     *
     * 规则（`w`/`h` 为宽高比）：
     *  - 宽图、方图（`h / PhotoStretch > w`）：统一缩放按"拉伸后顶满高度"算，
     *    纵向再 ×1.05 —— 也就是纵向确实吃了 5%；
     *  - 竖图（`h / PhotoStretch ≤ w`）：高度本来就溢出，不做拉伸，退化成纯 `Crop`。
     *
     * 裁切量（390×844 框 390×422 / 873×1920 框 873×960）：
     *  - 方图 1:1：左右各裁 **1.5% / 2.3%**（纯 Crop 时是 3.8% / 4.5%）；
     *  - 去白边的宽图 0.879（真机那张）：左右各裁 **7.4% / 8.0%**（纯 Crop 时 9.4% / 10.1%）；
     *  - 竖图 3:4：底部裁掉 18.8% / 19.6%（不拉伸，与纯 Crop 相同）。
     */
    val HeroContentScale: ContentScale = object : ContentScale {
        override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
            val widthRatio = dstSize.width / srcSize.width
            val heightRatio = dstSize.height / srcSize.height
            // 若按"拉伸后顶满高度"算出来的比例更大，说明宽度是被高度顶出来的 ——
            // 这种情况才用得上那 5%。
            val stretched = heightRatio / PhotoStretch
            return if (stretched > widthRatio) {
                ScaleFactor(stretched, stretched * PhotoStretch)
            } else {
                ScaleFactor(widthRatio, widthRatio)
            }
        }
    }

    /**
     * 顶部对齐：裁切全部落在**底部**。
     *
     * 封面是画，顶部（标题/人脸）比底部重要；底部又正好是淡出带，裁掉的那截本来
     * 就要沉进模糊里，看不见。
     */
    val HeroContentAlignment: Alignment = Alignment.TopCenter

    /**
     * 倒影位图的对齐方式：**BottomCenter** —— 不是 [HeroContentAlignment] 的照抄。
     *
     * 倒影画的是**上下翻转过**的同一张图。封面用 TopCenter 取景时，竖版封面裁掉的是
     * 源图的**底部**；翻转之后，"源图底部"落在位图的**顶部**，要让它贴住镜面线就必须
     * 对齐**底部**。对齐成 Center/TopCenter 时倒影会跳过封面最下面那一截、错开半个
     * 裁切量 —— 2026-10-23《餓狼傳說》真机：封面底部的白边在倒影里消失，接缝处出现
     * 0.19 的亮度断层。方图走拉伸路径时缩放后高度恰好等于框高、对齐不参与，所以那张
     * 图上完全看不出这个 bug。
     *
     * 数学：封面 `C(v) = S(v)`（S 为缩放后的源图，v ∈ [0, 框高]，顶部对齐裁底）；
     * 翻转图 M 的缩放 `M(m) = S(H' − m)`（H' 为缩放后高度）。底部对齐时
     * `R(y) = M(y − (框高 − H')) = S(框高 − y) = C(框高 − y)` —— 任意纵横比下都是
     * 严格镜像。
     */
    val HeroMirrorAlignment: Alignment = Alignment.BottomCenter

    /**
     * 封面页的高度：屏幕高度的 [HERO_HEIGHT_FRACTION]（50%），但必须给信息区留够
     * [minTransport]。
     *
     * 竖屏永远取 50% 那一档；只有窗口矮到"50% 之后放不下信息区"时，才让封面退让。
     */
    fun heroHeight(windowHeight: Dp, minTransport: Dp): Dp =
        minOf(
            windowHeight * HERO_HEIGHT_FRACTION,
            (windowHeight - minTransport).coerceAtLeast(0.dp),
        )

    /**
     * Distance one page travels: the page width plus [PageGap].
     *
     * 页面满宽，所以一个 pitch 刚好把旧封面完全推出屏幕、把新封面停在原位；静止时
     * 隔壁那张正好在屏幕外一道缝的位置，不需要淡出。
     */
    fun pitch(pageWidth: Dp): Dp = pageWidth + PageGap
}

/**
 * The three covers mounted around the current track, in playback order.
 *
 * Kept as local state by the carousel instead of reading
 * [com.milan.music.play.PlayerUiState] directly: a committed swipe shifts the row and
 * resets its offset in one frame, which is only pixel-continuous if the position and
 * the pages change together.
 */
private data class CoverPages(
    val previous: Track? = null,
    val current: Track? = null,
    val next: Track? = null,
)

/**
 * Horizontal cover carousel: both neighbours stay mounted one pitch to either side, a
 * drag reveals them, and a release past the threshold commits the switch.
 *
 * 取景见 [CoverLayout.HeroContentScale]（2026-10-22 第 42 批：铺满 + 纵向 5%）。
 * 一句话：**照片盖满整个框，多出来的部分裁掉；纵向多给 5%，横向就少裁一截。**
 * 框恒为屏高一半（[HERO_HEIGHT_FRACTION]），所以下方不会出现"什么都填不满"的空档，
 * 淡出带也固定为「清晰 40% + 淡出 10%」。
 *
 * Two numbers come from the window rather than being guessed:
 *
 * - **Height** ([CoverLayout.heroHeight]): 50 % of the window, shortened only when the
 *   information below would otherwise be squeezed out.
 * - **Pitch** ([CoverLayout.pitch]): the page width plus [PageGap]. A page therefore
 *   travels just past the screen edge, which is what keeps the neighbours out of sight
 *   at rest.
 *
 * 为什么最后接受裁切：填满一个"比页宽还高"的框，横向裁切与纵向拉伸被
 * `照片高 = 拉伸 × 页宽 / (1 − 2×裁切)` 钉在一起，两者不可能同时为零；而"框 50% +
 * 照片不裁不拉 + 照片铺满"在数学上不存在。第 38 批的 `Fit`（留糊边）与第 39/40 批的
 * `FillWidth`（只到 40%）都被真机否掉之后，用户明确要 50%，于是接受裁切，
 * 再用 5% 纵向拉伸把横向裁切换回一半。
 *
 * 上缘那道曾失败的边（第 39 批真机：4 行内标准差 75 → 9）不是取景问题，而是照片底边
 * 落在淡出带之外；现在照片铺满整框、底边恒等于框底，那条边在几何上不再存在。
 *
 * There is no shadow and no border: the cover runs into the page through the gradient
 * the player paints over its lower edge, and that dissolve — sharp top, soft bottom — is
 * the whole of the framing.
 *
 * [modifier] carries position and the height the player reserved for the hero; the
 * component fills the space it is given and then appends clipping, the drag detector and
 * the accessibility actions in that order.
 */
@Composable
internal fun CoverCarousel(
    previousTrack: Track?,
    currentTrack: Track?,
    nextTrack: Track?,
    player: PlayerController,
    artworkRepository: ArtworkRepository,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    /**
     * 横向滑动位移（px），由调用点持有并在绘制阶段读取。
     *
     * 放在外面是因为**倒影叠层也要跟着走**：倒影是封面的镜像，封面被手指拖走时
     * 倒影必须一起走，否则滑动过程中会看到一层钉在屏幕上、内容和手指底下的封面
     * 对不上的"下半部分"（2026-10-23 用户原话："可以清楚地看到一个叠加层"）。
     */
    offsetX: Animatable<Float, AnimationVector1D> = remember { Animatable(0f) },
    /**
     * 隔壁页要不要自己淡出底边。
     *
     * 倒影叠层只属于**当前这一页**（它按屏幕坐标画，跟着 [offsetX] 走）。隔壁页滑进来
     * 时没有任何东西替它遮住镜面线，它的底边就会和色场硬碰 —— 静止时看不见（在屏幕
     * 外），滑动的整个过程都看得见。有倒影时由调用点打开。
     */
    fadeNeighbourBottoms: Boolean = false,
    /**
     * 中央这一页现在是哪一首 —— **在写 [pages] 的同一个 tick 里**上报。
     *
     * 播放页拿它决定倒影带子画谁的镜像。不能用 `playerState.currentTrack` 代替：
     * 会话先换曲，轮播要等翻页动画走完、并且会话确认之后才换页（见 `launchSwitch`），
     * 两者相差不止一帧。带子若跟着会话走，切歌瞬间就会出现"带子已经是新封面、
     * 上面那张还是旧封面"（或者反过来）—— 2026-10-23 第 59 批的"闪一下"。
     *
     * 走回调而不是把 [pages] 提到上层：上报与 `pages = …` 是同一批快照写入，
     * 会在同一帧里一起生效；分两次写就必然有一帧错位。所有写入都必须经过
     * 下面的 `publish`，别再直接赋值。
     */
    onTrackShown: (Track?) -> Unit = {},
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            // As wide as the screen and as tall as the space above the transport: the
            // card slides across the window and off its edge instead of being cut at its
            // own boundary, and the neighbours are clipped away until they slide in.
            .clipToBounds(),
    ) {
        // 页面尺寸由播放页给的框决定：满宽、高度就是 hero 那段。行程只跟宽度有关，
        // 所以隔壁那张永远停在屏幕外。
        val pitch = CoverLayout.pitch(pageWidth = maxWidth)
        val pitchPx = with(LocalDensity.current) { pitch.toPx() }

        val scope = rememberCoroutineScope()

        var pages by remember {
            mutableStateOf(CoverPages(previousTrack, currentTrack, nextTrack))
        }
        // 唯一的写入口：页面行与"中央是哪一首"必须一起变，见 [onTrackShown]。
        val publish: (CoverPages) -> Unit = { row ->
            pages = row
            onTrackShown(row.current)
        }
        val latestPublish by rememberUpdatedState(publish)
        val isAnimating = remember { mutableStateOf(false) }

        // Track changes the gesture did not initiate: song end, notification, Bluetooth,
        // widget. Swipes update `pages` themselves and wait for the session, so their
        // transient state must not overwrite the shifted row.
        LaunchedEffect(previousTrack, currentTrack, nextTrack) {
            if (!isAnimating.value) {
                publish(CoverPages(previousTrack, currentTrack, nextTrack))
            }
        }

        // Past the threshold the row finishes the slide, the player changes track, and
        // the offset resets only once the session confirms the incoming track.
        val launchSwitch: (Boolean) -> Unit = { toNext ->
            if (!isAnimating.value) {
                val incoming = if (toNext) pages.next else pages.previous
                if (incoming != null) {
                    isAnimating.value = true
                    scope.launch {
                        // One pitch: the outgoing card leaves the window completely and the
                        // incoming one is already sitting where it stopped.
                        val target = if (toNext) -pitchPx else pitchPx
                        offsetX.animateTo(
                            target,
                            tween(SWITCH_SLIDE_MS, easing = FastOutSlowInEasing),
                        )

                        // The transport's previous() restarts the current song when it has
                        // been playing for more than 3 s. A swipe revealed the previous
                        // cover, so it must move to that track instead.
                        if (toNext) player.next() else player.skipToPrevious()

                        // The controller is asynchronous. Hold the row on the incoming page
                        // until the session actually reports it, then adopt its neighbours
                        // and reset the offset together.
                        val switched = withTimeoutOrNull(SWITCH_TIMEOUT_MS) {
                            player.state.first { it.currentTrack?.id == incoming.id }
                        } != null
                        if (switched) {
                            val latest = player.state.value
                            // 与 `offsetX.snapTo(0f)` 同一批快照写入：页面行、中央曲目、
                            // 位移三者一起生效，中间不存在"新页在正中但坐标还是旧的"帧。
                            latestPublish(
                                CoverPages(
                                    previous = latest.previousTrack,
                                    current = latest.currentTrack,
                                    next = latest.nextTrack,
                                ),
                            )
                            offsetX.snapTo(0f)
                        } else {
                            // The seek never landed (session gone): put the row back
                            // rather than showing a track that is not playing.
                            offsetX.animateTo(
                                0f,
                                spring(stiffness = Spring.StiffnessMediumLow),
                            )
                        }
                        isAnimating.value = false
                    }
                }
            }
        }

        // Read inside the gesture detector, never as pointerInput keys: a track change
        // during a drag updates the reveal target without cancelling the gesture in
        // progress.
        val latestPages by rememberUpdatedState(pages)
        val latestLaunchSwitch by rememberUpdatedState(launchSwitch)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pitchPx) {
                    // Cover follows the finger horizontally; on release past the threshold
                    // the carousel finishes the page, otherwise it springs back. Level with
                    // the vertical drag on the root: the gesture system routes each drag by
                    // its dominant direction.
                    val swipeThreshold = 80.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { },
                        onHorizontalDrag = { change, dragAmount ->
                            if (!isAnimating.value) {
                                // With no neighbour on that side there is no page to hand
                                // over to, so the drag stays put: letting the card move
                                // would promise a page that never arrives.
                                val min = if (latestPages.next != null) -pitchPx else 0f
                                val max = if (latestPages.previous != null) pitchPx else 0f
                                val target = (offsetX.value + dragAmount).coerceIn(min, max)
                                scope.launch { offsetX.snapTo(target) }
                            }
                            change.consume()
                        },
                        onDragEnd = {
                            if (isAnimating.value) {
                                return@detectHorizontalDragGestures
                            }
                            when {
                                offsetX.value < -swipeThreshold &&
                                    latestPages.next != null -> latestLaunchSwitch(true)
                                offsetX.value > swipeThreshold &&
                                    latestPages.previous != null -> latestLaunchSwitch(false)
                                else -> scope.launch {
                                    offsetX.animateTo(
                                        0f,
                                        spring(stiffness = Spring.StiffnessMediumLow),
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            if (!isAnimating.value) {
                                scope.launch {
                                    offsetX.animateTo(
                                        0f,
                                        spring(stiffness = Spring.StiffnessMediumLow),
                                    )
                                }
                            }
                        },
                    )
                }
                // No press indication: a ripple would wash the artwork itself on every tap,
                // and a single tap does nothing here — only the double tap switches — so
                // there is nothing to acknowledge.
                .combinedClickable(
                    interactionSource = null,
                    indication = null,
                    onClick = { },
                    onDoubleClick = { latestLaunchSwitch(true) },
                )
                // A double tap and a horizontal swipe are both invisible to a screen
                // reader. The same two jumps are published as named actions so the
                // carousel stays reachable through the accessibility menu, without adding
                // a visible affordance the design does not have.
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("下一首") {
                            latestLaunchSwitch(true)
                            true
                        },
                        CustomAccessibilityAction("上一首") {
                            latestLaunchSwitch(false)
                            true
                        },
                    )
                },
        ) {
            // Both neighbours stay composed for the whole session so their artwork is
            // decoded before the finger moves. Draw order is left, current, right; pure
            // translation keeps them from overlapping.
            CoverSlot(
                track = pages.previous,
                slot = -1,
                pitchPx = pitchPx,
                offsetX = offsetX,
                artworkRepository = artworkRepository,
                backgroundColor = backgroundColor,
                fadeBottom = pageFadesBottom(slot = -1, neighbourFades = fadeNeighbourBottoms),
            )
            CoverSlot(
                track = pages.current,
                slot = 0,
                pitchPx = pitchPx,
                offsetX = offsetX,
                artworkRepository = artworkRepository,
                backgroundColor = backgroundColor,
                fadeBottom = pageFadesBottom(slot = 0, neighbourFades = fadeNeighbourBottoms),
            )
            CoverSlot(
                track = pages.next,
                slot = 1,
                pitchPx = pitchPx,
                offsetX = offsetX,
                artworkRepository = artworkRepository,
                backgroundColor = backgroundColor,
                fadeBottom = pageFadesBottom(slot = 1, neighbourFades = fadeNeighbourBottoms),
            )
        }
    }
}

/**
 * One page of the three-cover carousel.
 *
 * The page fills the hero box — full width, and the 50 %-of-window height the player gave
 * it — and is translated by its slot position plus the drag offset. Reading the offset
 * inside `graphicsLayer` keeps the drag out of recomposition: only the layer's
 * translation changes per pointer event.
 *
 * 取景：见 [CoverLayout.HeroContentScale]（2026-10-22 第 42 批，铺满 + 纵向 5%）。
 *
 * 一句话：**照片盖满整个框，多出来的部分裁掉，裁切落在底部**。任凭什么比例的封面，
 * 照片都正好占屏高 50% —— 这是用户连提三轮的要求，也是唯一在几何上能兑现的做法。
 *
 * 代价按封面比例浮动（由 [CoverLayout.HeroContentScale] 自己算，不写死）：方图左右
 * 各裁 **1.5%**、去白边后的宽图 0.879 左右各裁 **7.4%**、竖图底部裁掉 **18.8%**。
 * 前几轮试过的"不裁不拉"（`FillWidth`）只到 40%，"整张装进框"（`Fit`）会留白，
 * 都被真机推翻。
 *
 * 裁掉的部分落在底部，那里是淡出带（屏高 40% → 50%），本就沉进模糊里看不见。
 *
 * 没有圆角 —— 封面就是页面顶部，不是页面上的卡片。
 *
 * `null` neighbours are omitted entirely (a one-track queue has none); the current slot
 * still renders [CoverArt]'s deterministic placeholder when nothing is queued.
 */
@Composable
private fun BoxScope.CoverSlot(
    track: Track?,
    slot: Int,
    pitchPx: Float,
    offsetX: Animatable<Float, AnimationVector1D>,
    artworkRepository: ArtworkRepository,
    backgroundColor: Color,
    fadeBottom: Boolean,
) {
    if (track == null && slot != 0) return

    // 轮播落位要求"手指下的那张图"在同一帧变成当前页，不能做 Crossfade。
    // 以 track.id 重建 CoverArt 的淡入状态：同曲目内的占位图→封面仍会淡入，
    // 但切到另一首歌时直接以缓存封面起画。
    key(track?.id) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .graphicsLayer {
                    // Translation lives on the layer: reading `offsetX` here keeps the
                    // drag out of recomposition.
                    translationX = pageTranslationPx(slot, pitchPx, offsetX.value)
                },
        ) {
            CoverArt(
                track = track,
                artworkRepository = artworkRepository,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (fadeBottom) Modifier.heroBottomFadeMask() else Modifier),
                cornerRadius = 0.dp,
                iconSize = 72.dp,
                trimMargins = true,
                // 铺满整框 + 纵向 5%，裁切落在底部。取景方式由 [CoverLayout] 决定，
                // 调用点不许另写一个值。
                contentScale = CoverLayout.HeroContentScale,
                contentAlignment = CoverLayout.HeroContentAlignment,
                backgroundColor = backgroundColor,
            )
        }
    }
}

/**
 * 隔壁页的**底边淡出**：从 [CoverLayout.HeroFadeOut] 到框底渐隐到 0。
 *
 * 只管底边，不碰封面的上缘 —— 上缘的渐显由播放页那层 `heroFadeMask` 统一负责，
 * 两层叠起来会把顶部压两遍。
 *
 * 为什么需要它：倒影叠层是按屏幕坐标画的，只跟着**当前页**走。隔壁页滑进来时，
 * 镜面线下方没有任何东西接它，封面底边就会和色场硬碰 —— 静止时它在屏幕外看不见，
 * 滑动的整段过程都看得见，那正是"一层叠加层"消失后紧接着会露出来的下一条缝。
 */
private fun Modifier.heroBottomFadeMask(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black,
                    CoverLayout.HeroFadeOut to Color.Black,
                    CoverLayout.PhotoBottom to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }

/**
 * 第 [slot] 页在屏幕上的横向位移（px）。
 *
 * 当前页是 `slot = 0`，位移就等于手指拖动量 —— 倒影叠层必须用**同一个公式**算自己的
 * 位置（见 `PlayerScreen`），否则它就会钉在原地，滑动时露出一层和手指底下对不上的
 * "封面下半部分"（2026-10-23 第 58 批的真机现象）。
 */
internal fun pageTranslationPx(slot: Int, pitchPx: Float, offsetPx: Float): Float =
    slot * pitchPx + offsetPx

/**
 * 这一页要不要自己淡出底边。
 *
 * **当前页永远不淡**：它的底边由倒影叠层接管，而带子内部逐层 over 叠加、覆盖率不到 1，
 * 封面的不透明度是唯一的不透底来源 —— 淡掉就会在带子里透出一条背景色带（第 44 批）。
 * 隔壁页相反：没有叠层跟着它，不淡就是一条硬碰色场的边。
 */
internal fun pageFadesBottom(slot: Int, neighbourFades: Boolean): Boolean =
    neighbourFades && slot != 0
