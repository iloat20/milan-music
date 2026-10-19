# 米兰音乐 App 审查报告 — 性能 / 缺陷 / Google 安卓规范符合度

- 审查对象：`milan-music`（`com.milan.music`）1.0.43 / versionCode 44
- 编译目标：`compileSdk = 37`、`targetSdk = 37`、`minSdk = 26`、AGP 9.1.1 / Kotlin 2.4.20 / Compose BOM 2026.09.00
- 审查日期：2026-10-18
- 代码规模：27 个 Kotlin 文件 / 3,440 行；无单元测试、无仪器测试

---

## 0. 结论摘要

| 维度 | 评级 | 结论 |
|---|---|---|
| 编译期静态质量 | ✅ 良好 | `:app:lintDebug` **0 error / 33 warning**，无阻塞级问题；R8 裁剪有效 |
| 崩溃 / ANR 风险 | ✅ 低 | 主线程无阻塞 IO，异常路径均有 `runCatching`；无已知崩溃链路 |
| 功能缺陷 | ❌ 2 个 P0 | ① 返回键直接退出应用（播放器无法用返回关闭）② 通知栏/锁屏封面在 Android 10+ 大概率空白 |
| 性能 | ⚠️ 3 个 P1 | ① 全库排序/过滤在主线程 ② 进度轮询 500ms 常驻 ③ Haze 把整个 LazyColumn 当模糊采样源 |
| 最新 Google 规范 | ⚠️ 大体符合 | 16KB 页对齐 ✅、FGS 类型 ✅、targetSdk 37 ✅；缺 predictive-back 声明、单色图标、AAB、签名 v3 |

**总体判断**：这是一个完成度较高的本地音乐播放器，架构（AppContainer 手动 DI + StateFlow + Media3 MediaSessionService）与 Compose 使用方式基本正确，R8/资源裁剪与权限最小化做得比多数个人项目好。问题集中在**交互闭环缺失**（播放器没有返回语义）与**两处明确的 Android 版本迁移遗漏**（`Modifier.blur` / `clipToOutline` / 旧 albumart URI 都是 API 29~31 的断点）。

---

## 1. 验证方式与既有证据

实际执行而非推断：

| 项目 | 方法 | 结果 |
|---|---|---|
| Lint | Gradle 9.3.1 执行 `:app:lintDebug` | `BUILD SUCCESSFUL in 57s`，0 error / 33 warning，报告：`app/build/reports/lint-results-debug.html` |
| APK 元信息 | `aapt2 dump badging` | targetSdk 37 / compileSdk 37 / minSdk 26；权限 6 项，无 INTERNET |
| 签名 | `apksigner verify -v` | 仅 **v2** 通过；v1/v3/v3.1/v4 全部 false；RSA 2048 |
| 16KB 页对齐（Play 2025-11 起硬性要求） | 解析 ELF `PT_LOAD.p_align` | 4 个 `arm64-v8a` / `x86_64` .so 全部 `0x4000` ✅ |
| APK 体积 | 解包统计 | 未压缩 4.15 MB，下载 2.4 MB；单 `classes.dex` 3.7 MB（无需 multidex） |
| R8 效果 | `mapping.txt` / `usage.txt` | 导航库 366 个类、material-icons 11,403 项被裁剪 ✅ |
| 运行时行为 | 无设备连接（`adb devices` 为空） | 相关结论标注「需真机确认」 |

---

## 2. P0 — 必须修

### P0-1 播放器无返回语义：系统返回键直接退出应用

**证据**
- `app/src/main/java/com/milan/music/ui/MilanApp.kt:40-55`：播放器是 `AnimatedVisibility` 覆盖层，`onBack` 只由下滑手势触发
- `app/src/main/java/com/milan/music/ui/player/PlayerScreen.kt:91`：整个 `PlayerScreen` **没有任何 `BackHandler`**（全工程 grep 结果为空）
- 合并后清单（APK 实测）**未声明 `android:enableOnBackInvokedCallback`**

**后果**
1. 播放器打开时按返回 → `Activity.finish()` → 直接退出应用（音乐继续由前台服务播放，用户看到桌面）。
2. 界面上**没有关闭按钮**，唯一出口是下滑手势；手势失效（如列表滚动抢占、无障碍操作、车机/折叠屏）时用户被卡在播放页。
3. `targetSdk 37` 下预测性返回默认启用，系统会先在返回手势中预览「退出应用」动画，观感问题更明显。

