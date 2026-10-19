# 2026-10-19 内存与功耗专项审查（米兰音乐 1.0.46）

**结论先行**：当前代码的功能性开销已经收得比较干净（延迟绑定服务、暂停即停轮询、192px 列表封面、offload 已开），剩余空间集中在两处：

1. **位图内存没有回收出口**——三处 LRU 上限合计 38 MB，进程在后台播放时一分钱都不还，这是唯一能靠一个钩子就拿到两位数量级收益的项。
2. **模糊链路的分辨率与订阅关系没有约束**——Haze 未设 `performanceMode`（2.0.1 已提供），4 个玻璃面板在列表滚动时按全屏分辨率重采样；进度轮询虽然已经按"是否播放"门控，但没有按"是否有人看"门控，息屏播放时仍每秒两次唤醒主线程。

本文只做只读审查，**未修改任何代码**。所有量级为静态推算，标注 `待实测` 的必须真机取证后才可采信。

---

## 0. 优先级总表

| 编号 | 问题 | 位置 | 目标指标 | 风险 | 批次 |
|---|---|---|---|---|---|
| M1 | 三处位图 LRU 无 `onTrimMemory` 回收出口 | `data/media/ArtworkRepository.kt:45,51` · `play/ArtworkBitmapLoader.kt:53` | 后台 PSS −25～30 MB | 低 | I ✅ |
| M2 | Haze 未设 `performanceMode`（**实测：默认即 Balanced**） | `ui/theme/Theme.kt` · `ui/glass/GlassSurface.kt:62-65` | 输入层 1.57 → 0.63 MB，−0.94 MB | 低 | II ✅（数字已更正，见 §2） |
| M3 | 播放页 96px 背板 + `blur(48.dp)` 建全屏离屏层 | `ui/player/PlayerScreen.kt:286-296` | GPU 峰值 −1 全屏缓冲（~10 MB） | 与批次 B 的保留决定冲突 | III（先议） |
| M4 | 整库 MediaItem 一次跨 Binder 下发 | `play/PlayerController.kt:194-195` | 大曲库 Binder 上限风险 | 中（待实测） | II |
| M5 | 播放页打开时 HomeScreen 全量 Compose 树常驻 | `ui/MilanApp.kt:32-52` | PSS +15～25 MB | 已知产品取舍 | III |
| M6 | 位图账目按 4 B/px 记账，忽略 HARDWARE config | `ArtworkRepository.kt:46,52` · `PlayerScreen.kt:474` | 账目失准 + `getPixels` 崩溃风险 | 待实测 | II ✅ |
| M7 | 小组件每次刷新重建 2 张 256px 位图 | `widget/MusicWidgetProvider.kt:115-117` | 每曲 512 KB 分配 | 低 | II ✅ |
| M8 | API 26–28 内嵌封面全尺寸解码（无 `inSampleSize`） | `play/ArtworkBitmapLoader.kt:91-100` | 单张 3000px 封面瞬时 ~36 MB | 低 | I ✅ |
| P1 | 进度轮询未按订阅者门控 | `play/PlayerController.kt:314-330` | 息屏唤醒 2 次/s → 0 | 低 | I ✅ |
| P2 | 滚动列表作为 Haze 采样源，每帧全屏重录 | `ui/library/LibraryLists.kt:56` | 2 张全屏源层 ≈ 20.7 MB + 滚动每帧 5.2 Mpx | 中 | **不做（产品决策）** |
| P3 | 每次冷启动全量扫 MediaStore | `ui/library/LibraryViewModel.kt:155` | 每次启动 −200～800 ms CPU/IO | 中 | II ✅ |
| P4 | 无小组件实例仍发广播 | `play/PlayerController.kt:301-308` | 每曲一次空广播 | 低 | II ✅ |
| P5 | float 输出是否抵消 offload 收益，未取证 | `play/PlaybackService.kt:39,80-88` | 息屏 mA | 待实测 | II ✅（结论推翻，见 §2） |
| P6 | release 未剥离 `Log.d` 热路径 | `PlayerController.kt:264-269` + `proguard-rules.pro` | 每事件 4 次字符串拼接 | 低 | I ✅ |
| P7 | 正在播放行的 `PlayingBars` 常驻 60 fps | `ui/components/TrackRow.kt:123-149` | 常驻刷新 | 低 | III |
| P8 | 队列在则服务常驻（暂停亦然） | `PlayerController.kt:180-182` | 暂停态 PSS 与通知常驻 | 产品决策 | III |

