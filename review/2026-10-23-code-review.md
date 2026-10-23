# MilanMusic 全量代码审查报告

- 审查对象：`milan-music/`（`app` 单模块）
- 代码规模：主代码 **38 文件 / 8 000 行**，测试 **9 文件 / 1 541 行**，注释行 **2 392（占主代码 29.9%）**
- 工具链：AGP 9.4.1 / Kotlin 2.4.21 / Gradle 9.8.2 / compileSdk 37 / minSdk 26
- 审查方式：逐文件通读 + 交叉引用核对（调用点、未使用符号、README 与实现一致性、仓库构成）

---

## 一、结论摘要

**总体判断：工程质量显著高于同规模 Android 项目的平均水平，但存在三类系统性负债。**

| 维度 | 评分 | 判断依据 |
|---|---|---|
| 生命周期与并发正确性 | **A** | `MediaController` 的 `connectEpoch` 防悬挂、`pendingActions` 队列防丢请求、`NonCancellable` 交接、`LruCache` 单条大于容量导致自我淘汰的发现与修复 —— 这些都是真机事故倒逼出来的正确解法，不是模板代码 |
| 可测性设计 | **A−** | `paletteOf` / `coverRowAt` / `pageFadesBottom` / `keepsCachedLibraryOnEmptyScan` / `maskAlphas` 全部抽成纯函数，1 541 行门禁测试且做过红态验证。**但测试只覆盖纯函数，UI 层零覆盖** |
| 资源与内存管理 | **A−** | `BitmapBudget` 按堆推导、四条缓存切片逐一论证单条大小、`onTrimMemory` 分级、缓冲区池复用、`DefaultAllocator.trimOnReset` 的坑。唯一失分是 widget 用了 192px 缩略图去填 256px 目标 |
| 架构合理性 | **C+** | 数据层干净，**UI 层反向依赖 DI 容器**；播放页无 ViewModel，同一领域动作有两条写路径 |
| 重复逻辑控制 | **C** | 5 组明确重复（收藏按钮、内容切换转场、HARDWARE 位图保护、`sampleSizeFor`、trim 分级），合计约 250 行可消除 |
| 注释与可读性 | **C** | 不变量注释是资产；**批次史与真机取证占了过大比重**，`PlayerScreen.heroFadeMask` 甚至有两个 KDoc 块叠加（一处孤儿注释） |
| 命名规范 | **B** | 类型与函数命名准确；扣分在 `OnDarkFaint` / `Sheen` / `HairlineGold` / `MilanMotion.Emphasized` / `MilanMotion.Settle` 五处死代码，以及 UI 文案散落在 domain 层（`SortMode.label`） |
| 文档准确性 | **D** | README 的技术栈版本、代码规模、功能清单（歌单、专辑/艺人/文件夹四视图）**均与实现不符** |

**一句话结论：** 这个项目的"难的部分"（Media3 生命周期、位图预算、倒影几何）做得比大多数商业项目更严谨；"容易的部分"（分层、去重、文档同步）反而没做——而后者正是让它从"一个人的精密作品"变成"可持续维护的项目"的门槛。

---

## 二、做对了、不要动的部分

先划红线，避免后续重构误伤：

1. **纯函数 + 门禁测试的测试策略。** `CoverLayout` / `CoverReflection` / `CoverPalette` 的几何与取色全部脱离设备可测，且测试写过红态验证。这是全项目最有价值的资产。
2. **播放页用叠加层而非 Navigation-Compose。** `MilanApp` 的注释给的理由（pop 期间前一个目的地不参与合成，做不出"揭页"）是对的，不要为了"架构正统"改成 NavHost。
3. **`PlaybackTrackIndex` 的"只传 mediaId + uri"设计。** 避免 5 000 条 `MediaMetadata` 在主线程构建并过 Parcel，这是有量化依据的优化。
4. **`IndexStamp` 的 generation 水位线 + "先取 stamp 再读行"的次序。** 这条次序是正确性问题（中途新增的曲目会导致永久跳过），注释写清了反例，不要"优化"成扫完再取。
5. **清单层的零联网姿态用 `tools:node="remove"` 结构性保证。** 比"我们不加网络代码"这种口头承诺强一个数量级。
6. **`ByteCappedLoadControl` 用委托而非继承**，且明确只覆写 `LoadControl.Parameters` 那一个重载。这个判断如果做错，字节上限会静默失效且编译通过。