**修复**
```kotlin
// MilanApp.kt 内，playerOpen 为 true 时拦截
BackHandler(enabled = playerOpen) { playerOpen = false }
```
另建议在播放页左上角加显式收起按钮（当前 `Spacer`/标题行完全空白，见 `PlayerScreen.kt:348`），并把 `android:enableOnBackInvokedCallback="true"` 写入 `<application>` 以启用预测性返回动画。

---

### P0-2 通知栏 / 锁屏 / 蓝牙元数据封面在 Android 10+ 大概率空白

**证据**
- `play/PlayerController.kt:332`：`setArtworkUri(albumArtUri(albumId))`
- `play/PlayerController.kt:337-341`：`albumArtUri()` = `content://media/external/audio/albumart/<albumId>`
- 对照 `data/media/ArtworkRepository.kt:113-127`：**UI 侧已经正确改用 `ContentResolver.loadThumbnail()`（API 29+）**，两套取图路径不一致

**根因**：`content://media/external/audio/albumart` 是 Android 9 及以前的专辑封面 provider，自 **API 29 起弃用、Android 10+ 上普遍不可访问**。Media3 的默认 `DefaultBitmapLoader` 对该 URI 做 `openInputStream`，失败即静默降级为无封面 —— 通知栏只剩文字，锁屏/手表/车机同样没有封面。

**修复（二选一）**
```kotlin
// 方案 A：直接塞字节，Media3 不再走 URI 解析
MediaMetadata.Builder()
    .setArtworkData(jpegBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)

// 方案 B：给 session 装自定义 BitmapLoader，复用 ArtworkRepository 的 LruCache
MediaSession.Builder(this, player).setBitmapLoader(myBitmapLoader).build()
```
方案 B 与现有 24 MB 封面缓存复用，最省内存。**该项建议在 Android 10/11/13 真机各验一次通知栏封面。**

---

## 3. P1 — 性能与体验

### P1-1 全库过滤 + 排序跑在主线程（本项目最大的卡顿源）

- `ui/library/LibraryViewModel.kt:85-115`：`combine(allTracks, debouncedQuery, sortModeFlow)` 内做全量 `filter` + 3 次排序
- `stateIn(viewModelScope, …)`，而 `viewModelScope` 默认 `Dispatchers.Main.immediate`
- 全工程 `grep flowOn` **零命中**（确认无降线程）

**代价估算**：5,000 首曲目时，一次 ARTIST 排序 = 3 次 `sortByCached`，每次额外产生 `n` 个 `Pair` + 3 个中间 `List`，约 2 万次对象分配；加上 `lowercase()` 的 locale-sensitive 分配。键盘输入每 180ms 触发一次全量重排 → 明显掉帧。

**修复**
```kotlin
private val sortedLibrary = combine(...) { ... }
    .flowOn(Dispatchers.Default)          // 关键一行
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SortedLibrary())
```
另：`sortByCached`（`:45-46`）可改为 `sortedWith(compareBy { keyOf(it) })` + 预计算 key 数组，省 2/3 分配。

---

### P1-2 进度轮询 500ms 常驻，暂停与后台都不停

- `play/PlayerController.kt:305-322`：`while (isActive) { if (isPlaying) update…; delay(500) }`
- 作用域为 `AppContainer.scope`（`:22`，`Dispatchers.Main.immediate`），**进程存活期间永不退出**

**后果**：暂停后仍然每 500ms 唤醒主线程一次；后台播放时同样每 500ms 做一次 StateFlow 写入（虽有 lifecycle-aware 收集避免重组，但线程唤醒与状态分配照旧）。这是待机耗电与 Doze 唤醒的主要来源。

**修复**：仅在 `isPlaying` 为 true 时维持 500ms，暂停时 `delay(1500)` 或直接挂起在 `snapshotFlow { isPlaying }` 上；更彻底的做法是播放中用 `Animatable` 线性推进 + 每 5s 与 `currentPosition` 校准。

---

### P1-3 Haze 把整个滚动列表当作模糊采样源

