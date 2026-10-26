package com.milan.music.ui.library

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.milan.music.R
import com.milan.music.data.model.SortMode
import com.milan.music.data.model.Track
import com.milan.music.di.AppContainer
import com.milan.music.ui.components.MiniPlayer
import com.milan.music.ui.components.SearchGlyph
import com.milan.music.ui.components.SortGlyph
import com.milan.music.ui.components.shouldAnimatePlayingBars
import com.milan.music.ui.glass.GlassSurface
import com.milan.music.ui.text.labelText
import com.milan.music.ui.theme.LocalGlassColors
import com.milan.music.ui.theme.LikedRed
import com.milan.music.ui.theme.MilanMotion
import com.milan.music.ui.theme.homeBackdropFor
import com.milan.music.ui.theme.iconInkFor
import com.milan.music.ui.util.noRippleCombinedClickable
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.random.Random

private val ContentBottomPadding = 152.dp

/**
 * 搜索框叠加层要让给列表的高度：12dp 上留白 + 46dp 输入框 + 2dp 下留白 + 6dp 原始留白。
 *
 * 这个值只在"列表第一项不该被玻璃压住"时用；滚动之后内容是从搜索框**底下**穿过去的，
 * 那正是要的效果。
 */
private val SearchFieldOverlayHeight = 66.dp

/**
 * 播放页下滑时首页联动圆角的目标值，按进度线性长到它。
 *
 * 这里**没有**缩放系数：首页一旦被几何变换，玻璃面板的采样就会错位（原因见下方
 * `graphicsLayer` 处的注释）。想恢复"卡片退后"的缩小感，得先解决玻璃采样。
 */
