# 2026-10 安卓本地音乐播放器技术调研与 MilanMusic 适配方案

- 调研日期：2026-10-24
- 对象：`milan-music/`（`app` 单模块）
- 基线：AGP 9.4.1 / Kotlin 2.4.21 / Gradle 9.8.2 / compileSdk 37 / targetSdk 37 / minSdk 26
- 结论口径：**P0 = 本轮高收益低风险，P1 = 下一轮，P2 = 观察/评估**。这套编号与 `2026-10-23-code-review.md` 的“阻断级 P0”不是同一套口径，不要交叉引用编号。

---

## 一、结论摘要

**本轮不需要追新版本。** Media3 1.11.1 已经提供了这个项目缺的三块能力——通知跳转、播放恢复、媒体库浏览——项目都还没接上；把这三块做完，收益远大于升级到 1.12.0-alpha01 或迁移 Glance。

| 判断 | 结论 |
|---|---|
| Android 17 后台音频 | 现有 `MediaSessionService` + `mediaPlayback` FGS + 权限基本合规；小组件点击、媒体键属于 WIU 豁免场景，仍需真机用 hardening 验证 |
| 通知/锁屏点击 | 未设置 `setSessionActivity()`，`DefaultMediaNotificationProvider` 把 `null` 传给 `setContentIntent()`，点击没有跳转目标。**P0 最小修复** |
| 播放恢复 | 未实现 `onPlaybackResumption()`，未声明 `MediaButtonReceiver`；杀进程或重启后无法续播。**P0** |
| 媒体库浏览 | Manifest 声明了 `android.media.browse.MediaBrowserService`，但服务是 `MediaSessionService` 而不是 `MediaLibraryService`——等于对外宣称了未实现的浏览能力。**P0 迁移** |
| 系统 UI 恢复通知 | 官方文档明确：只有实现 `MediaLibraryService` 后才可用；与上一条是同一改造 |
| 小组件内存 | Android 17 上限为 `6 × 当前显示配置宽 × 高`，1080×2400 约 15.5 MB；当前 256×256 ARGB 封面约 262 KB，安全；完整 `updateAppWidget` 符合官方建议 |
| 媒体按钮 | API 33+ 的正确扩展点是 `setMediaButtonPreferences()`，不是自定义通知布局；**P1** |
| 性能工程 | Baseline Profile、Compose 稳定性、Media3 Inspector 都是 P1/P2，不影响本轮正确性 |

**一句话结论：** 这个项目现在缺的不是“更新的库”，而是“把已经声明、已经依赖的能力真正接完”。先把 `setSessionActivity()`、播放恢复、`MediaLibraryService` 三件事做完，再谈 1.12、Glance 和 Baseline Profile。

---

## 二、2026-10 技术基线

| 组件 | 当前项目 | 2026-10 最新稳定 | 观察版本 | 本轮判断 |
|---|---|---|---|---|
| Android API | compile/target 37 | Android 17 (API 37) | — | 保持 |
| Media3 | **1.11.1** | 1.11.1（2026-09-10） | 1.12.0-alpha01（2026-10-21） | 保持 1.11.1；**不要回退到 1.11.0** |
| AGP | 9.4.1 | 9.4.1 | — | 保持 |
| Kotlin | 2.4.21 | 2.4.21 | — | 保持 |
| Gradle | 9.8.2 | 9.8.2 | — | 保持 |
| Compose BOM | 2026.09.00 | 2026.09.00 | — | 保持 |
| DataStore | 1.2.1 | **1.3.0**（2026-10-21） | — | P2 升级，先读 release notes |
| Glance | 未使用 | 1.2.0 | 1.3.0-alpha02（2026-08-26） | P2 观察，不迁移 |
| Baseline Profile 插件 | 未使用 | 1.5.0 | 1.6.0-alpha01 | P2 |
| Benchmark Macro | 未使用 | 1.5.0 | 1.6.0-alpha01 | P2 |
| Media3 Inspector | 未使用 | 1.11.1 | 1.12.0-alpha01 | P2，调试期可临时加 |
| `media3-ui-compose` | 未使用 | 1.11.1 | 1.12.0-alpha01 | P2，不替换现有播放页 |