- `ui/library/LibraryLists.kt:44`：`LazyColumn(... .hazeSource(hazeState))`
- `ui/library/HomeScreen.kt:159-162`：`AmbientBackdrop` 也是 `hazeSource`
- 3 个玻璃面板消费该源：搜索框（`HomeScreen.kt:308`）、底部栏（`:439`）、MiniPlayer（`MiniPlayer.kt:44`），`GlassSurface` 统一 `blurRadius = 26.dp` + `noiseFactor(0.03f)`

**代价**：Haze 2.x 需要把 source 内容渲染进离屏纹理再模糊。源包含 LazyColumn → **滚动时每帧重建全屏纹理 + 3 处模糊采样**。这是本项目在中低端机上掉帧风险最高的单项。

**修复建议（按优先级）**
1. 把 `hazeSource` 从 LazyColumn 移到固定的 `AmbientBackdrop`（背景层），玻璃面板仍然有「模糊背景」观感，但滚动不再触发纹理重建；
2. 或 `ActivityManager.isLowRamDevice == true` 时把 `blurRadius` 设为 `0.dp` 并提高 tint alpha，做视觉降级；
3. 用 Macrobenchmark + `FrameTimingMetric` 量化后再决定，避免凭感觉优化。

---

### P1-4 播放器打开时下层 HomeScreen 仍在完整绘制

`ui/MilanApp.kt:34-55` 中 HomeScreen 与 PlayerScreen 是 `Box` 兄弟节点；播放页背景不透明，但下层列表 + 2 个 haze source 仍在每帧绘制（Compose 不做遮挡剔除）。这是「下滑手势需要看到下层」的有意设计，但**手势结束后可以停止绘制**：在 `offsetY == 0f` 且 `playerOpen` 时给 HomeScreen 加 `Modifier.graphicsLayer { alpha = 0f }` 或直接 `if (!playerOpen) HomeScreen(...)` 并把下滑动画改为仅在手势进行中显示下层。

---

### P1-5 封面解码尺寸与显示尺寸严重不匹配

- `data/media/ArtworkRepository.kt:196`：`THUMB_SIZE = 512`，列表与播放页共用
- `ui/components/TrackRow.kt:67`：列表缩略图实际 **52dp**；`MiniPlayer.kt:59` 46dp

512×512×4 = **1 MB / 张**；列表缓存上限 24 MB ≈ 24 张专辑，一屏可见 8–10 行 ≈ 10 MB 纹理，滚动时逐张 512px 解码。这是列表卡顿与 GC 压力的第二来源。

**修复**：新增按显示尺寸解码的重载（列表用 ~128px，2× 密度足够），播放页继续 512px；缓存分桶（list/player/backdrop）而非共用一把 LruCache。

---

### P1-6 `Modifier.blur` 在 API 26–30 是空操作，API 31+ 又是冗余开销

- `ui/player/PlayerScreen.kt:235`：`.blur(48.dp)` 作用于全屏背景图

两点问题：
1. Compose 的 `Modifier.blur` 文档明确**仅 Android 12（API 31）以上生效**，`minSdk 26` 意味着 Android 8/9/10/11 上完全不模糊 —— 而源图只有 **96px**（`BACKDROP_SIZE`，`ArtworkRepository.kt:197`）被拉伸到全屏，会呈现明显马赛克色块。
2. API 31+ 上，96px 图放大本身已经足够柔和，再叠一层全屏 `RenderEffect` 几乎无视觉收益，却是每帧的 GPU 成本。

**修复**：去掉 `blur`，改为 96px + `alpha(0.55f)` + 更重的渐变遮罩（`PlayerScreen.kt:240-251` 已有）；若坚持要糊，提升 `BACKDROP_SIZE` 到 256 并只在 API 31+ 启用。

---

### P1-7 冷启动会自动随机播放

- `ui/library/HomeScreen.kt:146-156`：曲库就绪后 `LaunchedEffect` 自动 `playQueue(tracks, Random.nextInt(size))`

首次授权后立刻出声，用户无任何预期；同时拉起前台服务与通知。结合 P0-1（没有关闭按钮），观感是「一打开就自己放歌」。

**建议**：改为「恢复上次播放队列/位置」或完全由用户点击触发；如坚持自动播放，至少改为从第一首顺序开始而非随机。