---

## 三、P0 —— 阻断级，建议在本轮内处理

### P0-1　UI 直接依赖 `AppContainer`，播放页没有 ViewModel

**证据**

| 位置 | 问题 |
|---|---|
| `ui/MilanApp.kt:28` | `MilanApp(container: AppContainer)` |
| `ui/library/HomeScreen.kt:124` | `HomeScreen(container: AppContainer, …)` |
| `ui/player/PlayerScreen.kt:166` | `PlayerScreen(container: AppContainer, …)` |
| `PlayerScreen.kt:170-173` | 直接 `collectAsStateWithLifecycle` 三个数据源 + `rememberCoroutineScope` |
| `PlayerScreen.kt:485-487` | 收藏走 `scope.launch { container.userData.toggleFavorite(item.id) }` |
| `ui/library/HomeScreen.kt:284` | 同一个动作却走 `viewModel::toggleFavorite` |

**为什么是 P0**

1. **同一领域动作存在两条写路径。** 收藏在列表页经 `LibraryViewModel`、在播放页直连 `UserDataStore`。今天两者行为一致只是因为 `toggleFavorite` 恰好无副作用；一旦加入"收藏数统计""最近收藏"或撤销提示，播放页这条路径必然被漏掉。
2. **状态重复订阅。** `LibraryViewModel` 已把 `userData` 折进 `LibraryUiState`（含 `favorites` 列表），`PlayerScreen` 又独立订阅一次 `container.userData.data` —— 两处 `stateIn(Eagerly)` 语义不同，未来必然分叉。
3. **组件不可预览、不可测试。** 签名里带一个需要 `Context`、`ContentResolver`、`DataStore` 的容器对象，`@Preview` 无法构造，Compose UI 测试也无法只注入状态。这是 **UI 层零测试覆盖的直接原因**（见 P1-6）。
4. **`container.player` 被当全局单例从 composable 深处调用**（`HomeScreen.kt:227/236/335-336/349`）。播放控制是领域动作，不是"从容器里取个对象点一下"。

**优化方向**

新增 `PlayerViewModel`，与 `LibraryViewModel` 对称：

```
PlayerViewModel(
  player: PlayerController,   // 构造注入
  userData: UserDataStore,
) {
  val state: StateFlow<PlayerUiState>
  val progress: StateFlow<PlaybackProgress>
  val isFavorite: StateFlow<Boolean>
  fun toggleFavorite(); fun onSeek(ms); fun onTogglePlay(); …
}
```

`PlayerScreen` 的签名收敛为 `(state, progress, isFavorite, callbacks)`，`AppContainer` 只出现在 `MilanApp` 一处（`viewModel(factory = …)` 的构造点）。

**收益**：删除 1 条写路径、删除 1 处重复订阅、让播放页出 3 个 `@Preview` 与后续 UI 测试成为可能。**成本约 150 行改动，无行为风险。**

---

### P0-2　`edgeHaze` / `heroCover` / `reflection` 跨曲目残留 → 播放页画出上一首的封面

**证据**：`PlayerScreen.kt:181-213`

```kotlin
var edgeHaze by remember { mutableStateOf<ImageBitmap?>(null) }
var heroCover by remember { mutableStateOf<ImageBitmap?>(null) }

LaunchedEffect(track?.id) {
    val target = track ?: return@LaunchedEffect
    reflection = container.artwork.loadReflection(target)
    heroCover  = container.artwork.loadPlayerArtwork(target) ?: container.artwork.loadPlayerArtwork(target)
    val image  = container.artwork.loadBackdrop(target) ?: return@LaunchedEffect   // ← 提前返回
    edgeHaze = image
    palette  = withContext(Dispatchers.Default) { paletteOfImage(image) }
}
```

这三个 `var` 都是 `remember` 记住的**跨曲目状态**，只在加载成功时写入，**换曲时从不清空**。`palette` 保留是有意的（注释已说明：避免闪一下兜底深褐）。

但另外两个没有同样理由：

| 变量 | 残留后果 |
|---|---|
| `edgeHaze` | 新曲目无封面时，封面**上缘 0.07~0.24 那一段**仍然画着上一首的模糊图 |
| `heroCover` | 新曲目无封面时 `syncCover == null` → `bandCover = heroCover`（`PlayerScreen.kt:227`）→ 镜面线以下铺着**上一首封面的镜像** |