**为什么强调 1.11.1 不能回退：** 1.11.1 修复了 legacy `MediaBrowser` 连接异步 `onGetLibraryRoot()` / `onConnectAsync()` 时的主线程死锁（[#3393](https://github.com/androidx/media/issues/3393)）。P0-2 的 `MediaLibraryService` 曲库树正是异步回调；如果因为别的原因回退到 1.11.0，这个死锁会直接命中 Android Auto / 旧版 MediaBrowser 客户端。

---

## 三、现状核对（证据）

| 位置 | 现状 | 缺口 |
|---|---|---|
| `PlaybackService.kt:283-286` | `MediaSession.Builder(this, player).setBitmapLoader(loader).setCallback(sessionCallback).build()` | 没有 `setSessionActivity()` |
| `PlaybackService.kt:71-97` | 只重写了 `onAddMediaItems()` | 没有 `onPlaybackResumption()` |
| `AndroidManifest.xml:56-65` | 服务 action：`MediaSessionService` + `MediaBrowserService` | 服务不是 `MediaLibraryService`；没有 `MediaButtonReceiver` |
| `HomeScreen.kt:238-248` | 曲库就绪后 `playQueue(state.tracks, Random.nextInt(...))` | 冷启动不读上次队列，直接随机覆盖 |
| `PlayerController.kt:255-280` | `setMediaItems(items, index, 0L)`，随后固定 `shuffle=true`、`repeat=ALL`、`play()` | 没有起始位置、没有“恢复但不自动播”的入口 |
| `MusicWidgetProvider.kt:87-110` | 队列为空时 `libraryCache.load()` 后随机 `playQueue` | 冷启动路径在广播里直接起播，需 Android 17 WIU 验证 |
| `app/build.gradle.kts` | 无 `composeCompiler { stabilityConfigurationFile }`、无 Baseline Profile | P1/P2 |
| `libs.versions.toml` | `media3 = "1.11.1"`、`datastore = "1.2.1"` | DataStore 1.3.0 可升，但不急 |

---

## 四、六条技术主线

### 4.1 Android 17 后台音频加固：合规，但要验证豁免路径

Android 17 的音频加固分两层：

1. **对所有运行在 Android 17 上的应用生效**（不管 target 多少）：应用在后台时，必须有可见 Activity，或一个不是 `SHORT_SERVICE` 的前台服务，才能继续音频交互。
2. **target 37 额外要求**：后台应用的前台服务必须具有 while-in-use（WIU）能力。WIU 能力通常来自“用户可见时启动”或“用户主动操作触发”。

本项目的情况：

- 使用 `MediaSessionService`，Manifest 声明 `android:foregroundServiceType="mediaPlayback"`，并具备 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限。
- 播放由用户点击、媒体键或小组件触发；这些属于 WIU 豁免场景。官方文档明确列出：通知点击、**小组件交互**、**来自外部设备的媒体键事件**。
- 因此“小组件冷启动直接起播”理论上合规，但这是**推断**，不是真机结论。必须用 hardening 打开后验证。

验证命令：

```bash
adb shell cmd audio set-enable-hardening enable
adb logcat -s AudioHardening
# 复现：清进程 → 点小组件播放 → 观察是否有 level: full / partial
adb shell dumpsys audio | grep -i hardening
```

- `level: full`：应用有前台服务，但服务没有 WIU 能力。
- `level: partial`：应用根本没有前台服务。

如果小组件冷启动被静音，保守改法是：**队列为空时不在广播里直接起播，改为打开 App**，让播放发生在可见 Activity 之后。这比给广播加一个 `FOREGROUND_SERVICE` 启动路径更稳，也不会引入第二个 FGS。

**不建议：** 为了绕开限制加 `BOOT_COMPLETED` 自启播放、加第二个前台服务、或把 `mediaPlayback` 改成 `shortService`。前者不是用户意图，后两者会改变系统的媒体控制行为。

---

### 4.2 Media3 1.11.1 会话：可信控制器与通知点击

Media3 1.11 收紧了未重写 `onConnect` / `onConnectAsync` 的会话对不可信控制器的权限。项目没有重写这两个方法，所以默认行为是安全的：`ConnectionResult.AcceptedResultBuilder(session, controller)` 会按 `controller.isTrusted()` 自动选择 `DEFAULT_UNTRUSTED_SESSION_COMMANDS` / `DEFAULT_UNTRUSTED_PLAYER_COMMANDS`。

**不要**为了“显式一点”而手工传 `DEFAULT_SESSION_COMMANDS`。那会把不可信控制器也提升到可信命令集，正好抵消 1.11 的收紧。

真正的问题是通知点击：

- `DefaultMediaNotificationProvider` 会把 `session.getSessionActivity()` 直接传给 `NotificationCompat.Builder.setContentIntent()`。
- 项目从未调用 `setSessionActivity()`，所以这个值是 `null`。
- 结果：通知、锁屏、Android Auto 的“点封面/点通知”没有跳转目标；用户只能从最近任务回到 App。

这是 P0-1，修复成本极低。

**验证：** 播放中下拉通知 → 点通知主体 → 应回到 `MainActivity`；锁屏同理；Android Auto 的“正在播放”卡片点击同理。仅验证播放/暂停按钮不够，那些走的是 `PendingIntent` action，不依赖 `sessionActivity`。

---

### 4.3 播放恢复：从“进程活着”到“进程死了也能续上”

项目现在只有“进程活着”的恢复：`PlaybackService` 的队列在服务里，`PlayerController.release()` 也刻意不清 `PlaybackTrackIndex`，所以从播放页返回、配置变更都不丢队列。但以下场景全丢：

- 用户在最近任务里划掉 App，服务被销毁；
- 系统低内存杀进程；
- 设备重启；
- 蓝牙/耳机在服务未运行时发播放键。

Media3 的恢复入口是 `MediaSession.Callback.onPlaybackResumption()`。1.11.1 有 2 参数和 3 参数两个重载，3 参数版本带 `isForPlayback`：

```kotlin
override fun onPlaybackResumption(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    isForPlayback: Boolean,
): ListenableFuture<MediaSession.MediaItemsWithStartPosition>
```

两个分支的语义不同：

| 分支 | 调用时机 | 返回值用途 | 建议 |
|---|---|---|---|
| `isForPlayback = false` | 开机后为系统 UI 构建“最近播放”条目 | 只取 `startIndex` 位置的那一项做恢复通知 | **只返回当前项 + 完整元数据**，尽快完成 |
| `isForPlayback = true` | 用户按媒体键/蓝牙/系统 UI 恢复 | `MediaUtils.setMediaItemsWithStartIndexAndPosition()` 直接把列表设进播放器 | 返回**当前项 ±100 的元数据窗口**，控制分配与回调延迟 |

关键事实：**`onPlaybackResumption` 的返回列表不经过 `onAddMediaItems()`**。也就是说，恢复项必须自带 `MediaMetadata`；如果只返回 bare `mediaId + uri`，恢复后通知会没有标题、艺人、封面。

另一个关键事实：**系统 UI 的“恢复播放”通知只有在实现 `MediaLibraryService` 后才可用**。所以 4.3 的 P0-3 先做“蓝牙/媒体键恢复 + App 冷启动恢复”，P0-2 迁移到 `MediaLibraryService` 后再解锁系统 UI 恢复通知。

#### 快照存储设计

不要复用 `LibraryCacheStore`。它在每次 `save()` 时重编码全库 JSON（5 000 首约 1 MB），而播放位置快照每 5 秒就要写一次；复用它等于每 5 秒重写 1 MB。

新增独立的 `PlaybackSnapshotStore`，用单独的 Preferences DataStore（或普通 JSON 文件 + 原子 rename），payload 控制在几十 KB：

```kotlin
@Serializable
data class PlaybackSnapshot(
    val trackIds: List<Long> = emptyList(),
    /** 原始队列（未 shuffle）中的索引，对应 Player.currentMediaItemIndex。 */
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val shuffleModeEnabled: Boolean = true,
    val repeatMode: Int = Player.REPEAT_MODE_ALL,
    val wasPlaying: Boolean = false,
    /** 当前项的元数据，供 isForPlayback=false 的分支快速返回。 */
    val current: SnapshotTrack? = null,
    val updatedAtMs: Long = 0L,
)

@Serializable
data class SnapshotTrack(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
)
```

写入时机（全部在 `PlaybackService` 里，服务拥有 player）：

- `onMediaItemTransition`：换曲立即写；
- `onIsPlayingChanged`：播放/暂停立即写；
- `onPlaybackStateChanged`：`STATE_ENDED` / `STATE_IDLE` 写；
- 播放中每 5 秒写一次位置；
- `onTaskRemoved`：`stopSelf()` 之前写。

不要把 `PlayerController` 的 500 ms 进度 ticker 直接接到快照写入上。那个 ticker 只在播放页可见时运行，且频率是快照需要的 10 倍。

#### 冷启动恢复

`HomeScreen` 现在的逻辑是“曲库就绪 → 随机开播”。改成“先尝试恢复快照，失败再随机”：

```kotlin
LaunchedEffect(hasAudioPermission, state.tracks.isNotEmpty()) {
    if (!hasAudioPermission || state.tracks.isEmpty() || autoStarted) return@LaunchedEffect
    autoStarted = true
    if (container.player.state.value.queueSize != 0) return@LaunchedEffect

    val restored = container.playbackSnapshot.restore(state.tracks)
    if (restored != null) {
        container.player.playQueue(
            tracks = restored.tracks,
            startIndex = restored.startIndex,
            startPositionMs = restored.positionMs,
            shuffle = restored.shuffleModeEnabled,
            repeatMode = restored.repeatMode,
            autoPlay = restored.wasPlaying,
        )
    } else {
        container.player.playQueue(state.tracks, Random.nextInt(state.tracks.size))
    }
}
```

`PlayerController.playQueue()` 需要增加可选参数：

```kotlin
fun playQueue(
    tracks: List<Track>,
    startIndex: Int,
    startPositionMs: Long = 0L,
    shuffle: Boolean = true,
    repeatMode: Int = Player.REPEAT_MODE_ALL,
    autoPlay: Boolean = true,
) {
    // ...
    mediaController.setMediaItems(items, index, startPositionMs)
    mediaController.shuffleModeEnabled = shuffle
    mediaController.repeatMode = repeatMode
    mediaController.prepare()
    if (autoPlay) mediaController.play() else mediaController.pause()
}
```

`autoPlay = restored.wasPlaying` 是对“打开应用就开始放”的一个例外：用户上次是暂停状态，冷启动不应该突然出声。如果产品坚持“每次打开都播”，把这里改成 `true` 即可，但要在 README 里写清这是有意覆盖用户暂停状态。

#### 恢复索引的映射

快照里存的是全量 `trackIds`，但曲库可能已经变化（删歌、换存储、重新扫描）。恢复时不能直接按 `currentIndex` 取，要先按 id 映射，再找回当前曲目的新索引：

```kotlin
fun restore(library: List<Track>): RestoredQueue? {
    if (trackIds.isEmpty()) return null
    val currentId = trackIds.getOrNull(currentIndex) ?: return null
    val byId = library.associateBy { it.id }
    val tracks = trackIds.mapNotNull(byId::get)
    if (tracks.isEmpty()) return null
    val newIndex = tracks.indexOfFirst { it.id == currentId }
    if (newIndex < 0) return null
    return RestoredQueue(
        tracks = tracks,
        startIndex = newIndex,
        positionMs = positionMs.coerceAtLeast(0L),
        shuffleModeEnabled = shuffleModeEnabled,
        repeatMode = repeatMode,
        wasPlaying = wasPlaying,
    )
}
```

如果只找回一部分曲目，队列会变短，但当前曲目不会错位。不要在映射前用 `coerceIn` 把旧索引硬套到新列表上。

#### `onPlaybackResumption` 骨架

```kotlin
private fun resumption(
    snapshot: PlaybackSnapshot,
    library: List<Track>,
    isForPlayback: Boolean,
): MediaSession.MediaItemsWithStartPosition? {
    val currentId = snapshot.trackIds.getOrNull(snapshot.currentIndex) ?: return null
    val byId = library.associateBy { it.id }
    val currentTrack = byId[currentId] ?: snapshot.current?.toTrack()

    if (!isForPlayback) {
        // 只服务系统 UI 的“最近播放”条目：一项、带元数据、立即完成。
        val item = currentTrack?.toMediaItem(withMetadata = true)
            ?: MediaItem.Builder().setMediaId(currentId.toString()).build()
        return MediaSession.MediaItemsWithStartPosition(
            listOf(item),
            /* startIndex = */ 0,
            snapshot.positionMs,
        )
    }

    // 恢复播放：当前项 ±100 的窗口，元数据完整；窗口外不返回。
    val from = (snapshot.currentIndex - RESUMING_WINDOW).coerceAtLeast(0)
    val to = (snapshot.currentIndex + RESUMING_WINDOW)
        .coerceAtMost(snapshot.trackIds.lastIndex)
    val items = (from..to).map { i ->
        val id = snapshot.trackIds[i]
        byId[id]?.toMediaItem(withMetadata = true)
            ?: MediaItem.Builder().setMediaId(id.toString()).build()
    }
    return MediaSession.MediaItemsWithStartPosition(
        items,
        snapshot.currentIndex - from,
        snapshot.positionMs,
    )
}
```

`RESUMING_WINDOW = 100` 是起点，不是定论。真机要测两件事：

1. 5 000 首队列、201 项带元数据时，回调从进入到 `future.set()` 的耗时；
2. 恢复后连续切歌 20 次，通知/锁屏的标题、封面是否仍然正确。

#### 已知取舍：队列会被截断

上面的 P0 设计返回 201 项，恢复后播放器的队列就是 201 项。用户从当前曲目往后切 100 首，队列会到头。这是为了控制冷启动分配和 Binder payload 的刻意取舍。

如果这个截断不可接受，有两个替代方案：

- **全量 ID + 元数据窗口**：返回全部 `trackIds` 的 `MediaItem`，只给当前 ±100 挂 `MediaMetadata`。队列长度保住，Binder payload 主要是 bare item；需要实测 5 000 项时的回调耗时和 Binder 上限。
- **P1 懒扩展**：P0 先窗口，P1 在 `onMediaItemTransition` 里用 `Player.replaceMediaItem()` 把新当前项及邻居的元数据补上，并逐步把全量 ID 接回队列。

无论选哪个，都不要“返回全量 + 全量元数据”。5 000 个 `MediaMetadata` 的构建和 Parcel 成本，正是项目当初用 `PlaybackTrackIndex` 避免的那笔账。

---

### 4.4 MediaLibraryService：把已声明的浏览能力真正实现

当前 Manifest 同时声明了：

```xml
<action android:name="androidx.media3.session.MediaSessionService" />
<action android:name="android.media.browse.MediaBrowserService" />
```

但服务类是 `MediaSessionService`。这等于告诉系统“我可以被浏览”，实际 `onGetLibraryRoot()` / `onGetChildren()` 都没有实现。Android Auto、旧版 MediaBrowser、系统 UI 的最近播放条目都会按这个声明来连接，然后拿到“不支持”。

迁移到 `MediaLibraryService` 是 P0-2。它同时解决三件事：

1. 兑现 `MediaBrowserService` 的声明；
2. 让 Android Auto 能浏览曲库；
3. 解锁系统 UI 的播放恢复通知（4.3 的 P0-3 依赖它）。

#### 服务改造

```kotlin
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null

    private val sessionCallback = object : MediaLibrarySession.Callback {
        // 保留现有的 onAddMediaItems()，不要删。
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<List<MediaItem>> { /* 现有实现 */ }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(
                    MediaItem.Builder()
                        .setMediaId(ROOT_ID)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(getString(R.string.library_root_title))
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .build(),
                        )
                        .build(),
                    params,
                ),
            )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                runCatching { libraryTree.children(parentId, page, pageSize) }
                    .onSuccess { future.set(LibraryResult.ofItemList(it, params)) }
                    .onFailure { future.setException(it) }
            }
            return future
        }
    }

    override fun onCreate() {
        super.onCreate()
        // ... ExoPlayer 构建保持不变 ...
        mediaSession = MediaLibrarySession.Builder(this, player, sessionCallback)
            .setSessionActivity(sessionActivity())
            .setBitmapLoader(loader)
            .build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaLibrarySession? = mediaSession
}
```

#### 曲库树的结构

```text
root
├── songs          （可浏览，子项是全部歌曲，按标题排序）
├── albums         （可浏览，子项是专辑容器）
│   └── album:<albumId>  （可浏览，子项是该专辑歌曲，按 trackNumber）
└── artists        （可浏览，子项是艺人容器）
    └── artist:<encodedName> （可浏览，子项是该艺人歌曲）
```

- **容器 ID** 用前缀：`lib:root`、`lib:songs`、`lib:albums`、`lib:album:<id>`、`lib:artists`、`lib:artist:<encodedName>`。
- **可播放项 ID** 必须用纯数字 `track.id.toString()`，与 `PlayerController.playQueue()` 写入的 `mediaId` 保持一致。否则 `PlaybackTrackIndex` 查不到，通知会丢元数据。
- 容器项的 `MediaMetadata` 设 `isBrowsable = true`、`isPlayable = false`；歌曲项设 `isBrowsable = false`、`isPlayable = true`，并带 `title`、`artist`、`albumTitle`、`durationMs`、`artworkUri`。
- 专辑/艺人容器不要一次性把子项全查出来；`onGetChildren` 只返回当前页。

#### 分页的两个坑

```kotlin
internal fun pageSlice(total: Int, page: Int, pageSize: Int): IntRange {
    if (total <= 0 || pageSize <= 0 || page < 0) return IntRange.EMPTY
    val from = page.toLong() * pageSize
    if (from >= total.toLong()) return IntRange.EMPTY
    val toExclusive = (from + pageSize).coerceAtMost(total.toLong())
    return from.toInt() until toExclusive.toInt()
}
```

1. `page` 从 **0** 开始，不是 1。
2. `page * pageSize` 用 `Long` 算，再转回 `Int`。`Int` 乘法在超大页时可能溢出成负数，`until` 会抛异常或返回空。

这个函数是纯函数，应该放 `test/` 做 JVM 测试：`total=0`、`page=0`、`page` 超出末页、`pageSize` 大于总数、`page * pageSize` 接近 `Int.MAX_VALUE`。

#### Manifest

```xml
<service
    android:name=".play.PlaybackService"
    android:exported="true"
    android:foregroundServiceType="mediaPlayback"
    tools:ignore="ExportedService">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaLibraryService" />
        <action android:name="android.media.browse.MediaBrowserService" />
    </intent-filter>
</service>

<receiver
    android:name="androidx.media3.session.MediaButtonReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MEDIA_BUTTON" />
    </intent-filter>
</receiver>
```

两个 action 都要保留：`androidx.media3.session.MediaLibraryService` 给 Media3 客户端，`android.media.browse.MediaBrowserService` 给平台/旧版客户端做向后兼容。`MediaButtonReceiver` 必须 `exported="true"`，否则系统媒体键送不进来。

#### 不要做的事

- 不要为了“先让声明不假”而删掉 `MediaBrowserService` action。删掉会同时失去 Android Auto 和系统 UI 的发现能力，问题只是从“声明了没实现”变成“根本没声明”。
- 不要在 `onGetChildren` 里每次 `libraryCache.snapshot()` 重读 1 MB JSON。服务内缓存一份 `List<Track>`，用 `LibraryCacheStore` 的 generation/时间戳决定是否刷新；浏览是低频操作，但不能每次翻页都解一遍全库。
- 不要一次性构建全量 `MediaItem` 树。5 000 首歌的 `MediaItem + MediaMetadata` 在服务里常驻，内存和构建时间都不划算；按页构建，页外不保留。

---

### 4.5 通知、锁屏与媒体按钮：API 33+ 的正确扩展点

API 33+ 的系统媒体控制不再使用应用的自定义通知布局。`Notification.MediaStyle` 的 compact view 由系统模板决定，自定义 `RemoteViews` 会被忽略。项目已经明确否决过“自定义通知布局”，这个判断在 2026 年仍然正确。

正确的扩展点是 **媒体按钮偏好**（media button preferences）：

- `MediaSession.Builder.setMediaButtonPreferences(List<CommandButton>)`：会话级默认；
- `MediaSession.setMediaButtonPreferences(...)`：运行时更新；
- `ConnectionResult.AcceptedResultBuilder.setMediaButtonPreferences(...)`：按控制器下发。

API 33+ 的系统媒体控制最多显示 5 个按钮，`CommandButton` 的 `slots` 决定它落在哪个位置：`SLOT_CENTRAL`、`SLOT_BACK`、`SLOT_FORWARD`、`SLOT_BACK_SECONDARY`、`SLOT_FORWARD_SECONDARY`、`SLOT_OVERFLOW`。

收藏按钮的实现要点：

```kotlin
private val favoriteCommand = SessionCommand(
    "com.milan.music.session.FAVORITE",
    Bundle.EMPTY,
)

private fun favoriteButton(favorite: Boolean): CommandButton =
    CommandButton.Builder(
        if (favorite) CommandButton.ICON_HEART_FILLED
        else CommandButton.ICON_HEART_UNFILLED,
    )
        .setSessionCommand(favoriteCommand)
        .setDisplayName(getString(R.string.favorite))
        .setSlots(CommandButton.SLOT_FORWARD_SECONDARY)
        .build()

override fun onConnectAsync(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
): ListenableFuture<MediaSession.ConnectionResult> {
    // 只给系统媒体通知控制器下发收藏按钮；不要给所有控制器。
    if (session.isMediaNotificationController(controller)) {
        return Futures.immediateFuture(
            MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setMediaButtonPreferences(listOf(favoriteButton(isCurrentFavorite())))
                .build(),
        )
    }
    // 其他控制器用默认命令集；AcceptedResultBuilder(session, controller)
    // 会按 isTrusted() 自动选择可信/不可信命令集。
    return Futures.immediateFuture(
        MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build(),
    )
}

override fun onCustomCommand(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
    customCommand: SessionCommand,
    args: Bundle,
): ListenableFuture<SessionResult> {
    if (customCommand.customAction == favoriteCommand.customAction) {
        serviceScope.launch {
            val id = trackIndex?.get(session.player.currentMediaItem?.mediaId)?.id
            if (id != null) {
                userData.toggleFavorite(id)
                session.setMediaButtonPreferences(listOf(favoriteButton(isFavorite(id))))
            }
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }
    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
}
```

注意：

- `session.isMediaNotificationController(controller)` 是 `MediaSession` 的方法，不是 `ControllerInfo` 的方法。1.11.1 的 `ControllerInfo` 没有这个 API。
- Android Auto 的自定义命令授权是另一条路径：用 `session.isAutoCompanionController(controller)` 判断，再把 `favoriteCommand` 加进 `availableSessionCommands`。不要因为“收藏按钮在通知上能用”就默认 Auto 也能用。
- 收藏状态变化后要主动 `setMediaButtonPreferences()` 刷新图标；系统 UI 不会因为 `UserDataStore` 变了就自动重绘按钮。
- 这套逻辑放在 P1，不要和 P0 的恢复/迁移混在一个改动里。P0 先把会话和曲库打通，P1 再加按钮。

---

### 4.6 小组件与性能工程：Android 17 内存上限、Baseline Profile、Compose 稳定性

#### 小组件内存

Android 17 对 target 37 的应用在 `AppWidgetManager.updateAppWidget()` 时计算 `RemoteViews` 里所有图片数据的总内存，超过 `mMaxWidgetBitmapMemory` 就抛 `IllegalArgumentException`。上限是：

```text
6 × 当前显示配置的宽 × 高
```

1080×2400 的屏幕约 **15.5 MB**。当前小组件封面是 256×256 ARGB_8888：

```text
256 × 256 × 4 = 262,144 字节 ≈ 0.25 MB
```

远低于上限，安全。加上图标、文字、布局，实际序列化内存也远不到 1 MB。用 `adb shell dumpsys appwidget` 可以看到每个实例的实际占用，发布前跑一次即可。

两个不要：

- **不要**为了“省内存”把完整 `updateAppWidget()` 改成 `partiallyUpdateAppWidget()` 换图。官方明确说：部分更新会把新 action 合并进 `system_server` 里已有的 `RemoteViews`，但不会驱逐旧的图片 action；换封面时旧 bitmap 仍然被缓存，反而更容易累积到上限。项目现在用完整 `updateAppWidget()`，是对的。
- **不要**盲目把封面改成 `RGB_565`。当前封面是圆角透明输出（`PorterDuff.Mode.SRC_IN` 清掉四角），`RGB_565` 没有 alpha，圆角会变黑或变白。262 KB 离 15.5 MB 很远，这里没有优化收益。如果将来改成不透明方形封面，再考虑 `RGB_565`。

真正值得做的是 P1：小组件冷启动路径现在 `libraryCache.load()` 全库 JSON 再随机起播。5 000 首时这是一次约 1 MB 的解析，虽然能跑完，但没必要。P0-3 的 `PlaybackSnapshotStore` 落地后，小组件可以优先用快照恢复上次队列；没有快照再退回 `libraryCache.load()`。这既缩短广播处理时间，也让“点小组件继续上次播放”成为一致行为。

#### Baseline Profile（P2）

Baseline Profile 插件 1.5.0 已稳定，配套 `benchmark-macro-junit4` 1.5.0。对这个项目的收益主要在：

- 冷启动到首帧（曲库缓存解码 + Compose 首屏）；
- 曲库列表快速滚动（5 000 项）；
- 播放页打开时的封面解码/取色。

成本是要加 `baselineprofile` 模块、`androidTest` 设备测试、每次发版跑一次生成。项目当前没有 `androidTest`，这是一笔真实的基础设施投入。P2，等 P0/P1 稳定后再做。

#### Compose 稳定性（P1）

项目没有 `@Immutable` / `@Stable` 注解，也没有 `stabilityConfigurationFile`。`Track` 里的 `android.net.Uri` 不是 Compose 的稳定类型，`List<Track>` 也会被推断为不稳定；这会让 `LibraryUiState`、`PlayerUiState` 相关的 composable 更容易重组。

不要凭感觉加注解。先用 Compose 编译器报告找出真正的不稳定类：

```kotlin
// app/build.gradle.kts
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose-reports")
    stabilityConfigurationFile = rootProject.layout.projectDirectory.file("compose_stability.conf")
}
```

然后只把“确实不可变、但编译器看不出来”的类写进 `compose_stability.conf`，例如 `com.milan.music.data.model.Track`。`Track` 是 data class，字段都是 val，`Uri` 本身不可变；这是适合声明的。不要给带 `MutableState`、`SnapshotStateList` 或可变集合的类加 `@Immutable`，那会让 Compose 跳过本应发生的重组。

#### Media3 Inspector（P2）

`media3-inspector` 1.11.1 可以在调试包里看会话、控制器、媒体项、元数据。它不适合进 release 包，但排查“通知为什么没封面”“恢复后为什么没标题”时比 logcat 快。P2 作为调试工具引入，不要作为运行时依赖。

#### DataStore 1.3.0（P2）

DataStore 1.3.0 已于 2026-10-21 稳定。项目在 1.2.1。升级前读 release notes，重点看 `DataStore` 的异常行为和 `preferencesDataStore` 的迁移说明。这个升级和本轮 P0 无关，不要顺手带上。

#### Glance（P2）

Glance 1.2.0 是当前稳定版，1.3.0-alpha02 还在 alpha。项目的小组件是 2×2、一个封面、一个标题、一个播放按钮，用经典 `RemoteViews` 已经足够；迁移到 Glance 的收益是声明式布局和响应式尺寸，成本是重写整个 `MusicWidgetProvider`、引入 Glance 依赖、重新验证 Android 17 内存上限。P2，等 Glance 1.3 稳定且小组件要加内容时再做。

---

## 五、P0 适配清单

### P0-1　`setSessionActivity()`：让通知和锁屏点击有落点

**改什么**

`PlaybackService.onCreate()` 里给 `MediaSession.Builder` / `MediaLibrarySession.Builder` 加 `setSessionActivity(...)`。

**为什么**

`DefaultMediaNotificationProvider` 把 `session.getSessionActivity()` 直接传给 `setContentIntent()`。项目没有设置，通知主体、锁屏、Android Auto 卡片的点击都没有目标。这不是“体验优化”，是通知点击功能缺失。

**怎么改**

```kotlin
private fun sessionActivity(): PendingIntent {
    val intent = Intent(this, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addCategory(Intent.CATEGORY_LAUNCHER)
        // MainActivity 是 singleTop；这组 flag 让通知点击复用已有任务，
        // 而不是在播放页之上再压一个 MainActivity。
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    return PendingIntent.getActivity(
        this,
        REQUEST_SESSION_ACTIVITY,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
```

```kotlin
mediaSession = MediaSession.Builder(this, player)
    .setSessionActivity(sessionActivity())
    .setBitmapLoader(loader)
    .setCallback(sessionCallback)
    .build()
```

**风险**

- `FLAG_ACTIVITY_CLEAR_TOP` 会把 `MainActivity` 之上的页面清掉；本项目只有 `MainActivity` 一个 Activity，播放页是 Compose 叠加层，不受影响。
- 通知点击只会回到 App，不会自动展开播放页。如果产品希望“点通知直接进播放页”，在 Intent 里加 `EXTRA_OPEN_PLAYER`，在 `MainActivity.onNewIntent()` 里消费一次，再把状态传给 `MilanApp`。这是 P1 打磨，不要用全局 mutable state 绕过 `singleTop` 的 `onNewIntent`。

**验证**

- 播放中下拉通知 → 点通知主体 → 回到 App；
- 锁屏 → 点媒体卡片 → 回到 App；
- 通知的播放/暂停/上一首/下一首按钮仍然正常（它们走 action，不依赖 `sessionActivity`）；
- Android Auto 的“正在播放”卡片点击（如有 DHU）。

---

### P0-2　`MediaLibraryService` 迁移 + 曲库树

**改什么**

- `PlaybackService` 从 `MediaSessionService` 改为 `MediaLibraryService`；
- `MediaSession` 改为 `MediaLibrarySession`；
- 新增 `onGetLibraryRoot()`、`onGetChildren()`、`onGetItem()`；
- Manifest 的 action 从 `MediaSessionService` 改为 `MediaLibraryService`，保留 `MediaBrowserService`；
- 新增 `MediaButtonReceiver`。

**为什么**

Manifest 已经声明了 `android.media.browse.MediaBrowserService`，但服务没有实现浏览接口。这是“对外宣称了未实现的能力”。同时，系统 UI 的播放恢复通知只有 `MediaLibraryService` 才可用。

**怎么改**

服务骨架见 4.4。曲库树按 `root → songs / albums / artists` 构建，可播放项 `mediaId` 用纯数字 track id，容器项用 `lib:` 前缀。

`onGetItem()` 至少要实现容器和歌曲的按 ID 查询；`onSearch()` / `onGetSearchResult()` 可以先返回 `RESULT_ERROR_NOT_SUPPORTED`，P1 再补搜索。

**风险**

- `MediaLibraryService` 的 `onGetSession()` 返回类型是 `MediaLibrarySession?`，不是 `MediaSession?`；现有 `onTaskRemoved` 里的 `mediaSession?.player` 仍然可用。
- 曲库树构建在服务里，必须做服务内缓存；不要每次 `onGetChildren` 都重读 `LibraryCacheStore`。
- 1.11.1 的 #3393 修复是前提；不要回退到 1.11.0。
- Android Auto 的浏览行为需要 DHU 或真车验证；没有条件时，至少用 `adb shell dumpsys media_session` 确认服务被发现、`onGetChildren` 有返回。

**验证**

- `adb shell dumpsys media_session` 能看到 `MediaLibraryService`；
- 旧版 `MediaBrowser` 客户端（或 Media3 demo 的 browser）能拿到 root、songs、albums、artists；
- 从曲库树点一首歌，能起播且通知有标题/封面；
- 翻页到最后一页不崩、不返回重复项；
- 杀进程后系统 UI 出现恢复通知（与 P0-3 一起验证）。

---

### P0-3　播放快照 + `onPlaybackResumption()` + `MediaButtonReceiver` + 冷启动恢复

**改什么**

- 新增 `PlaybackSnapshotStore`（独立 DataStore，不复用 `LibraryCacheStore`）；
- `PlaybackService` 增加快照写入（换曲、播放/暂停、每 5 秒、`onTaskRemoved`）；
- 实现 `onPlaybackResumption()` 的 `isForPlayback=false/true` 两个分支；
- `PlayerController.playQueue()` 增加起始位置、shuffle/repeat、`autoPlay` 参数；
- `HomeScreen` 冷启动先恢复快照，失败再随机；
- Manifest 声明 `MediaButtonReceiver`。

**为什么**

项目现在只有“服务活着”的恢复。划掉 App、系统杀进程、设备重启、蓝牙在服务未运行时发播放键，都会丢队列。`onPlaybackResumption()` 是 Media3 提供的标准恢复入口；`MediaButtonReceiver` 是媒体键在服务未运行时把事件送进来的前提。

**怎么改**

快照模型、写入时机、`restore()` 映射、`onPlaybackResumption()` 骨架见 4.3。关键点再列一遍：

- `isForPlayback=false` 只返回当前项 + 完整元数据，`startIndex=0`；
- `isForPlayback=true` 返回当前项 ±100 的窗口，`startIndex = currentIndex - from`；
- 返回项必须自带 `MediaMetadata`，因为不经过 `onAddMediaItems()`；
- 在完成 future 之前设置 `shuffleModeEnabled` / `repeatMode`，且这些 player 调用要在主线程；
- 快照写入用 `Dispatchers.IO`，不要在主线程重写 JSON。

**风险**

- **队列截断**：P0 的 201 项窗口会让恢复后的队列变短。接受不了就改用“全量 ID + 元数据窗口”，并实测 Binder 上限。
- **Binder 上限**：5 000 项 bare item 的 `MediaItemsWithStartPosition` 可能接近 Binder 限制；201 项带元数据通常安全，但仍要用 5 000 首库实测。
- **元数据缺失**：如果快照里的 `current` 为空、曲库缓存也没命中，`isForPlayback=false` 会返回 bare item，恢复通知没有标题。快照写入时尽量从 `trackIndex` 或 `player.currentMediaItem.mediaMetadata` 补上 `current`。
- **恢复位置越界**：曲目变短后 `positionMs` 可能超过新时长；`setMediaItems` 会自行 clamp，但 UI 进度要能接受。可以在 `restore()` 里按 `Track.duration` 再 clamp 一次。
- **自动播放策略**：`autoPlay = wasPlaying` 是对“打开即播”的例外；产品如果坚持每次开播，改成 `true`，但要在 README 说明。

**验证**

- 播放中划掉 App → 按蓝牙/耳机播放键 → 恢复上次曲目和位置（P0-3 单独可验）；
- 杀进程 → 重新打开 App → 恢复上次队列，不随机；
- 暂停后杀进程 → 重新打开 → 恢复队列但保持暂停（如果采用 `wasPlaying`）；
- 设备重启 → 系统 UI 出现恢复通知（需要 P0-2）；
- 恢复后连续切 20 首，通知标题/封面正确；
- 5 000 首库测 `onPlaybackResumption` 回调耗时和 Binder 异常。

---

### 实施顺序

推荐 **P0-1 → P0-3 → P0-2**：

1. **P0-1** 最小、独立、当天可验证；
2. **P0-3** 不依赖 `MediaLibraryService`：`onPlaybackResumption()` 是 `MediaSession.Callback` 的方法，普通 `MediaSessionService` 也能用；蓝牙/媒体键恢复和 App 冷启动恢复可以先落地；
3. **P0-2** 迁移到 `MediaLibraryService`，解锁系统 UI 恢复通知和 Android Auto 浏览。

如果希望“系统 UI 恢复通知”一次到位，也可以 **P0-1 → P0-2 → P0-3**。两种顺序都行，但 P0-2 和 P0-3 会改同一个 `sessionCallback`，如果同一条分支做，建议一次把 `MediaLibrarySession.Callback` 写全，避免两次改同一个文件。

---

## 六、P1 适配清单

### P1-1　会话安全回归验证

Media3 1.11 收紧不可信控制器后，项目没有重写 `onConnect` / `onConnectAsync`，默认行为是安全的。P1 要做的不是改代码，而是把回归验证固定下来：

- 自带 `MediaController` 能连接并控制；
- 系统媒体通知控制器能连接；
- 蓝牙/耳机媒体键能控制；
- 不可信第三方控制器只能拿到有限命令；
- 如果将来为收藏按钮重写 `onConnectAsync`，必须用 `AcceptedResultBuilder(session, controller)`，不要手工传 `DEFAULT_SESSION_COMMANDS`。

验证工具：`adb shell dumpsys media_session`、`adb shell input keyevent 85/87/88`、蓝牙真机、Android Auto DHU。

### P1-2　富元数据

当前 `PlaybackService.withMetadata()` 只设置 `title`、`artist`、`albumTitle`、`artworkUri`。补上：

- `durationMs`：系统 UI 进度、Android Auto 时长；
- `trackNumber`：专辑内序号；
- `year`：发行年；
- `albumArtist`：合辑/多艺人专辑的正确归类（需要给 `Track` 和 `LibraryCacheStore` 加字段，MediaStore 的 `ALBUM_ARTIST` 在 API 30+ 可用，低版本要有兜底）。

`MediaMetadata.Builder` 在 1.11.1 已确认支持这些字段。补的时候只改 `withMetadata()` 和 `MediaLibraryService` 的歌曲项构建，不要动 `PlayerController` 的 bare item 设计。

> 2026-10-24 已实施：`durationMs` / `trackNumber` / `year` / `albumArtist` 进入会话元数据与曲库树歌曲项；`albumArtist` 走 `Track` + `LibraryCacheStore` + MediaStore `ALBUM_ARTIST`（API 30+，低版本回退 `artist`）。快照格式同步带上这三个字段，旧快照靠默认值解码。

### P1-3　媒体按钮偏好（收藏）

见 4.5。先只对 `session.isMediaNotificationController(controller)` 下发收藏按钮；Android Auto 的自定义命令授权单独用 `isAutoCompanionController()` 处理。收藏状态变化后主动刷新按钮图标。

验证：API 33+ 通知显示心形按钮；点击切换收藏；图标更新；Android Auto 不显示未授权的自定义按钮；API 26-32 不崩（这些版本没有系统媒体按钮偏好，代码要走兼容分支）。

### P1-4　Android 17 小组件硬化

- 用 `adb shell dumpsys appwidget` 记录当前实例的实际内存；
- 用 `adb shell cmd audio set-enable-hardening enable` + `adb logcat -s AudioHardening` 验证小组件冷启动不被静音；
- 如果被静音，队列为空时改为打开 App，不在广播里直接起播；
- 小组件冷启动优先用 `PlaybackSnapshotStore` 恢复上次队列，减少 `libraryCache.load()` 的 1 MB 解析；
- 保持完整 `updateAppWidget()`，不要用 `partiallyUpdateAppWidget()` 换图；
- 圆角透明封面保留 ARGB_8888，不要为了省 262 KB 改成 RGB_565。

### P1-5　Compose 稳定性配置

- 打开 Compose 编译器报告，列出不稳定类；
- 只给“确实不可变、编译器看不出来”的类加 `stabilityConfigurationFile` 条目；
- 不要给含 `MutableState` / `SnapshotStateList` / 可变集合的类加 `@Immutable`；
- 用 Macrobenchmark 或至少 `adb shell dumpsys gfxinfo` 对比滚动帧率，确认配置有收益。

> 2026-10-24 执行结果：报告已打开（`app/build/compose_compiler/`），**未添加** `stabilityConfigurationFile` —— 21 个 unstable 类里 20 个是 Repository/Controller/ViewModel 这类本来就该可变的持有者；唯一不可变的 `Track` 单独标 stable 也换不来跳过（同一行里还有 `ArtworkRepository` / `StateFlow`）。数据与理由见 [2026-10-24-p1-compose-stability.md](2026-10-24-p1-compose-stability.md)。

---

## 七、P2 适配清单

| 项 | 当前 | 建议 | 触发条件 |
|---|---|---|---|
| Baseline Profile | 无 | 插件 1.5.0 + `benchmark-macro-junit4` 1.5.0 | P0/P1 稳定，且愿意维护 `androidTest` 设备测试 |
| Glance 迁移 | 经典 `RemoteViews` | Glance 1.2.0 | 小组件要加内容/响应式尺寸，或 Glance 1.3 稳定 |
| `media3-ui-compose` | 无 | 1.11.1 | 只想参考 API，不替换现有播放页 |
| Media3 Inspector | 无 | 1.11.1，仅 debug | 排查会话/元数据/恢复问题 |
| DataStore 1.3.0 | 1.2.1 | 升级前读 release notes | 有明确收益或修 bug 时 |
| Media3 1.12 | 1.11.1 | 观察 1.12.0-beta/rc | 1.12 稳定且 release notes 有本项目需要的修复 |

---

## 八、明确不建议做

| 方向 | 为什么不做 |
|---|---|
| 音频 offload | 已在 2026-10-19 评估：参考设备 `getDirectPlaybackSupport` 只返回 `DIRECT_PLAYBACK_BITSTREAM_SUPPORTED`，offload 名义开启实际走 MediaCodec；还会引入 gapless/后处理限制。保持默认关闭 |
| USB DAC bit-perfect | 需要绕过系统混音/独占 USB，项目明确不引入自研 native；收益与维护成本不匹配 |
| Oboe / AAudio | 本地文件播放的瓶颈不在音频回调延迟；引入 native 违反项目约束 |
| FFmpeg / DSD | 需要 native 解码器，APK 体积和 ABI 维护成本高；当前格式覆盖已够 |
| 有损修复 / ML Kit GenAI | 与本地播放器核心无关；引入模型/网络依赖，违背零联网姿态 |
| WorkManager 定时扫描 | `MediaStore.getGeneration()` 水位线已经用一次 binder 调用解决“是否要重扫”；定时扫描是额外唤醒 |
| `PreloadMediaSource` | 项目已用 `setPreloadConfiguration(5s)` 预热下一首；`PreloadMediaSource` 是列表预览/试听场景，不适用 |
| 自定义通知布局 | API 33+ 系统媒体控制忽略自定义 `RemoteViews`；正确扩展点是 `setMediaButtonPreferences()` |
| 现在迁移 Glance | 当前小组件简单且正确；迁移是重写 + 重新验证 Android 17 内存，收益不足 |
| 用 `media3-ui-compose` 替换播放页 | 项目播放页的玻璃/倒影/取色是核心设计，替换会丢掉这些 |
| 用 Room/KSP 存播放快照 | 快照只有几十 KB，DataStore 足够；Room 会引入 KSP 和 schema 迁移 |
| 恢复时返回全量 + 全量元数据 | 5 000 个 `MediaMetadata` 的构建和 Parcel 成本正是 `PlaybackTrackIndex` 要避免的 |
| 为绕 Android 17 加第二个 FGS / BOOT_COMPLETED 播放 | 改变系统媒体控制语义，且不是用户意图；优先用 WIU 豁免路径 |

---

## 九、版本观察窗口

| 版本 | 日期 | 看什么 | 决策 |
|---|---|---|---|
| Media3 1.12.0-alpha01 | 2026-10-21 | `MediaMetadata.playlistId` / `categories`、`Flags` 注册表、`setLoadOnlySelectedTracks` 默认开启 | 不进生产；等 1.12.0-beta/rc |
| Media3 1.11.1 | 2026-09-10 | #3393 legacy MediaBrowser 异步回调死锁修复 | **当前基线，不回退** |
| Glance 1.3.0-alpha02 | 2026-08-26 | 响应式尺寸、错误回退、Android 17 内存处理 | 等 1.3.0 稳定 |
| DataStore 1.3.0 | 2026-10-21 | 异常行为、迁移说明 | P2 升级 |
| Baseline Profile 1.6.0-alpha01 | 2026-10-07 | 新插件/宏基准能力 | 等 1.6.0 稳定 |
| Android 17 | 2026 | OEM 推送节奏、`AudioHardening` 行为 | 每次发版跑 hardening 验证 |

---

## 十、验证矩阵

| # | 场景 | 步骤 | 期望 | 工具 |
|---|---|---|---|---|
| 1 | 通知点击 | 播放中下拉通知 → 点主体 | 回到 App，不重启播放 | 真机 |
| 2 | 锁屏点击 | 锁屏 → 点媒体卡片 | 回到 App | 真机 |
| 3 | 媒体键 | 蓝牙/耳机 play/pause/next | 正常控制 | 真机 + `input keyevent` |
| 4 | 杀进程恢复 | 播放中划掉 App → 按媒体键 | 恢复上次曲目/位置 | 真机 + logcat |
| 5 | 冷启动恢复 | 杀进程 → 打开 App | 恢复上次队列，不随机 | 真机 |
| 6 | 重启恢复通知 | 重启设备 → 看系统 UI | 出现恢复通知，标题/封面正确 | 真机（需 P0-2） |
| 7 | 小组件冷启动 | 清进程 → 点小组件播放 | 正常起播，无 `AudioHardening` | `set-enable-hardening enable` + logcat |
| 8 | 小组件内存 | 添加 1-2 个实例 | `dumpsys appwidget` 远低于 15.5 MB | `adb shell dumpsys appwidget` |
| 9 | 曲库浏览 | 连接 MediaBrowser/Android Auto | root/songs/albums/artists 可浏览 | `dumpsys media_session` / DHU |
| 10 | 不可信控制器 | 第三方 App 连接 | 只能发有限命令 | `dumpsys media_session` |
| 11 | 富元数据 | 播放 → 看通知/锁屏 | 时长、曲目号、年份正确 | 真机 |
| 12 | 收藏按钮 | API 33+ 通知 | 心形按钮显示/切换/刷新 | 真机 |
| 13 | Compose 稳定性 | 编译报告 + 滚动 | 不稳定类减少，帧率不退化 | 编译器报告 / gfxinfo |
| 14 | Baseline Profile | 冷启动/滚动对比 | 有可测收益 | Macrobenchmark（P2） |

---

## 十一、发布门禁增量

现有门禁不变：`testDebugUnitTest`、`lintRelease` 0 error、`apksigner` v2/v3、`zipalign -c -P 16`、SHA-256。

本轮建议新增：

1. **纯函数测试**：`pageSlice()`、`PlaybackSnapshot.restore()` 的 id 映射、`PlaybackSnapshot` 序列化/反序列化、`keepsCachedLibraryOnEmptyScan()` 已有测试保持。
2. **真机检查清单**：验证矩阵的 1-9 项，至少覆盖通知点击、杀进程恢复、冷启动恢复、小组件 hardening、`dumpsys appwidget`。
3. **日志核对**：`PlaybackService` 的 `queue metadata resolved N/N` 仍然是元数据解析的健康指标；新增 `resumption items=N startIndex=... positionMs=...` 和 `snapshot saved tracks=N`，方便排查恢复问题。
4. **Binder 实测**：5 000 首库跑一次 `onPlaybackResumption`，记录回调耗时和是否出现 `TransactionTooLargeException`；不要凭直觉断言安全。

---

## 十二、参考来源

- Android 17 后台音频加固：<https://developer.android.com/about/versions/17/changes/bg-audio>
- Android 17 行为变更（小组件内存上限）：<https://developer.android.com/about/versions/17/behavior-changes-17#memory-limit-widget>
- Media3 版本说明：<https://developer.android.com/jetpack/androidx/releases/media3>
- Media3 后台播放（恢复、可信控制器、媒体按钮偏好）：<https://developer.android.com/media/media3/session/background-playback>
- Media3 曲库服务：<https://developer.android.com/media/media3/session/serve-content>
- Media3 Inspector：<https://developer.android.com/media/media3/inspector/retrieve-metadata>
- Media3 1.11 博客：<https://developer.android.com/media/media3/release-notes#1.11.0>（本地抓取见 `.firecrawl/media3-1-11-blog.md`）
- 项目上一轮全量审查：`review/2026-10-23-code-review.md`

---

## 附：本轮调研的边界

- 本文只覆盖“安卓本地音乐播放器”相关技术，不涉及泛 Android 开发资讯。
- 已评估并否决的方向（offload、USB DAC bit-perfect、Oboe/AAudio、有损修复、ML Kit GenAI、WorkManager 定时扫描、`PreloadMediaSource`、自定义通知布局）不再重复论证，只在第八节列出结论。
- 所有版本号以 2026-10-24 的 Google Maven / Android Developers 为准；1.12.0-alpha01 和 Glance 1.3.0-alpha02 是观察项，不是生产建议。
- 代码骨架是“改哪里、怎么改”的示意，不是可直接粘贴的最终实现；落地时要按项目现有的注释风格、线程约束和测试门禁调整。