---

### P1-8 每次冷启动都全量扫 MediaStore（与代码注释相反）

- `ui/library/LibraryViewModel.kt:136-165`：`ensureLoaded()` 注释写「showing the cached snapshot immediately and only rescanning on an explicit user action」，但代码在缓存命中后仍然无条件 `mediaStore.loadTracks()`
- `:158` 只在 **id 集合不等** 时才 `allTracks.value = fresh` 并落盘 → **标题/时长/封面等元数据变更永远不生效**，除非 id 增删或用户长按重新扫描

**修复**：用 `ContentObserver` 监听 `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`；或比对 `count + max(DATE_MODIFIED)`；至少把 `save()` 移到集合相等分支之外。

---

### P1-9 加载态未接线：首启会闪「这里还没有歌曲」

- `ui/library/LibraryViewModel.kt:30,32`：`loading` / `isEmptyLibrary` 已定义
- 全工程 grep：**UI 从未读取这两个字段**
- `ui/library/LibraryLists.kt:33-41`：`tracks.isEmpty()` 直接渲染 `EmptyState`

结果：冷启动或权限刚授予、扫库尚未返回时，用户先看到「把音频文件放到手机的 Music 目录…」，随后列表突然填充。

**修复**：`SongsList` 增加 `loading: Boolean` 分支（骨架屏或 `CircularProgressIndicator`），`loading && tracks.isEmpty()` 时不显示空态。

---

### P1-10 权限被永久拒绝后无法恢复

- `ui/library/HomeScreen.kt:94-99`：只处理 `granted` 分支，未查询 `shouldShowRequestPermissionRationale`
- `:120-126`：未授权时直接渲染 `PermissionGate`

用户勾选「不再询问」后，`launch()` 立即回 `false`，按钮点击毫无反应，应用永久停在该页，无跳转系统设置的入口。

**修复**：区分「首次/可再询问」与「永久拒绝」，后者显示「前往设置」按钮（`Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, package:…uri)`）。

---

## 4. P2 — 规范、安全与代码卫生