第二条是可见缺陷：一首没有内嵌封面的歌，其下方的倒影带会显示上一首歌的封面。触发条件窄（需要一个无封面曲目），但一旦触发就是"内容错误"，不是"效果不好"。

**优化方向**

把"曲目加载结果"建模成一个带 id 的整体，而不是三个独立可空变量：

```kotlin
data class HeroArtwork(
  val trackId: Long?,
  val cover: ImageBitmap?,
  val mirror: ImageBitmap?,
  val haze: ImageBitmap?,
  val palette: PlayerPalette,
)
// LaunchedEffect 里整体赋值；新曲目无封面时给出 trackId 匹配但 cover = null 的对象
```

让 `bandCover` 的取值天然带 `trackId` 校验（`heroArtwork.trackId == shownTrack?.id`），残留就在类型层面不可能。

---

### P0-3　`catch (e: Exception)` 吞掉协程取消

**证据**：`data/media/MediaStoreRepository.kt:79-91`

```kotlin
} catch (e: SecurityException) {
    Log.w(TAG, "audio query rejected: permission revoked", e)
} catch (e: Exception) {          // ← CancellationException 也是 Exception
    Log.w(TAG, "audio query failed; returning what was read", e)
}
```

`kotlinx.coroutines.CancellationException` 继承 `java.util.concurrent.CancellationException` → `RuntimeException` → `Exception`。这个 `catch` 会把**协作式取消变成"扫描成功但返回空表"**。

实际后果：`LibraryViewModel.viewModelScope` 被取消后（ViewModel 清理、Activity 真正结束），正在跑的 `loadTracks()` 不会中止，会继续把整库读完，然后回到 `ensureLoaded()` 的 `libraryCache.save(fresh, stamp)` —— 在已经失效的 scope 里写一次 1MB 的 DataStore。正常情况下 `save` 会在挂起点抛出，但**这个 catch 把最内层的取消信号吃掉了**，中止点被推迟到下一次挂起。

同类模式还有：`MusicWidgetProvider.render` 的 `runCatching`（`widget/MusicWidgetProvider.kt:55`）套在协程里。

**优化方向**

```kotlin
} catch (e: CancellationException) {
    throw e
} catch (e: SecurityException) {
    …
} catch (e: Exception) {
    …
}
```

并在协程作用域内禁用裸 `runCatching`：抽一个 `suspend fun <T> runCatchingCancellable(block: suspend () -> T)`，内部先 rethrow `CancellationException`，其余交 `Result`。**这条应作为 lint/评审规则固化**，因为它是"看起来防御性强、实际吃掉取消"的典型。

---

### P0-4　README 与实现系统性不符

**证据**

| README 声明 | 实际 |
|---|---|
| 「歌曲 / 专辑 / 艺人 / 文件夹 **四个视图**」 | 只有两个 tab：`mainTab` 0/1（米兰 / 喜欢），`HomeScreen.kt:203` |
| 「**歌单**：自建歌单」 | 全项目无 playlist 实现，`UserData` 只有 `favorites` |
| 「26 个 Kotlin 文件，约 3.7k 行」 | **47 文件 / 9 541 行** |
| 「AGP 9.1.1、Kotlin 2.4.20、Gradle 9.3.1」 | **9.4.1 / 2.4.21 / 9.8.2**（`libs.versions.toml`） |
| 「Android 13+ 自适应图标 monochrome 层」 | 未见 `mipmap-anydpi-v33` 之外的 monochrome 声明，需核对 |

**为什么算 P0**：README 是这个仓库唯一的对外门面，且已被引用在 Release 页。宣称不存在的功能属于**交付级错误**，不是文档瑕疵——它会让外部使用者（和未来的你自己）无法判断"这功能是没做，还是坏了"。

**优化方向**：把 README 的功能表与代码建立机械对应关系——功能表每一行标注实现文件（如 `收藏 → data/userdata/UserDataStore.kt`），未实现的移入"规划"章节。版本号改为从 `libs.versions.toml` 读取，或至少在 CI 里加一条断言脚本。

---

## 四、P1 —— 高优先级

### P1-1　`ArtworkRepository` 四个 `load*` 函数是同一段代码的四份拷贝