批次 I = 低风险、纯收益、不改变交互语义；批次 II = 需要测量或涉及数据层；批次 III = 需要产品决策或视觉重设计。

✅ = 已实施，执行记录见 `2026-10-19-batch-i-memory-power.md`（批次 I）、`2026-10-19-batch-j-memory-power-ii.md`（批次 II）与 `2026-10-19-m2-haze-performance-mode.md`（M2，含数字更正）。P2 经 brainstorming 后判定为**产品决策而非优化项**，保留现状，理由见该文档第 5 节。

---

## 1. 内存

### M1 三处位图 LRU 没有回收出口（最高价值项）

**证据**

| 缓存 | 位置 | 字节上限 |
|---|---|---|
| 列表封面（192 px，albumId 键） | `ArtworkRepository.kt:45-48` | 24 MB（`:207`） |
| 播放页封面（512 px，含白边裁剪） | `ArtworkRepository.kt:51-54` | 6 MB（`:208`） |
| 通知 / 锁屏封面（512 px） | `ArtworkBitmapLoader.kt:53-55` | 8 MB（`:115`） |

**机理**：`LruCache` 只在 `put()` 超过上限时驱逐，从不主动缩容。这三处都没有任何 `trimToSize` / `evictAll` 的触发点——`ArtworkBitmapLoader.release()` 只在服务销毁时调用（`PlaybackService.kt:132`），而按当前设计只要队列在，服务就不销毁（`PlayerController.kt:180-182`）。

**后果**：用户浏览一遍大曲库后把 App 切到后台、熄屏听歌，列表封面缓存仍按上限钉在堆里；此时前台只剩一个迷你播放器 + 通知栏封面，24 MB 是纯浪费。低内存机型（2 GB / heap 192 MB）上这是被 LMK 回收的第一梯队。

**改法**：给 `ArtworkRepository` 增加 `onTrimMemory(level)`：

```
TRIM_MEMORY_UI_HIDDEN        → cache.trimToSize(maxSize/6), playerCache.evictAll()
TRIM_MEMORY_RUNNING_LOW      → 两个缓存 trimToSize(maxSize/8)
TRIM_MEMORY_COMPLETE/BACKGROUND → evictAll()
```

挂载点：`MilanMusicApp : Application(), ComponentCallbacks2`，在 `onTrimMemory` 里转发给 container；服务侧在 `PlayerController` 观察到 `isPlaying == false` 且队列空闲时对 `ArtworkBitmapLoader` 做一次 `trimToSize(0)`。

**预估**：后台（息屏播放中）PSS 下降 25～30 MB；前台首屏不变（缓存会重新填充）。
**验证**：`adb shell dumpsys meminfo com.milan.music | grep -E "TOTAL PSS"`，对比"浏览 300 首后 Home 键"与"再回前台"两个时点；并手动触发 `adb shell am send-trim-memory com.milan.music RUNNING_LOW` 检查是否真的缩小。
**风险**：低。唯一副作用是切回前台瞬间封面需要重新解码（albumId 命中磁盘/MediaProvider，非网络）。

---

### M2 Haze 采样层未降采样

> **2026-10-19 更正（brainstorming 后取证）**：本节三处数字全部有误，已由 Haze 2.0.1 官方 sources jar 推翻 ——
> ① 未传 `performanceMode` **不等于**全分辨率，节点默认是 `Default` = `Balanced` = `Fixed(0.5f)`，即 0.79 线性 / 62.5% 像素；
> ② `Fixed(0.5f)` 正是当前默认值，收益为 0，要 ÷4 像素得用 `Fixed(0f)`（= `Performance`，0.5 线性）；
> ③ 捕获层按**每个效果自身尺寸**建（`BlurHelpers.kt:124`），四个面板合计 0.63 Mpx，不是一整屏，所以真实收益是 **0.94 MB** 而非 7 MB。
> 另：半径随缩放同步缩（`BlurVisualEffectUtils.kt:105`），降档不改变观感半径。已实施为 `LocalHazePerformanceMode = Performance`，
> 详见 `2026-10-19-m2-haze-performance-mode.md`。以下原文保留作为对照。