| 编号 | 问题 | 证据 | 说明 |
|---|---|---|---|
| P2-1 | 缺单色（monochrome）图标层 | lint `MonochromeLauncherIcon` ×2；`res/mipmap-anydpi-v26/ic_launcher.xml` | Android 13+ 主题图标无法取色；Android 16 QPR2 起系统会直接给彩色图标染色，观感异常 |
| P2-2 | `clipToOutline` 仅 API 31+ 生效 | lint `UnusedAttribute`；`res/layout/widget_music.xml:7` | **Android 8–11 上小组件圆角失效**，封面会溢出 26dp 圆角卡片 |
| P2-3 | 签名缺 v3 / v3.1 / v4 | `apksigner verify`：仅 v2 = true | 无密钥轮换能力；建议开启 v3 + v4（增量安装） |
| P2-4 | `allowBackup` 已弃用 | lint `DataExtractionRules`；`AndroidManifest.xml:26` | 补 `android:dataExtractionRules`（API 31+ 的备份/迁移语义） |
| P2-5 | 前台服务 `exported=true` 无权限保护 | lint `ExportedService`；`AndroidManifest.xml:47-55` | MediaBrowserService 需导出，但任意应用可 bind 并读取曲库元数据。建议在 `MediaSession.Callback.onConnect` 里按包名校验，或声明 signature 级权限 |
| P2-6 | 小组件广播可被任意应用触发 | `widget/MusicWidgetProvider.kt:47-60` + 清单 `exported=true` | 自定义 `ACTION_TOGGLE` 与系统的 `APPWIDGET_UPDATE` 走同一 receiver；建议拆出非导出 receiver 或校验 `getCallingUid` |
| P2-7 | 使用弃用列 `MediaStore.Audio.Media.DATA` | `data/media/MediaStoreRepository.kt:31,52,60` | API 29+ 应改 `RELATIVE_PATH`；且 `EXTERNAL_CONTENT_URI` 只覆盖主卷，**SD 卡曲目会漏**（需 `MediaStore.getExternalVolumeNames`） |
| P2-8 | 小组件不重置封面 | `widget/MusicWidgetProvider.kt:100-109` | 当前曲目无封面时未 `setImageViewResource`，会残留上一首封面 |
| P2-9 | 小组件每次刷新新建位图 | `MusicWidgetProvider.kt:103` | 每次 `createScaledBitmap` 256×256 新分配，长播放列表滚动切换时 GC 抖动 |
| P2-10 | 文案未资源化 | `res/values/strings.xml` 仅 2 条；lint `HardcodedText` | 所有 UI 文案与 `contentDescription` 硬编码在 Kotlin，无法本地化 |
| P2-11 | `lowercase()` 未指定 Locale | `LibraryViewModel.kt:101-109` | 土耳其语环境下 `I` → `ı` 导致排序错乱；用 `lowercase(Locale.ROOT)` |
| P2-12 | `configChanges` 含 `uiMode` | `AndroidManifest.xml:36` | 深色切换不重建 Activity → `enableEdgeToEdge()` 的 auto 样式不会重算，状态栏图标颜色可能滞后 |
| P2-13 | 首次授权触发两次扫库 | `HomeScreen.kt:98` `refresh()` 与 `:105-107` `ensureLoaded()` | 重复 MediaStore 查询，可去重 |
| P2-14 | 通知权限在启动即申请 | `HomeScreen.kt:101-116` | 无场景化申请与 rationale；建议首次开始播放时再请求 |
| P2-15 | 死代码 | `PlayerController.toggleShuffle` / `play()` / `pause()` / `release()`；`ArtworkRepository.invalidate()`；`library/LibrarySection.kt` 整个文件；`ui/LocalProviders.kt`（只 provide 无人 consume）；`Track.folder`（计算后从未使用）；`LibraryUiState.isEmptyLibrary` | 合计约 100 行无效代码。注：`cyclePlayMode` / `cycleRepeatMode` 是 v1.0.37 用户明确要求保留的，不计入 |
| P2-16 | 依赖卫生 | `libs.versions.toml:8` `navigation-compose` 未使用（`usage.txt` 已证被裁掉）；`:35` `haze-blur-materials` 未 import；`build.gradle.kts:90` `ui-tooling-preview` 应为 `debugImplementation` | 不影响 APK，只拖慢构建 |
| P2-17 | 未使用资源 / 冗余限定符 | lint `UnusedResources` ×3、`ObsoleteSdkInt` | `colors.xml` 两个色值、`drawable/ic_launcher_foreground.xml`（自适应图标用的是 mipmap PNG）均未使用 |
| P2-18 | `INTERNET` 移除声明无效 | lint：`was tagged to remove … but no other declaration present` | 无库引入该权限，`tools:node="remove"` 属冗余（保留无坏处） |
| P2-19 | 签名口令明文落盘 | `local.properties`：`KEYSTORE_STORE_PASSWORD=<redacted>` | 口令强度极低且明文；keystore 路径固定为 `~/.android/milanmusic-release.keystore`。建议改用环境变量/`keystore.properties` 并 gitignore |
| P2-20 | 发布物为 APK | `dist/*.apk` | Google Play 新应用与更新强制 **AAB**；若上架需切 `bundleRelease` |
| P2-21 | 隐藏手势发现性差 | `HomeScreen.kt:456-459` 长按「曲库」触发重扫 | 唯一的手动刷新入口无任何视觉提示 |

---

## 5. 最新 Google 安卓规范核对表