**证据**：`data/media/ArtworkRepository.kt:123-262`

`load` / `loadPlayerArtwork` / `loadBackdrop` / `loadReflection` 各自 25~40 行，结构完全一致：

```
读缓存 → 建 CompletableDeferred → putIfAbsent → 命中则 await(cache 兜底)
→ 解码/翻转 → 写缓存 → finally { remove(key); own.complete(result) }
```

四处都带同一句注释「producer 被取消会答 null，所以要重读缓存」。**这条不变量被复制了四次，改一次必然漏三处。**

**优化方向**

```kotlin
private class MemoCache<K, V>(maxBytes: Int, …) {
  suspend fun get(key: K, loader: suspend (K) -> V?): V?
}
```

把"缓存 + 在飞去重 + 取消兜底重读"收进一个类，四个 `load*` 退化成「一行取缓存 + 一行解码 lambda」，净减约 **110 行**，且不变量只有一个真值来源。

---

### P1-2　收藏按钮完整实现两份

**证据**：`ui/components/TrackRow.kt:119-163` 与 `ui/player/PlayerScreen.kt:702-742`

两处都是 `Box(48.dp) + noRippleClickable + AnimatedContent(spring 0.45f) + Favorite/FavoriteBorder`，差异只有 tint（`onBackground@0.5` vs `OnDark`）与图标尺寸（18 vs 24）。

第二份是原样抄过去的，这从 `TrackRow.kt:156` 的写法能看出来 —— 那里为了不 import 而写了全限定名 `com.milan.music.ui.theme.LikedRed`。

**优化方向**：抽 `ui/components/FavoriteButton(checked, tint, iconSize, onClick)`，两处只传 tint。

**收益**：消除"改了一处、另一处忘了"的隐患（这个按钮的动效参数是调过的，`dampingRatio = 0.45f` 出现在两个文件里，一旦有人只改一个，两个页面的手感就分叉了）。

---

### P1-3　内容切换转场重复 3 次

**证据**

| 位置 | 用途 |
|---|---|
| `PlayerScreen.kt:670-676` | 播放页标题/艺人 |
| `ui/components/MiniPlayer.kt:89-95` | 迷你播放器标题/艺人 |
| `ui/library/HomeScreen.kt:265-271` | tab 切换列表 |

三处都是 `slideInVertically { it / 3 } + fadeIn(MediumMs)` ↔ `slideOutVertically { -it / 3 } + fadeOut(FastMs)`，连 `it / 3` 的除数和两条时长都相同。

**优化方向**：`MilanMotion.contentSwap(direction)` 返回 `ContentTransform`，放进 `ui/theme/Motion.kt` —— 该文件本来就是"动效节奏唯一来源"，这三处漏在外面属于规则没闭环。

顺带：`MilanMotion.Emphasized` 与 `MilanMotion.Settle` **零调用**（已核对全量 grep），要么用起来，要么删掉。

---

### P1-4　HARDWARE 位图保护散落 4 处

**证据**

| 位置 | 代码 |
|---|---|
| `ArtworkRepository.kt:533-543` | `Bitmap.softwareCopy()` |
| `ArtworkRepository.kt:499-502` | `reflectionOf` 内再判一次 |
| `PlayerScreen.kt:625-629` | `paletteOfImage` 内 `copy(ARGB_8888, false)` |
| `MusicWidgetProvider.kt:214-218` | `roundedCover` 内再一份 |

`MediaProvider.loadThumbnail` 可能返回 `Config.HARDWARE`，而 `getPixels` 在它上面抛 `IllegalStateException` —— 这条平台事实被独立发现了四次。

**优化方向**：`data/media/BitmapGuard.kt` 导出 `fun Bitmap.softwareOrNull(): Bitmap?`（统一日志、统一"拷贝失败则降级"策略），四处改为单行调用。这同时消除了"某处忘了判"的可能性 —— 目前 `reflectionOf` 与 `trimLightMargins` 对拷贝失败的处理就不一致（一个跳过、一个返回原图）。

---

### P1-5　`sampleSizeFor` 两份实现 + 注释承认了

**证据**：`ArtworkRepository.kt:405-417` 与 `play/ArtworkBitmapLoader.kt:157-161`