private val CoverCornerRadius = 28.dp

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenPlayer: () -> Unit,
    /**
     * True while the player sheet covers this screen. The list stays composed for the
     * swipe-down hand-off, but its infinite now-playing animation must not keep
     * drawing behind an opaque page.
     */
    isCovered: Boolean = false,
    /** Monotonic id of an explicit "play" request from the home-screen widget. */
    playRequest: Long = 0L,
    /**
     * 迷你播放器卡片在窗口坐标系里的矩形，供播放页的入场动画当生长起点。
     */
    onMiniPlayerBounds: (Rect) -> Unit = {},
    /**
     * 播放页被向下拖动的进度（0 = 完全盖住，1 = 完全退出）。
     *
     * 故意做成 lambda 而不是 `Float` 参数：这个值每帧都变，参数化会让整页重组
     * （包括几万条曲目列表）。lambda 只在 `graphicsLayer` 的绘制阶段被读取，
     * 于是每帧只标脏一个图层。
     */
    coverProgress: () -> Float = { 0f },
) {
    val context = LocalContext.current
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val queryText by viewModel.query.collectAsStateWithLifecycle()
    val playerState by container.player.state.collectAsStateWithLifecycle()

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermissionName()) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    // Set when the system stops showing the permission dialog (second denial on
    // Android 11+, or "don't ask again"). Requesting again then does nothing at
    // all, so the gate has to point the user at the system settings instead.
    // Persisted because the denial survives the process: without it the first
    // request after a restart silently does nothing and the gate looks broken.
    val permissionPrefs = remember(context) {
        context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
    }
    var permanentlyDenied by remember {
        mutableStateOf(
            permissionPrefs.getBoolean(KEY_AUDIO_PERMANENTLY_DENIED, false),
        )
    }
    val activity = remember(context) { context.findActivity() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            permanentlyDenied = false
            permissionPrefs.edit { remove(KEY_AUDIO_PERMANENTLY_DENIED) }
            viewModel.refresh()
        } else {
            val denied = activity?.let {
                !ActivityCompat.shouldShowRequestPermissionRationale(it, audioPermissionName())
            } ?: false
            permanentlyDenied = denied
            permissionPrefs.edit {
                if (denied) {
                    putBoolean(KEY_AUDIO_PERMANENTLY_DENIED, true)
                } else {
                    remove(KEY_AUDIO_PERMANENTLY_DENIED)
                }
            }
        }
    }

    // Re-read the grant when the user comes back from the system settings page;
    // without this the gate stays on screen even after the permission was granted
    // there, and the app looks broken.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val granted = ContextCompat.checkSelfPermission(context, audioPermissionName()) ==
            PackageManager.PERMISSION_GRANTED
        if (granted && !hasAudioPermission) {
            hasAudioPermission = true
            permanentlyDenied = false
            permissionPrefs.edit { remove(KEY_AUDIO_PERMANENTLY_DENIED) }
            viewModel.refresh()
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(hasAudioPermission) {
        if (hasAudioPermission) {
            viewModel.ensureLoaded()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    if (!hasAudioPermission) {
        PermissionGate(
            permanentlyDenied = permanentlyDenied,
            onRequest = { permissionLauncher.launch(audioPermissionName()) },
            onOpenSettings = { context.openAppSettings() },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    val hazeState = rememberHazeState()
    var mainTab by rememberSaveable { mutableIntStateOf(0) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    // 排序浮层是一层盖住整页的遮罩，系统返回键必须先把浮层收起来。
    // 没有这个处理时，菜单打开状态下按返回键会直接结束 Activity——用户丢掉的是
    // 整个界面，而不是他们刚打开的菜单。enabled 绑定可见状态，浮层不可见时返回键
    // 行为完全交还给系统。
    BackHandler(enabled = sortMenuOpen) { sortMenuOpen = false }

    val favoriteIds = remember(state.favorites) { state.favorites.map { it.id }.toSet() }

    // 搜索框浮在列表之上（和底栏同一套逻辑），不再占列表的高度。好处是列表能滚到
    // 最顶、内容从玻璃底下穿过去——这才是"浮"的观感。代价是列表得自己让出这一条，
    // 否则第一首歌会被永久压在玻璃底下。
    //
    // 搜索框现在两个 tab 都在（2026-10-22），所以这条留白是常量，不再随 tab 动画。
    val contentPadding = PaddingValues(
        start = 12.dp,
        end = 12.dp,
        top = SearchFieldOverlayHeight,
        bottom = ContentBottomPadding,
    )

    val playList: (List<Track>, Int) -> Unit = { list, index ->
        container.player.playQueue(list, index)
    }

    // 启动即播：曲库一就绪就开播，起点随机、顺序也打乱（[PlayerController.playQueue]
    // 里把 shuffleModeEnabled 钉成 true）。这是产品意图——打开应用就开始放音乐，
    // 不是"用户点了才响"。
    //
    // 进程被系统杀掉后，快照优先：先恢复上次的队列、当前曲目和位置；只有快照不可用
    // （首次启动、曲目全被删）才退回随机。`autoPlay = wasPlaying` 是"打开即播"的唯一
    // 例外——上次是暂停状态时，冷启动不应该突然出声。
    //
    // `autoStarted` 用 remember 而不是 rememberSaveable：配置变更时控制器是
    // application 级的，`queueSize` 仍非零，effect 会直接返回；进程死亡后 remember
    // 归零，才会真正走快照恢复。rememberSaveable 会把 true 带过进程死亡（系统恢复
    // Activity 状态时），反而让恢复路径永远不执行。
    //
    // 代价不在这一页：控制器是 bound client，一旦绑定，播放服务就随**进程**存活而不是
    // 随 UI，`PlayerController.onUiStopped` 的 release 分支因此走不到。那段 KDoc 里有账。
    var autoStarted by remember { mutableStateOf(false) }
    // The widget's play tap is one-shot. Without this watermark the request would
    // stay "new" forever and the effect would restart playback after every library
    // refresh, including immediately after the user paused.
    var handledPlayRequest by remember { mutableLongStateOf(0L) }
    LaunchedEffect(hasAudioPermission, state.tracks.isNotEmpty(), playRequest) {
        if (!hasAudioPermission || state.tracks.isEmpty()) return@LaunchedEffect
        val decision = decidePlaybackStart(playRequest, handledPlayRequest, autoStarted)
        if (!decision.proceed) return@LaunchedEffect
        if (decision.explicitPlay) handledPlayRequest = playRequest
        autoStarted = true
        val explicitPlay = decision.explicitPlay
        if (container.player.state.value.queueSize != 0) {
            // The app was already alive when the widget requested play.
            if (explicitPlay) container.player.play()
            return@LaunchedEffect
        }

        val restored = container.playbackSnapshot.restore(state.tracks)
        if (restored != null) {
            container.player.playQueue(
                tracks = restored.tracks,
                startIndex = restored.startIndex,
                startPositionMs = restored.positionMs,
                shuffle = restored.shuffleModeEnabled,
                repeatMode = restored.repeatMode,
                // A widget play tap overrides a paused snapshot.
                autoPlay = explicitPlay || restored.wasPlaying,
            )
        } else {
            container.player.playQueue(
                state.tracks,
                Random.nextInt(state.tracks.size),
            )
        }
    }

    // 播放页下滑时首页的联动圆角。换算成 px 是因为下面的 `graphicsLayer` lambda
    // 不是组合作用域，拿不到 dp 转换。
    val coverCornerPx = with(LocalDensity.current) { CoverCornerRadius.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                // 播放页压下来时，首页作为"下面那张卡片"起圆角 —— iOS sheet 的既有语义。
                //
                // **只做圆角，不做缩放**（第 71 批真机回归）。haze 的采样坐标按窗口算，
                // 而源的 RenderNode 内容不跟着祖先的几何变换走：这里一加 scale，底栏与
                // 迷你播放器的玻璃面板里就会浮出**放大且错位**的列表文字（实测现象）。
                // 圆角只是绘制期裁剪，不参与坐标换算，所以安全。
                //
                // 缩放要恢复的话，必须同时把玻璃切到非采样模式（`GlassSurface.liveBackdrop`），
                // 代价是下滑瞬间材质跳变 —— 那是产品决策，不是参数问题。
                val p = coverProgress()
                if (p <= 0f) return@graphicsLayer
                shape = RoundedCornerShape(coverCornerPx * p)
                clip = true
            },
    ) {
        AmbientBackdrop(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // 曲库/喜欢切换：新列表从下方少许滑入并淡入，旧列表短淡出。
            // 模糊源挂在容器上而不是列表里：曲库、喜欢、以及空的"喜欢"页都
            // 共用这一个 source，切 tab 时不会先把源摘掉再挂上，底部玻璃也就
            // 不会因为某一帧没有源而丢掉模糊。
            AnimatedContent(
                targetState = mainTab,
                modifier = Modifier
                    .weight(1f)
                    .hazeSource(hazeState),
                transitionSpec = { MilanMotion.PageSwap },
                contentKey = { it },
                label = "library-tab",
            ) { tab ->
                // Both tabs render the same list; only the source differs. One call
                // site keeps the swipe/empty/loading behaviour from drifting apart.
                // The build stamp is library-only: it is a diagnostic, not content.
                SongsList(
                    tracks = if (tab == 0) state.tracks else state.favorites,
                    artworkRepository = container.artwork,
                    currentTrackId = playerState.currentTrack?.id,
                    isPlaying = shouldAnimatePlayingBars(playerState.isPlaying, isCovered),
                    favoriteIds = favoriteIds,
                    onPlay = playList,
                    onToggleFavorite = viewModel::toggleFavorite,
                    contentPadding = contentPadding,
                    isLoading = state.loading,
                    showBuildStamp = tab == 0,
                )
            }
        }

        // 搜索框**浮在列表之上**——和底栏同一套做法：不占列表高度，列表滚到顶时
        // 内容从玻璃底下穿过去。它曾在 Column 里占位，列表永远停在它下面，滚不动
        // 那一条，观感是"一块贴纸"而不是"浮起的玻璃"。
        //
        // 曲库与"喜欢"两页共用同一个搜索框（2026-10-22）：查询条件对两个列表一致
        // 生效，用户在任何一页都能看见并清除它，所以不再按 tab 收起。
        //
        // 排序按钮**在玻璃里面**（2026-10-22 用户要求），搜索框因此铺满整行。左右边距
        // 一并从 20/12 收成 14/14：那对不对称值原来是为了给框外的按钮留位，按钮进了
        // 框之后它只剩"左边比右边宽 8dp"这一个效果；14dp 也和迷你播放器、底栏同一条竖线。
        SearchField(
            value = queryText,
            onValueChange = viewModel::setQuery,
            onSortClick = { sortMenuOpen = true },
            hazeState = hazeState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp)
                .padding(bottom = 10.dp),
        ) {
            // 迷你播放器随队列出现/消失，底部玻璃导航栏保持贴底不动。
            AnimatedVisibility(
                visible = playerState.hasQueue && playerState.currentTrack != null,
                enter = MilanMotion.RiseIn,
                exit = MilanMotion.SinkOut,
            ) {
                Column {
                    MiniPlayer(
                        state = playerState,
                        artworkRepository = container.artwork,
                        hazeState = hazeState,
                        onClick = onOpenPlayer,
                        onTogglePlay = container.player::togglePlayPause,
                        onNext = container.player::next,
                        onBoundsChange = onMiniPlayerBounds,
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            // 在组合作用域里取值：`context.getString` 走的是 LocalContext，配置变化时
            // 不会被失效（lint: LocalContextGetResourceValueCall）。
            val rescanningText = stringResource(R.string.library_rescanning)
            GlassBottomBar(
                selected = mainTab,
                onSelect = { tab ->
                    // 搜索框现在两个 tab 都在，查询对两个列表一致生效，用户在任何一页
                    // 都看得见、也清得掉。以前切走要清空查询，是因为它在"喜欢"页不可见，
                    // 列表会被一个屏幕上找不到原因的条件过滤掉；这个前提没有了。
                    mainTab = tab
                },
                onRescan = {
                    viewModel.refresh()
                    Toast.makeText(context, rescanningText, Toast.LENGTH_SHORT).show()
                },
                hazeState = hazeState,
            )
        }

        // 排序面板从右上角展开，方向和触发按钮一致；遮罩层一起淡入。
        // end 从 16 收到 14：右缘与搜索框、底栏同一条竖线（排序按钮进了搜索框之后
        // 这三者才有可比性）。
        AnimatedVisibility(
            visible = sortMenuOpen,
            enter = fadeIn(tween(MilanMotion.FastMs)) + scaleIn(
                initialScale = 0.94f,
                transformOrigin = TransformOrigin(1f, 0f),
                animationSpec = tween(MilanMotion.MediumMs, easing = MilanMotion.Standard),
            ),
            exit = fadeOut(tween(MilanMotion.FastMs)) + scaleOut(
                targetScale = 0.96f,
                transformOrigin = TransformOrigin(1f, 0f),
                animationSpec = tween(MilanMotion.FastMs, easing = MilanMotion.Standard),
            ),
        ) {
            SortMenuOverlay(
                hazeState = hazeState,
                selected = state.sortMode,
                onSelect = { mode ->
                    viewModel.setSortMode(mode)
                    sortMenuOpen = false
                },
                onDismiss = { sortMenuOpen = false },
            )
        }
    }
}

@Composable
private fun AmbientBackdrop(modifier: Modifier = Modifier) {
    // 配色不在这里拼：两套配方都在 `ui/theme/Color.kt`（LightHomeBackdrop /
    // DarkHomeBackdrop），这样"再淡一点 / 再暗一点"是改一处数值，
    // 而不是在 Canvas 里翻常量。玻璃面板采样的就是这层，改它等于同时改玻璃的底。
    val backdrop = homeBackdropFor(LocalGlassColors.current.isDark)

    Canvas(modifier = modifier) {
        // 竖直播渐变：顶部淡金（深色下是暗金）一路沉到底部近白（近黑）。
        // 三段而不是两段：两端线性插值在长距离上会把中段压成一块发灰的过渡带，
        // 给一个显式中段色点就能把曲线捏住。光晕叠在这层之上，玻璃才有东西可折射。
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = backdrop.stops.toTypedArray(),
            ),
        )

        backdrop.glows.forEach { glow ->
            val center = Offset(size.width * glow.centerX, size.height * glow.centerY)
            val radius = size.width * glow.radius
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glow.color.copy(alpha = glow.alpha), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSortClick: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    // 读在组合作用域里：`semantics {}` 的 lambda 不是 @Composable，里面不能调 stringResource。
    val sortLabel = stringResource(R.string.cd_sort)

    // 搜索框是浮起的液态玻璃（用户 2026-10-22 明确要求）。
    //
    // 上一版曾把它拉平成一款 5% 的平底，理由是它在 `Column` 里、与列表不重叠。
    // 布局判断没错，但要的不是"内联输入框"，而是它**看上去**浮在页面上：
    // 那就必须有投影 + 玻璃棱，也就是回到 `GlassSurface`。
    //
    // 材质不在调用点重复覆盖：白点/对比度归零、不染色这些结论统一在
    // `glassStyleFor` 里，否则搜索框与迷你播放器会各自漂移成两种玻璃。
    //
    // 排序按钮在框内右侧（2026-10-22 用户要求「把筛选按钮放进搜索框里面」）。
    // 它和"清除"一起构成输入框的尾部动作区，顺序是「清除 → 排序」：清除贴着文字
    // 出现又消失，放在最外层会让排序按钮跟着左右跳。
    // 放大镜与双箭头改为自绘（方案 03「分层双色」，2026-10-22）：几何与分档在
    // `ui/components/IconGlyphs.kt`，取色在 `iconInkFor`，这里不再需要 tint。
    //
    // 只剩清除按钮还走 `iconInk`。取值不从 `onBackground` 走：它是 `Ink`
    // （0xFF2A2419，暖黑），去掉 alpha 也还是一层棕，叠 alpha 就成灰棕。
    val iconInk = iconInkFor(LocalGlassColors.current.isDark)

    GlassSurface(
        hazeState = hazeState,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 15.dp, end = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 自绘的线框放大镜（方案 03：环 100% / 手柄 55%），笔画 1.4dp @21dp。
            SearchGlyph(iconSize = 21.dp)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onBackground,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
            AnimatedVisibility(
                visible = value.isNotEmpty(),
                enter = expandHorizontally(expandFrom = Alignment.End) +
                    fadeIn(tween(MilanMotion.FastMs)),
                exit = shrinkHorizontally(shrinkTowards = Alignment.End) +
                    fadeOut(tween(MilanMotion.FastMs)),
            ) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(26.dp),
                ) {
                    // 与放大镜、排序同一枚墨色（用户 2026-10-22 追加要求：清除也一起纯黑）。
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cd_clear_search),
                        tint = iconInk,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Spacer(Modifier.width(2.dp))
            // 34dp 的盒子 + 20dp 图形：图形右缘落在距玻璃边 15.5dp 处，与左侧放大镜
            // 的 15dp 对齐。颜色与放大镜一致（纯黑 / 深色下 Moonlight）。
            IconButton(
                onClick = onSortClick,
                modifier = Modifier.size(34.dp),
            ) {
            // 双箭头（iOS 语义里"排序"是 arrow.up.down，三条递减线才是筛选/调整）：
            // 左 ↑ 主层 100%、右 ↓ 次层 55%，深浅两个箭头的明度差就是方案 03 的层次。
            SortGlyph(
                iconSize = 20.dp,
                modifier = Modifier.semantics { contentDescription = sortLabel },
            )
            }
        }
    }
}

@Composable
private fun SortMenuOverlay(
    hazeState: HazeState,
    selected: SortMode,
    onSelect: (SortMode) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        GlassSurface(
            hazeState = hazeState,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 64.dp, end = 14.dp)
                .width(156.dp),
            shape = RoundedCornerShape(20.dp),
            // 每弹一次都是一次出现，所以每次都扫。
            sweepOnAppear = true,
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                SortMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mode) }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = mode.labelText(),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        if (mode == selected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一个 tab 的图标来源。
 *
 * 米兰 tab 用自带黑白的太极位图（不能 tint，否则两半会变成同一个颜色），
 * 喜欢 tab 仍是 material 线条图标，靠 tint 跟随取色档。
 */
private sealed interface TabIcon {
    data class Vector(val image: ImageVector) : TabIcon

    /** 已经自带颜色的位图，不 tint；选中态只用 alpha 区分。 */
    data class Plain(@DrawableRes val res: Int) : TabIcon
}

private data class BottomTab(
    val icon: TabIcon,
    val label: String,
    val accent: TabAccent,
)

/** 选中态的取色档：红（[LikedRed]，与列表里的收藏红同一枚）或墨色（浅色纯黑 / 深色 Moonlight）。 */
private enum class TabAccent { Liked, Ink }

@Composable
private fun GlassBottomBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    onRescan: () -> Unit,
    hazeState: HazeState,
) {
    // 纯黑在 Night 上只有 1.05:1、等于隐形，所以墨色档在深色主题里换成 Moonlight
    // （与搜索框里的图标同一条规则，见 `iconInkFor`）。
    val inkAccent = iconInkFor(LocalGlassColors.current.isDark)

    // 米兰 tab 的太极每点一次原地转半圈。存的是累积角度而不是布尔值，
    // 所以连点会一圈圈接着转，不会在中途"掉头"回到原位。
    var spinTarget by remember { mutableFloatStateOf(0f) }
    val spin by animateFloatAsState(
        targetValue = spinTarget,
        animationSpec = tween(MilanMotion.SlowMs, easing = MilanMotion.Standard),
        label = "tab-taiji-spin",
    )

    val libraryTabLabel = stringResource(R.string.tab_library)
    val favoritesTabLabel = stringResource(R.string.tab_favorites)
    val tabs = remember(libraryTabLabel, favoritesTabLabel) {
        listOf(
            // 应用图标的太极，已裁掉外圈描边并补成黑白两半（drawable-night 里反相），
            // 22dp 框内不额外缩放。
            BottomTab(
                icon = TabIcon.Plain(R.drawable.ic_tab_taiji),
                label = libraryTabLabel,
                accent = TabAccent.Ink,
            ),
            BottomTab(TabIcon.Vector(Icons.Rounded.Favorite), favoritesTabLabel, TabAccent.Liked),
        )
    }

    GlassSurface(
        hazeState = hazeState,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val active = selected == index
                val tint by animateColorAsState(
                    targetValue = if (active) {
                        when (tab.accent) {
                            // 「米兰」tab 图标与文字都是黑色，「喜欢」tab 是收藏红（与列表心形同一枚红）。
                            TabAccent.Ink -> inkAccent
                            TabAccent.Liked -> LikedRed
                        }
                    } else {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                    },
                    animationSpec = tween(MilanMotion.MediumMs, easing = MilanMotion.Standard),
                    label = "tab-tint-$index",
                )
                val iconScale by animateFloatAsState(
                    targetValue = if (active) 1.08f else 1f,
                    animationSpec = MilanMotion.Press,
                    label = "tab-icon-scale-$index",
                )

                Column(
                    modifier = Modifier
                        .noRippleCombinedClickable(
                            role = Role.Tab,
                            pressScale = 0.94f,
                            onClick = {
                                if (index == 0) spinTarget += 180f
                                onSelect(index)
                            },
                            onLongClick = if (index == 0) onRescan else null,
                        )
                        .padding(horizontal = 30.dp, vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val iconModifier = Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                            // 只有米兰的太极转；喜欢的心形保持不动。
                            rotationZ = if (index == 0) spin else 0f
                        }

                    when (val icon = tab.icon) {
                        is TabIcon.Vector -> Icon(
                            imageVector = icon.image,
                            contentDescription = tab.label,
                            tint = tint,
                            modifier = iconModifier,
                        )

                        is TabIcon.Plain -> Image(
                            painter = painterResource(icon.res),
                            contentDescription = tab.label,
                            alpha = if (active) 1f else 0.55f,
                            modifier = iconModifier,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionGate(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 36.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.LibraryMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.gate_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (permanentlyDenied) {
                    stringResource(R.string.gate_body_denied)
                } else {
                    stringResource(R.string.gate_body)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            Button(onClick = if (permanentlyDenied) onOpenSettings else onRequest) {
                Text(if (permanentlyDenied) stringResource(R.string.gate_action_settings) else stringResource(R.string.gate_action))
            }
        }
    }
}

/** Walks the context wrappers to the hosting activity (needed for permission APIs). */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

private fun audioPermissionName(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private const val PERMISSION_PREFS = "milan_permission_state"
private const val KEY_AUDIO_PERMANENTLY_DENIED = "audio_permanently_denied"