**证据**：`GlassSurface.kt:62-65` 调用 `hazeBlur(input = HazeInput.Backdrop(hazeState), style = style)`，第三个参数 `performanceMode` 未传，走默认值。

**已验证的 API**（反编译 `haze-blur-2.0.1.aar`，非推测）：

```
public static Modifier hazeBlur(Modifier, HazeInput, HazeBlurStyle, HazePerformanceMode, boolean)
HazePerformanceMode.Companion: getDefault() / getBalanced() / getQuality() / getPerformance()
HazePerformanceMode.Fixed(float qualityFraction)   // 值类，直接给 0.5f
BlurInputScalePolicyKt.resolveBlurInputScale(HazePerformanceMode)
```

**机理**：Haze 把 source 渲染进一张离屏层再对其做模糊。默认质量模式下，该层是全屏物理分辨率的线性尺寸——1080×2400 的 ARGB 离屏层约 **10 MB**，2K 屏约 28 MB。模糊本身是逐像素 shader，像素量与分辨率平方成正比。

**改法**：`hazeBlur(HazeInput.Backdrop(hazeState), style, HazePerformanceMode.Fixed(0.5f))`（或直接用 `HazePerformanceMode.Performance`）。0.5 倍输入 = 像素量 ÷4，配合 `blurRadius(26.dp)` 的观感差异在毛玻璃场景下通常不可辨。

**预估**：模糊采样层 −75% 显存；滚动时 GPU 片元写入下降同一量级。
**验证**：`adb shell dumpsys gfxinfo com.milan.music reset` → 滚动 10 s → 读 p90/p95 帧时；同时截图对比 4 个玻璃面板（搜索框、迷你播放器、底部栏、排序菜单）在 0.5 / 1.0 两档下的差异。
**风险**：视觉需人工确认一次。**这属于 UI 观感改动，按项目约定必须先过 brainstorming 再动**。

---

### M3 播放页背板：96 px 源 + 48 dp 全屏模糊（已有保留决定，仅补充一个角度）

**先说明立场**：批次 B 已复核过 `Modifier.blur(48.dp)` 并明确保留（`review/2026-10-19-batch-b-fixes.md`），理由是"图层只随曲目切换失效，无逐帧代价，删除反而丢失平滑"。该判断在**帧时间**维度成立——静态时模糊层是缓存层，不重绘。本文补充的是另外两个此前未讨论的点，不构成推翻：

1. **离屏缓冲的显存**：API 31+ 的 `RenderEffect` 需要一张与控件同尺寸的离屏缓冲，1080×2400 约 10 MB（2K 屏约 28 MB）。它常驻于播放页打开期间，与 Haze 的采样层叠加。这部分不体现在 PSS 里，但会挤压 GPU 带宽和低端机可用显存。
2. **源的冗余**：`BACKDROP_SIZE = 96`（`ArtworkRepository.kt:205`）经 11 倍双线性放大后本身已高度平滑，再叠 48 dp 高斯，人眼可辨差异接近零。也就是说这笔开销买到的观感收益很小。

**结论**：优先级放在最低（批次 III）。若真要做，做法是删 `.blur(48.dp)` + 把 `BACKDROP_SIZE` 提到 160～192 px，并**用截图逐像素对比**证明观感无损后再定。不建议在没有视觉对比的情况下按"省内存"名义移除，那会重演批次 B 已经排除的路径。

---

### M4 整库 MediaItem 一次跨 Binder 下发

**证据**：`PlayerController.kt:193-195`：

```kotlin
queue = tracks
val items = tracks.map { it.toMediaItem() }
mediaController.setMediaItems(items, index, 0L)
```

`toMediaItem()`（`:353-364`）为每条轨道构造 `MediaMetadata`（title / artist / albumTitle / artworkUri）。

**机理**：`MediaController.setMediaItems` 是跨进程调用，`List<MediaItem>` 被 parcel 成单个事务；Binder 事务上限约 1 MB。按每条 MediaItem + metadata bundle 约 200–400 B 估算，**3000～5000 首的曲库已经贴到上限**。此外这份列表在服务进程与 UI 进程各存一份（`PlayerController.queue` 与 session 的 media item 列表），属于纯冗余。

**判定**：待实测。若真机曲库规模小于 1500 首，本条只是"内存冗余"，不是故障；超过则可能出现 `TransactionTooLargeException` 或 `setMediaItems` 静默失败。