注释里写着「Mirrors `ArtworkBitmapLoader.sampleSizeFor`, kept local rather than shared: … six lines do not justify a common module.」——理由成立（6 行确实不值得一个 module），但**不值得一个 module ≠ 不值得一个文件**。同包内的 `internal fun` 就够，且能顺带统一"target ≤ 0 时钳到 1"这处防御（只有一份实现了钳位，另一份没有）。

**优化方向**：`data/media/BitmapSampling.kt`，两处共用。

---

### P1-6　UI 层零测试覆盖，回归完全依赖人工截图

**证据**

- `app/src/test` 9 个文件，全部是纯 JVM 函数测试；**无 `app/src/androidTest`**
- 没有任何 Compose UI test 依赖（`libs.versions.toml` 里无 `ui-test-junit4` / `ui-test-manifest`）
- 视觉回归靠 `review/*.py` 脚本 + 人工贴图，最多覆盖到"某个 y 段的亮度/RMS"

**风险**：项目已经出现过「整屏只剩封面，歌名/进度条/播放键全部出界」（第 55 批）这类**纯布局**缺陷，也出现过「封面出现两次」（第 57 批）这类**纯内容映射**缺陷。这两类都是 Compose UI test 或快照测试能直接抓住的，而截图体检脚本只能事后发现。

**优化方向**（按性价比排序）

1. 加 `androidTest` + 3 条最小用例：播放页三个区块都在屏幕内（`assertIsDisplayed` 分别对标题/进度条/播放键）；下滑 110dp 后回到首页；收藏在列表与播放页双向同步。
2. 引入 Roborazzi（Robolectric 上跑 Compose 截图），把 `review/` 的手工对比固化成 GPU-free 快照断言。
3. 保留现有 Python 脚本专做"真机取证件"，不与门禁混用。

---

### P1-7　启动即随机播放，与 `MilanMusicApp` 明示的"按需绑定"设计矛盾

**证据**

- `MilanMusicApp.kt:28-34`：明确论证"不在 Application 里先连会话，因为每次冷启动都会创建 service + ExoPlayer + MediaSession，且 `MediaController` 是绑定客户端 → service 永远无法销毁"。结论是「只有 playQueue / togglePlayPause 才连」。
- `HomeScreen.kt:232-242`：只要拿到权限且库非空，**立刻** `playQueue(tracks, Random.nextInt(size))`。

**后果**

1. `PlayerController.onUiStopped()` 的前提（`queueSize == 0` 才 release）**永不成立** → 用户"只是打开看看"也会一直绑定 service，`MilanMusicApp` 那段论证在真实路径上被绕过了。
2. 打开 App 就开始外放，且是**随机一首**——在安静环境里是负面体验。

**优化方向**：改成"恢复到上次队列但不自动播"（`playQueue(..., autoPlay = false)`），或仅在用户上次是"播放中退出"时续播（需要 `SettingsStore` 记一个 `lastPlaybackWasActive`）。若产品上确实要保留"打开即播"，则必须同步修正 `MilanMusicApp` 的注释与 `onUiStopped` 的判据，避免文档与事实相反。

---

## 五、P2 —— 中优先级