| 规范项 | 状态 | 依据 |
|---|---|---|
| targetSdk 满足 2026 年 Play 要求（≥36） | ✅ | 实测 targetSdk **37** |
| 16 KB 页大小支持（2025-11 起硬性） | ✅ | 4 个 .so 的 `p_align` 全为 `0x4000` |
| 内边距/边到边（targetSdk 35+ 强制） | ✅ | `MainActivity.kt:13` `enableEdgeToEdge()`；使用 `statusBarsPadding` / `navigationBarsPadding` |
| 前台服务类型（Android 14+） | ✅ | `foregroundServiceType="mediaPlayback"` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` |
| 通知权限（Android 13+） | ✅ | 声明并在运行时请求 |
| 存储权限分代（`READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE maxSdk=32`） | ✅ | 清单正确，无 `requestLegacyExternalStorage` |
| 预测性返回（Android 16 / targetSdk 36+） | ❌ | 无 `enableOnBackInvokedCallback`，且无 `BackHandler`（见 P0-1） |
| 主题图标 monochrome（Android 13+） | ❌ | lint 已报（P2-1） |
| SplashScreen API（Android 12+） | ⚠️ | 未接入 `core-splashscreen`，`windowBackground` 固定白色，深色模式冷启动白闪 |
| 应用签名 v3 + 密钥轮换 | ❌ | 仅 v2（P2-3） |
| 备份规则 `dataExtractionRules`（Android 12+） | ⚠️ | 仍用弃用的 `allowBackup`（P2-4） |
| 无障碍触摸目标 ≥48dp | ⚠️ | 34dp / 42dp / 40dp / 26dp 多处（见 6.1） |
| 无障碍进度条语义 | ❌ | 自绘进度条无 `progressBarRangeInfo` / `setProgress` |
| 动态取色（Material You） | ⚠️ 可选 | 未接入；当前金箔主题为有意设计，可接受 |
| 64 位支持 | ✅ | arm64-v8a 存在 |
| 权限最小化（无 INTERNET） | ✅ | 实测 APK 无 INTERNET / ACCESS_NETWORK_STATE |

---

## 6. 补充：无障碍专项

### 6.1 触摸目标小于 48dp
| 位置 | 尺寸 |
|---|---|
| `ui/components/TrackRow.kt:98` 喜欢按钮 | 34dp |
| `ui/components/MiniPlayer.kt:86` 播放 | 42dp |
| `ui/components/MiniPlayer.kt:98` 下一首 | 40dp |
| `ui/library/HomeScreen.kt:349-351` 清除搜索 | 26dp |

Material 3 与 Android 无障碍规范要求最小 48×48dp 触摸区（可用 `Modifier.minimumInteractiveComponentSize()` 保持视觉尺寸不变而扩大热区）。

### 6.2 自绘进度条无法被 TalkBack 操作
`ui/player/PlayerScreen.kt:488-545` 用 `Canvas` + `detectHorizontalDragGestures` 实现，缺 `Modifier.semantics { progressBarRangeInfo = … ; setProgress { … } }`。视障用户无法得知也无法调整播放进度，等同功能缺失。

### 6.3 搜索框缺少语义标签
`HomeScreen.kt:326-347` 的 placeholder 只是 `decorationBox` 里的普通 `Text`，不参与语义树；`BasicTextField` 未设 `semantics { contentDescription = … }`，TalkBack 读到一个无标签输入框。

---

## 7. 建议的修复顺序

**批次 A（必须先做，约 1 小时内可完成）**
1. P0-1 加 `BackHandler` + 播放页显式收起按钮 + 清单 `enableOnBackInvokedCallback`
2. P0-2 通知封面改 `setArtworkData` / 自定义 `BitmapLoader`（真机验证 Android 10/11/13）
3. P1-1 `flowOn(Dispatchers.Default)`（一行改动，收益最大）

**批次 B（性能与稳定性）**
4. P1-2 轮询改为播放中才高频
5. P1-5 封面按尺寸解码 + 缓存分桶
6. P1-6 去掉 `Modifier.blur`（或只保留 API 31+ 且换 256px 源）
7. P1-9 加载态接线；P1-10 权限永久拒绝兜底
8. P1-3 / P1-4 Haze 与下层绘制 —— 先上 Macrobenchmark 量化再动手

**批次 C（规范与卫生）**
9. monochrome 图标、`clipToOutline` 的 API<31 替代（`widget_card_bg` 用 `layer-list` + `clip` 或直接不裁）、签名 v3/v4、`dataExtractionRules`
10. 死代码清理 + 依赖清理 + 文案资源化
11. P1-7 / P1-8 启动行为与库同步策略（需要产品决策，建议先定方案再改）

---

## 8. 附：可直接复查的产物

- Lint 报告（HTML/XML/TXT）：`app/build/reports/lint-results-debug.{html,xml,txt}`
- R8 映射与裁剪记录：`app/build/outputs/mapping/release/{mapping.txt,usage.txt,seeds.txt}`
- 本报告：`milan-music/review/2026-10-18-app-review.md`

> 说明：本次审查为**只读**。除运行 lint 生成 `app/build/reports/**` 与新建本报告文件外，未修改任何源码或构建配置。