**验证**：logcat 过滤 `TransactionTooLargeException` / `RemoteException`；或构造 3000+ 曲目后观察 `playQueue` 是否生效。也可直接读 `dumpsys meminfo` 中两次 `queue` 的持有量。

**改法（若确认）**：只下发窗口化的列表（当前项 ±50），或用 `MediaLibraryService` 的 browse 树按需下发。两种都改播放架构，**不适合放进本轮**。

---

### M5 播放页打开时 HomeScreen 全量常驻

`MilanApp.kt:32-52` 的设计是刻意的（下拉手势要露出下层），代价是播放页打开期间：列表 LazyColumn 的组合、`hazeSource` 采样、AmbientBackdrop 的 4 个全屏 shader 全在内存里。属于**已知取舍**，不在本轮改；若要做，正确做法是手势开始后再恢复下层组合（而不是常驻），属于交互改动。

---

### M6 位图账目失准与 `getPixels` 风险（待实测）

**证据**：`ArtworkRepository.kt:46,52` 与 `ArtworkBitmapLoader.kt:54` 的 `sizeOf` 一律 `w*h*4`；`PlayerScreen.kt:474-475` 与 `ArtworkRepository.kt:147` 都对位图调 `getPixels`。

**机理**：`ContentResolver.loadThumbnail` 返回的 `Bitmap.Config` 由 MediaProvider 实现决定，部分 ROM 返回 `HARDWARE`。若为 `HARDWARE`：① 像素在图形内存而非 Java 堆，`w*h*4` 记账会让 LRU 提前驱逐（体验损失）或漏算真实占用（OOM 风险转移）；② `getPixels()` 在硬件位图上不可读，`trimLightMargins` 与 `dominantColorOf` 会抛异常——`trimLightMargins` 那条路径**没有被 `runCatching` 包住**，异常会穿透到 `LaunchedEffect` 的协程。

**验证**（必须先做）：在 `decodeBitmap` 后打一行 `bitmap.config`，跑一台 Android 12+ 与一台 Android 10 设备。
**改法（若确认 HARDWARE）**：读像素前 `if (config == HARDWARE) bitmap.copy(ARGB_8888, false)`；或统一在解码出口转软件位图，并把 `sizeOf` 改为按实际 `byteCount` 记账（`bitmap.byteCount`）。

---

### M7 小组件每次刷新重建 2 张位图

`MusicWidgetProvider.kt:115-117`：`bitmap.scale(256,256)` + `withRoundedCorners()`（内部再 `createBitmap` 一张），每曲 2 × 256 KB 分配，且没有按 albumId 复用。曲库切换频繁时是 GC 抖动的固定来源。改法：256 px 圆角封面按 albumId 做 6 条 `LruCache`（~1.5 MB 上限），或直接对 `ArtworkRepository` 已有的 192 px 位图只做一次圆角。

### M8 API 26–28 的内嵌封面是全尺寸解码

**证据**：`ArtworkBitmapLoader.kt:91-100`

```kotlin
val bytes = retriever.embeddedPicture ?: return null
BitmapFactory.decodeByteArray(bytes, 0, bytes.size)   // 全尺寸
```

解出来之后才 `scaleDown`（`:102-110`）。

**机理**：`decodeByteArray` 不带 `inSampleSize`，一张 3000×3000 的内嵌封面在 32 位下是 **36 MB 的一次性分配**。API 29+ 走 `loadThumbnail` 时由 provider 直接产出目标尺寸，不受影响；26–28 走的就是这条路径。

**关于 `runCatching`**：`ArtworkBitmapLoader.resolve()` 外层确实有 `runCatching`，而 `runCatching` 捕获的是 `Throwable`（含 `OutOfMemoryError`），所以进程不会因异常直接崩；但**捕获不改变风险性质**——瞬时 36 MB 的申请在 2 GB 机型上足以触发 LMK 直接杀进程，或者在 GC 抖动中让整机卡顿 1～2 秒。1.0.44 的代码审查里这条被记作"OOM 抓不住"，那个判断不准确，真正的问题是峰值本身。

**改法**：两趟解码——`inJustDecodeBounds = true` 读尺寸，算 `inSampleSize` 使最长边 ≥512，再真解码。约 15 行，不改任何对外行为。