| # | 问题 | 证据 | 优化方向 |
|---|---|---|---|
| P2-1 | **`heroFadeMask` 有两个 KDoc 块** | `PlayerScreen.kt:500-511` 与 `512-522` | 前一块是孤儿注释（后一块覆盖它），删除前者。这是注释膨胀导致的最直接产物 |
| P2-2 | **首次授权时全量扫描执行两遍** | `HomeScreen.kt:152`（`viewModel.refresh()`）+ `:177-190`（`ensureLoaded()`）在 `hasAudioPermission` 翻转后同时触发 | 二者加互斥（`ensureLoaded` 内部 `Mutex`，或 `refresh()` 复用同一 Job）。**首次启动是最慢的一次，却做两遍活** |
| P2-3 | **widget 用 192px 缩略图填 256px 目标** | `MusicWidgetProvider.kt:179` 调 `artwork.load(track)` → `LIST_THUMB_SIZE = 192`，`COVER_SIZE = 256` | 放大 1.33×。改用 `loadPlayerArtwork`（512px）再缩，或给 widget 单独一档 256 |
| P2-4 | **`runCatching` 被用于表达控制流** | `ArtworkRepository.kt:351/485/497`、`MediaStoreRepository.kt:115/145`、`PlayerController.kt:159/164/177/422` | 局部可接受（确实要"失败就降级"），但建议在 `PlayerController` 里改为 `try/catch` + 显式分支，因为那里的 `runCatching` 包住了 30 行逻辑，失败原因被压成一个 `Throwable` |
| P2-5 | **`SideEffect` 每次合成写 state** | `PlayerScreen.kt:344` `SideEffect { sheetHeightPx.floatValue = … }` | 改 `Modifier.onSizeChanged { }` 或 `LaunchedEffect(constraints.maxHeight)`。当前写法虽然因为值相等不会触发额外重组，但语义上是"每次合成都有一个副作用" |
| P2-6 | **`versionCode` 与 `versionName` 错位** | `app/build.gradle.kts`：`61` vs `"1.0.60"` | 用 `-PversionCode` 或从一处派生；已在批次流程里靠人工 +1，机械约束更好 |
| P2-7 | **debug 包只有一个后缀差异，装错包看不出来** | `applicationIdSuffix = ".debug"`，无任何构建类型标识 | 开 `buildFeatures { buildConfig = true }`，在首页/设置里显示 `versionName (versionCode)` + `RELEASE/DEBUG`。这把"改了没生效"从两轮排查变成一眼可见 |
| P2-8 | **UI 文案硬编码，无法本地化** | `strings.xml` 仅 3 条；`text=`/`contentDescription=` 中文约 11 处，`SortMode.label` 5 条在 **domain 层**，`Track.displayTitle` 3 条在 **model 层** | `SortMode` 只保留枚举，label 移到 `ui/SortModeLabel.kt`；文案全部进 `values/strings.xml`。**domain 层出现 UI 文案是分层渗漏**，即便单语言项目也应在 P2 处理 |
| P2-9 | **5 处死代码/未使用声明** | `OnDarkFaint`（`PlayerScreen.kt:108`）、`Sheen` / `HairlineGold`（`Color.kt:43-44`）、`MilanMotion.Emphasized` / `Settle`（`Motion.kt:24,33`）、`kotlin-android` 插件别名（`libs.versions.toml`，AGP 9 内置 Kotlin 后未使用） | 全部删除。`Color.kt` 里的 `Sheen` / `HairlineGold` 是玻璃改版前的残留，注释里「tint 不再声明」的结论已经把它们的用途作废了 |
| P2-10 | **`proguard-rules.pro` 的 keep 过宽** | `-keep class com.milan.music.data.model.** { *; }`，实际被序列化的是 `data.media.CachedTrack`（`@Serializable`），`Track` 不参与序列化 | 删除该规则，交回 `kotlinx.serialization` 自带规则；release 体积略降且规则更诚实 |
| P2-11 | **`configuration-cache=false`** | `gradle.properties` | AGP 9.4 + Gradle 9.8 已支持。开启后可省增量构建时间；需先确认无 `Project` 在配置期被业务代码捕获 |

---

## 六、P3 —— 低优先级 / 观察项

1. `LibraryViewModel.ensureLoaded()`：`cacheLoaded = true` 在 `launch` 之前设置，若首次读库抛异常则永不重试（`LibraryViewModel.kt:151-153`）。建议失败时回滚标志。
2. `ArtworkRepository` 中 `pending.remove(key)` 与 `own.complete(result)` 之间有极窄窗口（`ArtworkRepository.kt:144-147`）：此时到达的调用者会启动第二次解码。P1-1 的 `MemoCache` 重构顺带解决。
3. `PlayerScreen.kt:207-209` 的 `?: container.artwork.loadPlayerArtwork(target)` 重试：注释写"命中即返回"，但缓存已空时这里是**重新解码**，会把失败路径的等待时间翻倍。建议注释与实际一致，或改为"重读 `cachedPlayer` 一次"。
4. `MainActivity.onStop` 直接调 `container.player.onUiStopped()`，把生命周期策略写在 Activity 里。ViewModel 化（P0-1）后应下沉。
5. `CoverReflection` 在 `for` 循环里调 `remember`（`CoverReflection.kt:365-368`）—— 当前 `BlurRamp` 长度恒定所以合法，但这是一条隐含契约，值得一行注释固化（现有注释解释了顺序，未解释这个约束）。
6. `review/` 目录有 **44 个文件被 git 跟踪**（含多张 PNG），主仓库对象约 12 MiB。建议只保留脚本与最终结论图，中间取证图走 Release 附件或 `.gitignore`。
7. `MaterialTheme.typography` 只覆盖 8 档，`headlineSmall`（播放页标题在用）走的是 Material 默认值，未纳入 `Type.kt` 的统一节奏。

