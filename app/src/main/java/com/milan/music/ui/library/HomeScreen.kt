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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.milan.music.R
import com.milan.music.data.model.SortMode
import com.milan.music.data.model.Track
import com.milan.music.di.AppContainer
import com.milan.music.ui.components.MiniPlayer
import com.milan.music.ui.glass.GlassSurface
import com.milan.music.ui.theme.LocalGlassColors
import com.milan.music.ui.theme.LikedRed
import com.milan.music.ui.theme.MilanMotion
import com.milan.music.ui.theme.Moonlight
import com.milan.music.ui.util.noRippleCombinedClickable
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.random.Random

private val ContentBottomPadding = 152.dp

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenPlayer: () -> Unit,
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
    var permanentlyDenied by remember { mutableStateOf(false) }
    val activity = remember(context) { context.findActivity() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            permanentlyDenied = false
            viewModel.refresh()
        } else {
            permanentlyDenied = activity?.let {
                !ActivityCompat.shouldShowRequestPermissionRationale(it, audioPermissionName())
            } ?: false
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
    val contentPadding = PaddingValues(
        start = 12.dp,
        end = 12.dp,
        top = 6.dp,
        bottom = ContentBottomPadding,
    )

    val playList: (List<Track>, Int) -> Unit = { list, index ->
        container.player.playQueue(list, index)
    }

    // Start playing as soon as the library is ready, but only once per screen
    // instance: returning from the player must not restart playback.
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hasAudioPermission, state.tracks.isNotEmpty()) {
        if (!hasAudioPermission || state.tracks.isEmpty() || autoStarted) return@LaunchedEffect
        autoStarted = true
        if (container.player.state.value.queueSize == 0) {
            container.player.playQueue(
                state.tracks,
                Random.nextInt(state.tracks.size),
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
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
            // 搜索栏只属于曲库页。切到"喜欢"时让它收高度而不是瞬间消失，
            // 否则下面的列表会先向上跳一行，再开始自己的过渡。
            AnimatedVisibility(
                visible = mainTab == 0,
                enter = expandVertically(
                    animationSpec = tween(MilanMotion.MediumMs, easing = MilanMotion.Standard),
                ) + fadeIn(tween(MilanMotion.FastMs)),
                exit = shrinkVertically(
                    animationSpec = tween(MilanMotion.FastMs, easing = MilanMotion.Standard),
                ) + fadeOut(tween(MilanMotion.FastMs)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchField(
                        value = queryText,
                        onValueChange = viewModel::setQuery,
                        hazeState = hazeState,
                        modifier = Modifier.weight(1f),
                    )

                    IconButton(onClick = { sortMenuOpen = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Sort,
                            contentDescription = "排序",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }

            // 曲库/喜欢切换：新列表从下方少许滑入并淡入，旧列表短淡出。
            // 模糊源挂在容器上而不是列表里：曲库、喜欢、以及空的"喜欢"页都
            // 共用这一个 source，切 tab 时不会先把源摘掉再挂上，底部玻璃也就
            // 不会因为某一帧没有源而丢掉模糊。
            AnimatedContent(
                targetState = mainTab,
                modifier = Modifier
                    .weight(1f)
                    .hazeSource(hazeState),
                transitionSpec = {
                    val enter = slideInVertically(tween(MilanMotion.MediumMs)) { it / 20 } +
                        fadeIn(tween(MilanMotion.MediumMs))
                    val exit = slideOutVertically(tween(MilanMotion.FastMs)) { -it / 20 } +
                        fadeOut(tween(MilanMotion.FastMs))
                    enter togetherWith exit
                },
                contentKey = { it },
                label = "library-tab",
            ) { tab ->
                if (tab == 0) {
                    SongsList(
                        tracks = state.tracks,
                        artworkRepository = container.artwork,
                        currentTrackId = playerState.currentTrack?.id,
                        isPlaying = playerState.isPlaying,
                        favoriteIds = favoriteIds,
                        onPlay = playList,
                        onToggleFavorite = viewModel::toggleFavorite,
                        contentPadding = contentPadding,
                        isLoading = state.loading,
                    )
                } else {
                    SongsList(
                        tracks = state.favorites,
                        artworkRepository = container.artwork,
                        currentTrackId = playerState.currentTrack?.id,
                        isPlaying = playerState.isPlaying,
                        favoriteIds = favoriteIds,
                        onPlay = playList,
                        onToggleFavorite = viewModel::toggleFavorite,
                        contentPadding = contentPadding,
                        isLoading = state.loading,
                    )
                }
            }
        }

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
                enter = fadeIn(tween(MilanMotion.MediumMs)) +
                    slideInVertically(tween(MilanMotion.MediumMs)) { it / 3 },
                exit = fadeOut(tween(MilanMotion.FastMs)) +
                    slideOutVertically(tween(MilanMotion.FastMs)) { it / 3 },
            ) {
                Column {
                    MiniPlayer(
                        state = playerState,
                        artworkRepository = container.artwork,
                        hazeState = hazeState,
                        onClick = onOpenPlayer,
                        onTogglePlay = container.player::togglePlayPause,
                        onNext = container.player::next,
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            GlassBottomBar(
                selected = mainTab,
                onSelect = { tab ->
                    // 搜索框只属于曲库页，切走时它会收起来。查询若继续生效，「喜欢」
                    // 列表就会被一个屏幕上看不见的条件过滤——用户只看到歌变少了，却
                    // 没有任何入口能发现原因，也无从清除它。
                    if (tab != mainTab) {
                        mainTab = tab
                        viewModel.setQuery("")
                    }
                },
                onRescan = {
                    viewModel.refresh()
                    Toast.makeText(context, "正在重新扫描曲库", Toast.LENGTH_SHORT).show()
                },
                hazeState = hazeState,
            )
        }

        // 排序面板从右上角展开，方向和触发按钮一致；遮罩层一起淡入。
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
    val background = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary
    val glass = LocalGlassColors.current

    Canvas(modifier = modifier) {
        // Pale-gold wash flowing down into the base background; the glows below
        // sit on top of it, so glass surfaces sample a warm, non-flat backdrop.
        val backdropTop = if (glass.isDark) Color(0xFF2E2414) else Color(0xFFF5E8C6)
        drawRect(
            brush = Brush.verticalGradient(listOf(backdropTop, background)),
        )

        val blend = if (glass.isDark) 0.30f else 0.20f

        fun glow(color: Color, cx: Float, cy: Float, radius: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = blend), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(cx, cy),
            )
        }

        glow(primary, size.width * 0.16f, size.height * 0.10f, size.width * 0.80f)
        glow(Color(0xFFE9DBAE), size.width * 0.92f, size.height * 0.28f, size.width * 0.64f)
        glow(Color(0xFFD9BE80), size.width * 0.42f, size.height * 0.86f, size.width * 0.74f)
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        hazeState = hazeState,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = "搜索",
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                modifier = Modifier.size(21.dp),
            )
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
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "清除",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                        modifier = Modifier.size(15.dp),
                    )
                }
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
                .padding(top = 64.dp, end = 16.dp)
                .width(156.dp),
            shape = RoundedCornerShape(20.dp),
            blurRadius = 28.dp,
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
                            text = mode.label,
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
    // 纯黑在 Night 上只有 1.05:1、等于隐形，所以墨色档在深色主题里换成 Moonlight。
    val inkAccent = if (LocalGlassColors.current.isDark) Moonlight else Color.Black

    val tabs = remember {
        listOf(
            // 应用图标的太极，已裁掉外圈描边并补成黑白两半（drawable-night 里反相），
            // 22dp 框内不额外缩放。
            BottomTab(
                icon = TabIcon.Plain(R.drawable.ic_tab_taiji),
                label = "米兰",
                accent = TabAccent.Ink,
            ),
            BottomTab(TabIcon.Vector(Icons.Rounded.Favorite), "喜欢", TabAccent.Liked),
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
                            onClick = { onSelect(index) },
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
                text = "需要访问音频文件",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (permanentlyDenied) {
                    "系统不再弹出授权窗口。请前往系统设置，在应用权限中手动开启音频访问。"
                } else {
                    "米兰音乐只读取设备上已存在的音频文件用于播放，不会上传或修改任何内容。"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            Button(onClick = if (permanentlyDenied) onOpenSettings else onRequest) {
                Text(if (permanentlyDenied) "前往系统设置" else "授权访问")
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