**验证**：API 26–28 设备（或模拟器）打开一首带 3000px 内嵌封面的曲子，`dumpsys meminfo` 看 `Native Heap` 与 PSS 峰值回落。

---

## 2. 功耗

### P1 进度轮询未按"订阅者"门控（最高价值项）

**证据**：`PlayerController.kt:314-330`

```kotlin
private fun startProgressLoop() {
    if (progressJob?.isActive == true) return
    progressJob = scope.launch {           // scope = Dispatchers.Main.immediate
        while (isActive) {
            ...
            _progress.update { ... }
            delay(PROGRESS_INTERVAL_MS)     // 500 ms，:369
        }
    }
}
```

当前门控是 `EVENT_IS_PLAYING_CHANGED`（`:86-88`），比 1.0.43 的"连接即起、释放才停"已经好很多；但**门控维度仍然错**：它跟的是"是否在播放"，而不是"是否有人看进度"。

**机理**：`progress` 的唯一消费者是 `PlayerScreen.kt:99` 的 `collectAsStateWithLifecycle()`。息屏或播放页未打开时没有任何订阅，循环依旧每 500 ms 在**主线程**恢复一次协程、分配一个 `PlaybackProgress`、写一次 `StateFlow`。息屏播放 1 小时 = 7200 次主线程唤醒。CPU 在 500 ms 间隔下无法稳定进入深 C-state，这是当前后台耗电里唯一还没有被处理的结构性来源。

**改法**（二选一，都不改交互）

1. 用 `subscriptionCount` 控制：`val progress = _progress.asStateFlow()` 改为在 `PlayerScreen` 侧 `LaunchedEffect` 里 `player.setProgressLoopWanted(true/false)`，实现体只在 `isPlaying && wanted` 时跑循环。
2. 更省事：把轮询间隔按可见性分档——播放页可见 500 ms，不可见时停表（回到页面时用一次即时 `refresh()` 补位）。

**预估**：息屏播放主线程唤醒 7200 次/小时 → 0；估算节省 30～80 mA 量级（须实测，不同 SoC 差异大）。
**验证**：`adb shell dumpsys batterystats --reset` → 息屏播放 30 min → `dumpsys batterystats --charged com.milan.music`，看 `CPU: ... wakeups` 与 `Estimated power use` 前后对比；Perfetto 里看 `sched` 唤醒序列是否消失。

---

### P2 模糊链路的滚动重采样

**证据**：`LibraryLists.kt:56` 把整个 `LazyColumn` 设为 `hazeSource(hazeState)`；`HomeScreen.kt` 中 4 个 `GlassSurface` 消费者。

**机理**：列表滚动时 source 每帧变化 → Haze 每帧重捕获采样层 → 4 个面板各自重模糊。这是"滚动时 GPU 与功耗"的主项，与 M2 是同一个根因的两种表现：**分辨率没有约束，采样频率也没有约束**。

**改法**：先做 M2（输入降采样，收益 4 倍），再评估是否需要把迷你播放器 / 底部栏的采样源从列表换成静态背板（观感变化需确认，属交互视觉改动）。

---

### P3 每次冷启动全量扫 MediaStore

**证据**：`LibraryViewModel.kt:145-174`，`ensureLoaded()` 先读缓存，然后**无条件**执行 `mediaStore.loadTracks()`。

**机理**：缓存只解决了"首帧不等扫描"，没有解决"每次冷启动都扫一遍"。`MediaStoreRepository.kt:39-79` 是全表游标（projection 10 列，`TITLE COLLATE NOCASE` 排序）。几千首的库上是 200～800 ms 的 IO + CPU 突发，每次启动都付。

**改法**（两步，第二步可选）

1. 先做水位探测：`SELECT COUNT(*) AS n, MAX(date_modified) FROM audio WHERE ...`，与缓存里记录的 `(n, maxDateModified)` 比对，相同则跳过全表扫描。`DATE_MODIFIED` 需加入 projection 与 `CachedTrack`。
2. 注册 `ContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)`，前台时增量更新，取代长按重扫的心智负担（**这是交互改动，需先 brainstorming**）。

**验证**：`am start` 到首帧的 `Displayed` 时间（`logcat -b events | grep am_displayed`）前后对比；以及 `dumpsys batterystats` 中 CPU 时间。

---

### P4 没有小组件时仍然广播