---

## 七、目标架构

### 7.1 分层（当前 → 目标）

```
当前                                     目标
─────────────────────────────────────    ─────────────────────────────────────
UI ────────────┐                         UI (stateless)
               ├─→ AppContainer          ├─→ UiState + callbacks
data ←─────────┘   (双向耦合)             ├─→ PlayerViewModel / LibraryViewModel
                                          │
                                          ↓
                                        play/  (connection / queue / progress / widget)
                                          │
                                          ↓
                                        data/  (media / userdata / prefs)
                                          ↑
                                        di/AppContainer  ← 只在 Composition 根部出现一次
```

**唯一的结构性改动**：把依赖方向从"UI ↔ Container 互相持有"改成"UI ← State ← ViewModel ← Repository"。

### 7.2 `PlayerController`（491 行）拆分建议

| 目标文件 | 职责 | 预计行数 | 依赖 |
|---|---|---|---|
| `play/MediaSessionConnection.kt` | 建连、`connectEpoch`、`pendingActions` 重放、`release`/`onUiStopped` | ~140 | 只依赖 `Context` + 一个 `Listener` 接口 |
| `play/PlaybackQueue.kt` | `Track → MediaItem`、`trackIndex.replace`、shuffle/repeat 策略 | ~60 | `Track`, `PlaybackTrackIndex` |
| `play/ProgressTicker.kt` | 500ms 循环 + `progressObserved` 双闸 | ~70 | `MediaController`, `StateFlow` |
| `play/WidgetNotifier.kt` | `lastWidgetSnapshot` 去重 + broadcast | ~40 | `MusicWidgetProvider` |
| `play/PlayerController.kt` | 门面，组合上述四者，暴露 `state`/`progress`/命令 | ~120 | 上述 |

**关键收益**：`MediaSessionConnection` 可以在**不启动 Media3** 的前提下用 fake listener 测试"连接中途 release"这类最难复现的竞态（当前只能靠真机）。

### 7.3 去重清单（预计净减 250~300 行）

| 重复项 | 处数 | 归并到 |
|---|---|---|
| 缓存+在飞去重 | 4 | `data/media/MemoCache.kt` |
| HARDWARE 位图保护 | 4 | `data/media/BitmapGuard.kt` |
| 收藏按钮 | 2 | `ui/components/FavoriteButton.kt` |
| 内容切换转场 | 3 | `MilanMotion.contentSwap()` |
| `sampleSizeFor` | 2 | `data/media/BitmapSampling.kt` |
| trim 分级 | 2（阈值还不一致：÷6/÷8/÷2 vs ÷8/÷4） | `BitmapBudget.trimStep(level)` |

最后一项值得单独说：同一个 `onTrimMemory` 信号，UI 侧缓存按 ÷2/÷8/÷6 收缩，通知缓存按 ÷8/÷4 收缩，两套阈值没有任何文档解释为什么不同。**要么统一，要么在 `BitmapBudget` 里写明两者为什么该不同**——现在的状态是"看起来像疏忽"。

### 7.4 注释治理（这是最容易被低估的一项）

**现状**：注释率 29.9%，其中相当一部分是过程史。例如 `CoverReflection.kt` 557 行里，`FadeEnd` 一个常量的 KDoc 就写了 8 行，主要讲"第 45~53 批试错了什么"。

**这类注释的价值是真实的**——它记录了"为什么不能这么做"，而这正是最贵的知识。问题在于**存放位置错了**：它应该属于 ADR，不属于符号定义。

**建议的两层制度**

| 层 | 内容 | 位置 |
|---|---|---|
| 代码注释 | 只写 **①不变量**（"Σw ≡ 1"）②**为什么不是更简单的写法**（"不能用 Image + alignment，因为依赖缩放后高度==框高"）③**改动必须同时改哪里** | 符号上方，≤8 行 |
| ADR | 批次史、真机取证、被否方案、数值来源 | `docs/decisions/0007-reflection-ramp.md`，代码里一行 `// 设计依据: docs/decisions/0007` |

