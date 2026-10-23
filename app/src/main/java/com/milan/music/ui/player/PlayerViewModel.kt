package com.milan.music.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.milan.music.data.media.ArtworkRepository
import com.milan.music.data.model.Track
import com.milan.music.data.userdata.UserDataStore
import com.milan.music.di.AppContainer
import com.milan.music.play.PlaybackProgress
import com.milan.music.play.PlayerController
import com.milan.music.play.PlayerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放页的状态与动作。
 *
 * 存在的第一个理由不是"架构要有个 ViewModel"，而是**收藏过去有两条写路径**：列表页走
 * [com.milan.music.ui.library.LibraryViewModel]，播放页直接 `container.userData.toggleFavorite`。
 * 两条路径今天行为一致，只是因为那个动作恰好没有副作用；一旦要加"收藏数""最近收藏"、
 * 或者加一个撤销提示，播放页这条必然被漏掉。动作收进来之后只剩一个入口。
 *
 * 第二个理由是**UI 不该知道 [AppContainer]**：它带 `Context`、`ContentResolver`、`DataStore`，
 * 组件因此既不能 `@Preview` 也不能只注入状态做 UI 测试。这里把容器挡在 ViewModel 的
 * 构造函数里，UI 只看到状态和回调。
 */
internal class PlayerViewModel(
    private val player: PlayerController,
    private val artwork: ArtworkRepository,
    private val userData: UserDataStore,
) : ViewModel() {

    /**
     * 直接转发，**不套 `stateIn`**。
     *
     * [PlayerController.progress] 的 ticker 靠 `subscriptionCount > 0` 才运行（屏幕熄灭或
     * 播放页关闭时必须停，否则每小时 7 200 次主线程唤醒）。在外面再包一层常驻订阅，
     * 等于把这个门永远顶开 —— 转发给 `collectAsStateWithLifecycle` 才能保留"只有正在看的人
     * 才订阅"这条性质。
     */
    val playback: StateFlow<PlayerUiState> = player.state
    val progress: StateFlow<PlaybackProgress> = player.progress

    /**
     * 当前曲目是否已收藏。
     *
     * `distinctUntilChanged` 落在曲目 id 上：播放进度、缓冲、`isPlaying` 的每一次变化都会
     * 推一个新的 [PlayerUiState]，但只有换曲才可能改变收藏状态，不能让它们把整条
     * `userData` 订阅链（读一次 DataStore 文档、解一次 JSON）重新拉起来。
     */
    val isFavorite: StateFlow<Boolean> = combine(
        player.state.map { it.currentTrack?.id }.distinctUntilChanged(),
        userData.data,
    ) { trackId, user ->
        trackId != null && user.favorites.contains(trackId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleFavorite() {
        // 取"此刻的当前曲目"而不是调用点传进来的那一个：暂停、缓冲、翻页动画期间
        // `track` 的 UI 快照都可能落后一帧，收藏必须落到真正在播的那一条上。
        val trackId = player.state.value.currentTrack?.id ?: return
        viewModelScope.launch { userData.toggleFavorite(trackId) }
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    fun togglePlayPause() = player.togglePlayPause()

    fun previous() = player.previous()

    fun next() = player.next()

    /** 同步缓存里的封面，供轮播中央那一页在同一帧里取用。未命中返回 `null`。 */
    fun cachedCover(albumId: Long) = artwork.cachedPlayer(albumId)

    /** 同步缓存里的镜像封面，同样只给"已经解码过的那一页"用。 */
    fun cachedMirror(albumId: Long) = artwork.cachedReflection(albumId)

    /**
     * 把一首曲目的封面一次性解出来。
     *
     * 调用点是 `LaunchedEffect(track?.id)`，所以**生命周期跟合成走**：播放页关闭、或者
     * 又换了一首，这次加载就被取消。这是有意的 —— 把加载搬进 `viewModelScope` 会让
     * 播放页关着的时候依然为每一首新歌解码位图。
     *
     * 结果**一次发布、不分段**：中途写入一半的产物等于让画面看到"新封面配旧镜像"这种
     * 不可能的状态。代价是换曲后到解码完成之间的那一两帧里，播放页手上没有可用产物 ——
     * 上缘糊版退回色场、带子退回同步缓存。那是对的：宁可这一帧没有，也不要上一首的图
     * 停在别人的位置上。
     *
     * 次序有两条硬约束：
     *
     *  1. **`loadReflection` 必须在取封面之前**。它内部会先经 `loadPlayerArtwork` 把封面
     *     解码进 playerCache，所以紧跟着取封面必然缓存命中。反过来先取封面会踩到
     *     `loadPlayerArtwork` 的取消竞态（在飞的生产者被取消 → 返回 `null`，此刻缓存还是
     *     空的），接触层的上半边就会整块缺席 —— 真机表现是"过渡带里一点没糊，只有倒影
     *     变糊了"（第 51 批）。
     *  2. 推色只吃像素，放 `Dispatchers.Default`：96×96 的逐像素扫描在主线程上是可感的。
     *
     * 取封面失败时**不再重试第二次**。取消兜底已经由 [ArtworkRepository] 的 `MemoCache`
     * 承担 —— 生产者被取消时它会重读一次缓存（生产者可能在 `NonCancellable` 里已经写好）。
     * 外面再调一次 `loadPlayerArtwork`，在缓存确实是空的时候只会把解码再走一遍，失败路径
     * 的等待时间翻倍。
     */
    suspend fun loadArtwork(track: Track): PlayerArtwork {
        val mirror = artwork.loadReflection(track)
        val cover = artwork.loadPlayerArtwork(track)
        val haze = artwork.loadBackdrop(track)
        // 推色失败时 palette 是 null：调用方保留上一套颜色，不要切回兜底色（那是一次闪烁）。
        val palette = haze?.let { withContext(Dispatchers.Default) { paletteOfBitmap(it) } }
        return PlayerArtwork(
            trackId = track.id,
            cover = cover,
            mirror = mirror,
            haze = haze,
            palette = palette,
        )
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PlayerViewModel(
                    player = container.player,
                    artwork = container.artwork,
                    userData = container.userData,
                )
            }
        }
    }
}