`PlayerController.kt:301-308` → `MusicWidgetProvider.refresh(context)` → `widget/MusicWidgetProvider.kt:199-204` 无条件 `sendBroadcast`。曲目切换即一次广播，接收方即使 `getAppWidgetIds()` 为空也会走 `onReceive`。改法：`refresh()` 里先用缓存标志判断"是否有实例"（启动时查一次，`APPWIDGET_ADDED/REMOVED` 时更新）。量级小，但每曲一次、且 `goAsync()` 会拉一个 `CoroutineScope`。

---

### P5 float 输出与 offload 的实际生效情况（待实测，不可推断）

> **2026-10-19 更正（批次 II 取证）**：本节「float 输出不是免费的」一项**判断有误**，已由 media3 1.11.1 官方源码推翻 ——
> `DefaultAudioSink.shouldUseFloatOutput()` 是 `enableFloatOutput && Util.isEncodingHighResolutionPcm(pcmEncoding)`，
> 16-bit 源根本不进 float 路径；且 offload 走 bypass（`DefaultAudioSink.configure()` 的注释原文：
> "Audio processing is not supported in offload or passthrough mode"），与 float 不在同一条链路上。
> 结论：**不需要改 float 设置**，剩下的唯一待测项是「本机 offload 是否真的被选中」。详见
> `2026-10-19-batch-j-memory-power-ii.md` 第四节。以下原文保留作为对照。

**证据**：`PlaybackService.kt:39` `setEnableAudioFloatOutput(true)`；`:80-88` 打开 offload + 强制 gapless；`:65` `setWakeMode(C.WAKE_MODE_LOCAL)`。

**需要说清楚的三件事**

1. **float 输出不是免费的**：输出为 FLOAT PCM 时 AudioTrack 侧的数据搬运量是 16-bit 的两倍。只有当源确实是 24-bit 且用户能听出差别时，这个代价才成立；对 16-bit/44.1k 的主流曲库它是纯开销。
2. **是否抵消 offload 必须取证**：offload 走的是压缩码流直通 DSP，理论上与 float 输出无关；但当设备对某格式不支持 offload 而回落到 PCM 通路时，float 的代价就全额兑现。**不能靠推理定论**。
3. **`WAKE_MODE_LOCAL` 会让"CPU 深睡"这条收益不成立**：1.0.46 release note 已按"CPU 不再逐帧解码"表述，这是准确的；继续对外宣称省电时不要滑向"CPU 进入低功耗态"。

**验证方法**：播放 FLAC 与 MP3 各 2 分钟，`adb shell dumpsys media.audio_flinger | grep -iE "offload|direct|deep"` 确认 offload 线程是否真的存在；同时 `dumpsys batterystats` 读 mA。若 offload 未生效，再评估去掉 float。

---

### P6 release 未剥离日志热路径

`PlayerController.kt:264-269` 每次 `onEvents` 拼 4 段字符串 + `Log.d`；`:145` 同理。`proguard-android-optimize.txt` **不会**移除 `Log.d`。Media3 在一次播放中会连发多个事件，属于每秒级的热路径。

改法：`proguard-rules.pro` 增加

```
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
```

并确认 R8 的 `-dontoptimize` 未被引入。顺带：`refresh()` 里 `queue.firstOrNull { it.id.toString() == id }`（`:262`）是 O(n) + 每首一次 `toString()` 分配，可改为按 `mediaId` 建一次 `HashMap<String, Track>`（队列在 `playQueue` 时已整体替换，映射可同时重建）。

---

### P7 / P8（低优先，列出但不建议本轮做）

- **P7** `TrackRow.kt:123-149` 的 `PlayingBars` 用 `rememberInfiniteTransition` + 3 个 `animateFloat`，只要"正在播放的行"在屏幕上就持续 60 fps 失效。窗口不可见时 Compose 会停帧，实际代价仅在浏览时；若要收，改成单个 `Animatable` 驱动一个 `Canvas` 的重绘（不触发重组）。
- **P8** 队列存在时服务与通知常驻（`PlayerController.kt:180-182`）。这是 1.0.45 明示的产品取舍（保留续播路径）。若要兼顾功耗，可加"暂停超过 N 分钟且无 UI → 保存队列快照后释放"；属于产品决策，且需要恢复路径，**不在本轮**。

---

## 3. 明确不做（避免伪优化）