**验收目标**：注释率 29.9% → **20% 左右**，且 `grep "第 [0-9]+ 批"` 在 `app/src/main` 中归零（当前 54 处）。门禁脚本可以机械检查后者。

这不只是整洁问题：**注释与代码不一致的成本高于没有注释**。P2-1 那个双 KDoc、P1-5 那句"六行不值得共享"、`MilanMusicApp` 与 `HomeScreen` 的自相矛盾（P1-7），都是同一机制产生的——注释只增不改，最终描述的是一个不存在的版本。

---

## 八、落地计划

### 阶段 A（P0，必须先做）

| 任务 | 产出 | 验收 |
|---|---|---|
| A1 | `PlayerViewModel` + `PlayerScreen` 签名收敛 | `PlayerScreen` 3 个 `@Preview`；`grep "container.userData" ui/player` 为空 |
| A2 | `HeroArtwork` 整体状态 | 无封面曲目 → 倒影带为空（新增 1 条单元测试，先红后绿） |
| A3 | `CancellationException` 透传 | `MediaStoreRepository` 单测：取消后 `loadTracks` 抛 `CancellationException` |
| A4 | README 重写 + 功能表加实现文件列 | README 中每个技术栈版本与 `libs.versions.toml` 逐项一致 |

### 阶段 B（P1，与 A 同批或紧接）

B1 `PlayerController` 四拆 → B2 五组去重 → B3 `androidTest` 3 条 + Roborazzi 接入 → B4 启动播放策略定稿（改行为 or 改文档）

### 阶段 C（P2，择机）

注释分层 → 死代码清理 → 构建配置（`buildConfig`、`configuration-cache`、`versionCode` 派生、proguard 收窄）→ 文案资源化 + `SortMode.label` 下沉

### 阶段 D（不排期，观察）

`material-icons-extended`（只用到 ~9 枚图标）替换为 vector drawable 可去掉整个依赖，收益主要在 **debug 构建时间**（release 已被 R8 处理）；`review/` 目录瘦身。

**每阶段的公共门禁**（沿用现有纪律）：`lintRelease` 0 error、单测全绿、`assembleRelease` + `apksigner` v2/v3 + `zipalign -c` + SHA-256、`versionCode` +1。

---

## 九、明确**不建议**做的事

写在这里，是为了防止后续"架构升级"把已有的正确决策改坏：

| 不建议 | 理由 |
|---|---|
| 引入 Hilt / Koin | 8 000 行、1 个模块、5 个依赖对象。手写容器的注释已经给出正确判断。P0-1 的问题是**依赖方向**，不是**依赖注入框架**——加框架不会解决，反而会把 `container` 藏得更深 |
| 引入 Room | 缓存是"一次扫描写、一次启动读"的整块快照，`LibraryCacheStore` 的 KDoc 已明确写出退化到五位数曲目时才该换。当前写入频率不支持这个升级 |
| 改用 Navigation-Compose | `MilanApp` 的理由（pop 期间前驱不合成）是正确的，叠加层是这个视觉效果的**唯一**实现路径 |
| 拆多模块 / 加 `:core` `:feature` | 8 000 行的模块化只会增加构建配置面积。等出现第二个可发布产物再说 |
| 替换 Haze / 自研玻璃 | 第 19~27 批的结论有量化依据（白点 0.17 + 对比度 0.08 = +15 级），这是踩完坑得来的，不是选型偏好 |
| 为"性能"提前上 `baseline profile` | 当前无启动耗时投诉，第一优先级是正确性（A2/A3）与可测性（B3） |

---

## 十、度量基线

建议把下表钉进 CI，作为重构的回归依据（数值取自本次审查）：

| 指标 | 当前 | 阶段 C 目标 |
|---|---|---|
| 主代码行数 | 8 000 | ≤7 700（去重收益） |
| 注释行占比 | 29.9% | 18–22% |
| `第 N 批` 引用数 | 54 | 0（迁入 ADR） |
| 未使用声明 | 6 | 0 |
| UI 测试用例 | 0 | ≥3 |
| `runCatching` 裸用（协程内） | 2 | 0 |
| 硬编码 UI 文案 | ~19 | 0 |
| README 版本/规模与代码一致 | 否 | 是 |

---

*审查完成时间：2026-10-23。本报告只描述现状与方向，未修改任何生产代码。*