| 想法 | 为什么不做 |
|---|---|
| 降 `DefaultLoadControl` 的 15 s / 30 s 缓冲来省内存 | 缓冲是喂给 DSP 的，offload 靠它避免欠载；省的是一两 MB，换来的是爆音与欠载 |
| 去掉 `WAKE_MODE_LOCAL` | 息屏解码依赖它；社区"去掉唤醒锁省电"的说法来自无 offload 场景，不适用 |
| `largeHeap=true` | 掩盖问题，且在低内存设备上更容易被 LMK 针对 |
| 强制 `RGB_565` 解封面 | 省一半内存，但渐变与暗部出现色带，与水墨金箔的视觉要求冲突 |
| `System.gc()` / 手动 `recycle()` | Compose 仍持有 `ImageBitmap` 引用，手动回收会踩 use-after-free；交给 `onTrimMemory` 与 LRU |
| 引入 Coil / Glide | 本项目已有 albumId 键的去重 + 双档尺寸缓存，换库只增加体积 |
| 后台把输出采样率 48k 降到 44.1k | 三者皆不成立：Media3 无此 API（`DefaultAudioSink.Builder` 无采样率设定项）；解码成本与输出采样率无关；样本数只少 8.1%，理论整机收益 0.5–0.8%，还要付一次自建 SRC。详见 `2026-10-19-background-sample-rate-eval.md` |

---

## 4. 测量基线（做任何一项之前先跑一遍）

```bash
PKG=com.milan.music

# 内存：冷启 / 浏览 300 首后回后台 / 息屏播放 10 min
adb shell dumpsys meminfo $PKG | grep -E "TOTAL PSS|Java Heap|Native Heap|Graphics"

# 功耗：分项（CPU / wakeups / 估算 mA）
adb shell dumpsys batterystats --reset
# ... 息屏播放 30 min ...
adb shell dumpsys batterystats --charged $PKG | sed -n '1,120p'

# 帧时：滚动曲库 10 s
adb shell dumpsys gfxinfo $PKG reset
# ... 滚动 ...
adb shell dumpsys gfxinfo $PKG | grep -E "p50|p90|p95|Janky"

# offload 是否真的在跑
adb shell dumpsys media.audio_flinger | grep -iE "offload|direct"

# 人为触发内存回收钩子（验证 M1）
adb shell am send-trim-memory $PKG RUNNING_LOW
```

**验收口径**（相对基线，而非绝对值）

| 指标 | 现状 | 批次 I 后目标 |
|---|---|---|
| 后台（息屏播放中）PSS | 待测 | −25 MB 以上 |
| 息屏 30 min 唤醒次数 | 待测（预期 ~3600） | ≈ 0 |
| 滚动 p90 帧时 | 待测 | 不劣化（M2/P2 目标是改善） |
| 冷启动到首帧 | 待测 | 不劣化 |

---

## 5. 建议执行顺序

- **批次 I（纯收益、不碰任何用户可见行为）**：M1 → P1 → M8 → P6。四项都可独立验证、无视觉风险，建议一次提交、按第 4 节的命令序列前后对比。**✅ 已完成，记录在 `review/2026-10-19-batch-i-memory-power.md`。**
- **批次 II（需取证或需视觉确认）**：M6、P5 取证；P4、P3、M7（数据层与小组件）；M2 + P2 属视觉档位。**✅ M6 / M7 / P3 / P4 已实施（`review/2026-10-19-batch-j-memory-power-ii.md`）；P5 取证后结论推翻、无需改代码；M2 已实施（`review/2026-10-19-m2-haze-performance-mode.md`，同时更正了本节的三处数字）；P2 经 brainstorming 判定为产品决策，保留。**
- **批次 III（需产品决策）**：M3（已有保留决定，需截图对比）、M5、M4（视曲库规模决定是否做）、P7、P8。

批次 I 的三处实现与审计初稿的差异（均已在执行记录中说明）：`TRIM_MEMORY_*` 常量在 API 34/35 后大面积弃用，实际只剩 `UI_HIDDEN` 与 `BACKGROUND` 两个信号，档位表据此收敛；`ArtworkBitmapLoader` 不在 `AppContainer` 内，改由 `PlaybackService.onTrimMemory` 转发；`P6` 的 `-assumenosideeffects` 只覆盖 `Log.v` / `Log.d`，`Log.e` 保留。
