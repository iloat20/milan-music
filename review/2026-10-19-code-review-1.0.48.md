# 代码审查：米兰音乐 1.0.48（`b90a9c7`）

| 项 | 值 |
|---|---|
| BASE_SHA | `980b10e`（1.0.47） |
| HEAD_SHA | `b90a9c7`（1.0.48，worktree 干净，仅 `review/.release-notes-1.0.48.md` 未跟踪） |
| 代码变更面 | 10 个源文件，+713 / −107（另含 16 份 review 文档与 store 资产，不计入代码审查） |
| 审查口径 | 生产环境：并发、失败路径、权限边界、数据一致性 |
| 结论 | **Request changes** —— 3 项阻断、6 项高优先，改动量合计约 40 行；不建议以当前 HEAD 作为 1.0.48 的发布基线 |

---

## 0. 本次独立复核的事实（不采信发布说明，逐条实测）

| 断言 | 复核方式 | 结果 |
|---|---|---|
| APK 2,477,875 B / SHA-256 `af27abfe…` | `ls -l` + `sha256sum` | ✅ 一致 |
| `classes.dex` 3,808,120 B | `unzip` 后 `ls` | ✅ 一致 |
| dex 探针 `index_generation` / `.inproc.` 入包 | `grep -a -F` 于 `classes.dex` | ✅ 各 1 处（`index_count` / `index_newest` 同为 1） |
| `DefaultAllocator.allocate()` 在 `targetBufferSize == 0` 时行为 | media3 1.11.1 源码 | ✅ 与 target 无关，始终新建 Allocation → **不存在"释放池后无法起播"的 P0** |
| `DefaultLoadControl` 恢复 target 的时机 | 同上 | ✅ 仅在 `onTracksSelected` → `updateAllocator()`；暂停恢复不触发 |
| `MediaCodecRenderer` 是否对 selector 返回列表二次排序 | 同上 | ✅ 不排序，fallback 关闭时**只取 `get(0)`** |
| `DefaultRenderersFactory.enableDecoderFallback` 默认值 | 同上 | ✅ `false` |

> 结论：媒体层的事实基础是可靠的（含"target=0 不会导致停产"这一条，避免了把第 9 项误判为 P0）。问题集中在**应用层的判定逻辑、失败兜底与写入/读取的原子性**。

---

## 1. 阻断问题

### B1 重打标签的重扫结果被丢弃，本版第 1 项的核心收益归零

**位置**：`ui/library/LibraryViewModel.kt:191-201`

```kotlin
val cachedIds = cached.mapTo(HashSet()) { it.id }
val freshIds  = fresh.mapTo(HashSet()) { it.id }
if (cachedIds != freshIds) { allTracks.value = fresh }   // ← 只比 id 集合
libraryCache.save(fresh, stamp)
```

**原因（事实）**：本版把水位线换成 `getGeneration()`，并在 `MediaStoreRepository.kt:88-90` 明确宣称它"covers re-tagging (which a row count would miss)"。但对一首歌改标题/艺术家/专辑时 **id 集合不变**：generation 变化 → 触发全量重扫 → 拿到新元数据 → `cachedIds == freshIds` → 不更新 `allTracks`，磁盘快照被覆盖但屏幕仍是旧标题。用户必须重启才能看到改动，而这次重扫的成本完全白付。收益宣称与落地路径不一致。

**改法**：比较整个列表而非 id 集合（`Track` 是 data class，5k 曲等值比较在 O(n) 内，且两端都按 `TITLE` 排序，可直接比）：

```kotlin
if (cached != fresh) allTracks.value = fresh
```
若担心 `loadTracks()` 的排序稳定性，先各自 `sortedBy { it.id }` 再比。同一处的 `refresh()`（`scan()`）不受影响，因为它无条件赋值。

**验证**：真机改一首歌的标题 → 冷启一次 → 列表标题必须**当次**更新（不是下次启动）；`adb logcat -s LibraryViewModel` 应看到未打印 `rescan skipped`。

---

### B2 `roundedCover` 可能落入"软件画布绘硬件位图"异常，且小组件路径无兜底

**位置**：`widget/MusicWidgetProvider.kt:192-214`（`roundedCover`），调用点 `:168`，上游 `onUpdate` `:44-54`

```kotlin
val output = createBitmap(size, size, Bitmap.Config.ARGB_8888)  // 软件位图
val canvas = Canvas(output)                                      // 软件画布
...
canvas.drawBitmap(source, Rect(...), Rect(0, 0, size, size), paint)
```

**原因**：
- **事实**：`Canvas.drawBitmap` 在软件画布上绘制 `Bitmap.Config.HARDWARE` 源会抛 `IllegalArgumentException("Software rendering doesn't support hardware bitmaps")`。
- **事实**：`source` 来自 `app.container.artwork.load(track)?.asAndroidBitmap()`（`:168`），而该仓库自己在 `data/media/ArtworkRepository.kt:354-366` 写了 `softwareCopy()` 专门处理"MediaProvider 可能返回 HARDWARE 位图"——说明这条分支在本项目被预期出现。
- **事实**：`coverFor` / `render` 无 `runCatching`，异常在 `onUpdate` 的 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`（`:46`）中无人接住 → 未捕获协程异常 → 进程不可用（小组件刷新即崩）。
- **推测（需真机确认）**：旧链路 `Bitmap.createScaledBitmap` 以源 `Config` 创建目标位图（HARDWARE 源 → 硬件目标 + 硬件 Canvas），因此不触发该限制；即本 diff 把"能跑"改成了"可能抛"。

**改法**：
1. `roundedCover` 入口加 `if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) ?: return null`（与 `ArtworkRepository.softwareCopy()` 同一个判断，建议直接复用它、提升可见性）。
2. `onUpdate` 的 `forEach` 内逐 widget `runCatching { render(...) }.onFailure { Log.e(TAG, "widget render failed id=$widgetId", it) }`，保证一个实例失败不拖垮整批、也不杀进程。

**验证**：`:168` 后临时打一条 `Log.d(TAG, "cover config=${bitmap.config}")`，在参考机跑一次小组件；同时用 instrumented test 构造 `Bitmap.Config.HARDWARE` 源调用 `roundedCover`，断言不抛。

---

### B3 解码器偏好把"平台首选"变成"唯一候选"，且没有开启回退

**位置**：`play/InProcessCodecSelector.kt:44-63`；`play/PlaybackService.kt:57-59`

**原因（源码取证，非推测）**：
- `MediaCodecRenderer` 完全信任 selector 的顺序，`enableDecoderFallback == false` 时**只把 `get(0)` 放进候选队列**（`maybeInitCodecWithFallback`）。
- `DefaultRenderersFactory.enableDecoderFallback` 默认 `false`（字段未初始化，构造函数不赋值），本仓库未调用 `setEnableDecoderFallback(true)`。
- 本 diff 把 `c2.android.inproc.*` 提升到音频软件层最前，且这些解码器只在 API 37 存在、无历史运行数据。一次初始化失败（罕见 mime、异常采样率、厂商实现缺陷）就从"换下一条"退化为 `ExoPlaybackException`，播放直接失败。

对本地播放器而言这是可用性回归：排名是**偏好**，不该成为**唯一路径**。

**改法**：`PlaybackService.kt:57` 处追加：

```kotlin
val renderersFactory = DefaultRenderersFactory(this)
    .setMediaCodecSelector(InProcessCodecSelector)
    .setEnableDecoderFallback(true)   // 排名保留，初始化失败继续试下一条
    .setEnableAudioFloatOutput(true)
```

（`setEnableDecoderFallback` 语义即"初始化失败时回退到更低优先级的解码器"，正是本项需要的行为。）

**验证**：`adb logcat -s PlaybackService | grep "audio decoder="` 至少覆盖 AAC / FLAC 24bit-96k / WAV 三种；再用一个故意抛 `DecoderQueryException` 的测试 selector 做 JVM 侧断言（selector 是纯函数，可脱离设备测）。

---

## 2. 高优先改进（建议同批处理）

### P1-1 写侧原子、读侧不原子：`load()` 与 `loadStamp()` 是两次独立读取

**位置**：`ui/library/LibraryViewModel.kt:155`（`libraryCache.load()`）与 `:175`（`libraryCache.loadStamp()`）；`data/media/LibraryCacheStore.kt:93-114`

**原因**：`save()` 把 rows 与 stamp 放进同一个 `edit{}`（这一点做对了），但读侧分两次 `data.first()`。若 `refresh()` 的 `save()` 落在两次读之间，会出现"**旧行 + 新印章**"，`stamp.sameAs(stored)` 命中 → 跳过重扫并继续显示旧快照。概率低，但它恰好破坏了本版宣称的不变式"同事务写入 ⇒ 匹配即一致"。

**改法**：`LibraryCacheStore` 增加一次性读取

```kotlin
data class Snapshot(val tracks: List<Track>, val stamp: IndexStamp?)
suspend fun snapshot(): Snapshot   // 单次 preferences() 内同时解 tracks 与 stamp
```
`load()` 保留给 `MusicWidgetProvider.kt:86` 使用，`load()` / `loadStamp()` 内部复用它。

**验证**：纯函数层测 `IndexStamp.sameAs` 的 6 种组合（null / 双 null / 单侧 generation / 双 generation 相等 / 双 generation 不等 / 旧格式）；Robolectric 下对 DataStore 交错两次 `save`，断言 `snapshot()` 的两个字段永远同源。

---

### P1-2 `isPlaybackActive()` 把"已播完"当成"正在播"，最常见的后台场景不释放池

**位置**：`play/PlaybackService.kt:225-228`

```kotlin
return player.playWhenReady || player.isPlaying
```

**原因（事实）**：队列播完后 `playWhenReady` 保持 `true`、`playbackState == STATE_ENDED`、`isPlaying == false`。于是"专辑放完 → 揣兜里"这条最常路径永远判定为 active，`:219` 的释放条件不成立，第 9 项的回收点在该场景**从不触发**，与注释意图（"background 且未播放"）相反。

**改法**：
```kotlin
val state = player.playbackState
return player.isPlaying || (player.playWhenReady && state != Player.STATE_ENDED && state != Player.STATE_IDLE)
```

**验证**：播放到队列结束 → Home → `adb shell am send-trim-memory com.milan.music RUNNING_LOW`，日志应出现池释放；再 `dumpsys meminfo` 对比。

---

### P1-3 释放阈值可能永不投递（`TRIM_MEMORY_BACKGROUND` 与前台服务互斥）

**位置**：`play/PlaybackService.kt:219`

**原因（部分推测）**：`TRIM_MEMORY_BACKGROUND(40)` 对应"进程已进入 cached LRU"。而暂停后的 `MediaSessionService` 仍持有前台服务通知/绑定客户端，进程不处于该状态，40 档可能不投递。若如此，第 9 项唯一生效点只剩 `onDestroy`——服务销毁时池本就被 GC 回收，等于没做。同仓库的 `ArtworkRepository.kt:179-190` 恰好把 40 留给"真正后台"、20 用于"UI 不可见"，两条判定口径不一致。

**改法**：把释放点降到 `level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN`（20）且 `!isPlaybackActive()`；（可选）另在 `Player.Listener.onPlaybackStateChanged(STATE_ENDED)` 且应用不可见时直接调用 `releaseBufferPool()`，不依赖 trim 投递。

**验证**：这是本条的关键——`adb shell am send-trim-memory com.milan.music BACKGROUND` 与 `UI_HIDDEN` 各发一次，在 `onTrimMemory` 里打 `level` + 池字节（`DefaultAllocator.getUnusedBytesAllocated()`），用日志证伪/证实"40 是否真的到达服务"。

---

### P1-4 两项未披露的运行时行为变更

**位置**：`play/PlaybackService.kt:98`（`bufferForPlaybackMs` 250 → 120）、`:125`（新增 `setPreloadConfiguration(5_000_000L)`）

**原因**：commit message 与 `review/2026-10-19-release-1.0.48.md` 的"本版内容"9 项表**均未包含**这两条，但它们影响所有设备的起播行为与内存占用：
- 120 ms 起播阈值：慢存储（USB/SD、SMB）上有起播欠载风险，且没有给出任何测量；
- 预加载 5 s：新增第二个 `MediaPeriod` 及其 sample queue，与第 9 项的 `trimOnReset=false` 叠加后池内同时存在"当前曲 + 预载曲"的段，内存账目比发布说明描述的大。

**改法**：二者要么回退出本版，要么补进变更清单并各附一行数据（首播 P50/P95、预载后的 PSS）。发布说明是"合并决策"的输入，缺项会让 review 的风险面不完整。

**验证**：`adb shell am start` 冷启计时（`ActivityTaskManager: Displayed`）+ 首帧音频延迟日志；预载对照：`setPreloadConfiguration` 开/关各跑 20 次切歌，比 `dumpsys meminfo` 的 Native Heap 与 GC 次数。

---

### P1-5 静默降级复发：本版根因是"被吞掉的异常"，新代码仍在吞

**位置**：`data/media/MediaStoreRepository.kt:102-121`、`:132-140`

**原因（事实）**：`runCatching { ... }.getOrNull()`（聚合查询、`MediaStore.getGeneration`）两处都不留日志。若某个 OEM 的 provider 对 `getGeneration` 抛异常，回落聚合；聚合若再被拒（正是 1.0.47 的现场），回落 `IndexStamp()`（unknown）→ **每次冷启全量扫描，且依旧无声**。这就是本版第 1 项亲手修掉的那个失败模式，只是换了个触发源。

**改法**：
```kotlin
runCatching { MediaStore.getGeneration(context, volume) }
    .onFailure { Log.w(TAG, "getGeneration($volume) rejected", it) }
```
并在最终返回 `IndexStamp()` 时用 `@Volatile private var reportedUnknown` 门控打一条 `Log.w(TAG, "index stamp unknown; every launch will rescan")`（每进程一次，避免刷屏）。

**验证**：`adb logcat -s MediaStoreRepository`；或在 API 30+ 上临时把 `VOLUME_CANDIDATES` 置空跑一次，确认回落链路有日志。

---

### P1-6 无回归网，且本版有 4 处纯函数可零成本覆盖

**位置**：`app/src/` 下只有 `main`，无 `test` / `androidTest`

**原因**：`IndexStamp.sameAs`（比较语义有 3 个分支）、`BitmapBudget.totalBytes/slice`（钳制与切分）、`ArtworkRepository.sampleSizeFor`（幂次循环）、`MusicWidgetProvider.coverKey` 都是无 Android 依赖的纯逻辑，正是最容易写错、且最值得用红态验证的地方。B1/P1-1 的修复也必须以失败测试前置。

**改法**：最小集——`IndexStampTest`（6 组组合）、`BitmapBudgetTest`（96/192/1024/2048 四档）、`SampleSizeTest`（1200→192 得 4、480→192 得 1）、`roundCover` 的 SRC_IN 像素断言（四角 alpha=0、中心不透明）。JVM 单测即可，不需要 Robolectric。

**验证**：`./gradlew testDebugUnitTest`；每条断言先跑红态。

---

## 3. 低优先（不阻断）

| # | 位置 | 问题 | 改法 |
|---|---|---|---|
| P3-1 | `data/media/BitmapBudget.kt:37-40` | `memoryClassMb * 1024 * 1024` 为 Int 乘法，`memoryClass ≥ 2048` 时溢出为负 → `coerceIn` 静默落到 16 MB 下限。当前未申请 `largeHeap`（≤1024 安全），但与同文件 `slice()` 已用 `Long` 的严谨度不一致 | 改 `(memoryClassMb.toLong() * 1024 * 1024 / DIVISOR).coerceIn(...)` |
| P3-2 | `data/media/ArtworkRepository.kt:174-177` vs `:209-213` | 同文件注释明写"API 34 起 `RUNNING_*` 已弃用且平台停止投递"，紧接着又新增 `RUNNING_MODERATE` 分支；二者只能有一个成立。该分支在 API 34+ 是死代码 | 保留分支则把注释限定为"仅 API 26–33 有效"，或按注释删除分支 |
| P3-3 | `data/media/ArtworkRepository.kt:156-159` | `finally` 先 `remove` 再 `complete`：窗口内新调用者会插入新 deferred 并重复解码；owner 被取消时 `complete(null)` 会把"取消"广播给等待者，同一封面在不同位置出现"有图/无图" | 改为先 `complete` 再 `remove`；取消场景用 `completeExceptionally` 并在等待侧重试一次 |
| P3-4 | `play/PlaybackService.kt:155-166` | `AnalyticsListener` 匿名对象持有 `player` 的引用链（listener 注册在 player 上），`onDestroy` 未 `removeAnalyticsListener`；随 player 释放可回收，属良性 | 存字段并在 `onDestroy` 解绑，或保持现状并在注释里注明"随 player 死亡回收" |

---

## 4. 文档与证据一致性问题（影响对外文案，不影响运行）

| # | 断言 | 事实 |
|---|---|---|
| D1 | commit message："本版有一处用户可感知变化：播放页封面从强制正方形改为保持宽高比裁切（**CoverArt 用 Crop**）" | `CoverArt.kt:44` 自 `8d42fb2`（1.0.44）起默认就是 `ContentScale.Crop`；本提交 numstat 不含任何 UI 文件。真正变化在 `ArtworkRepository.decodeAlbumArt`，且**仅 API 26–28 生效**（API 29+ 走 `loadThumbnail(size,size)`，本就返回目标尺寸）。对外更新说明应写"Android 8/9 设备封面不再被拉伸" |
| D2 | 第 2 项："旧代码在 `loadTracks()` 之后取 stamp → 用户扫描窗口内加歌 → 下次启动跳过扫描 → 漏歌" | 1.0.47 的 watermark 是由**缓存行自身**推导的（`IndexFingerprint(count = cached.size, newestModified = cached.maxOf{...})`，见 `980b10e:LibraryViewModel`），并未在读后取 stamp。扫描窗口内加歌 → 下次启动 `count` 必然不匹配 → 会重扫，**旧实现不存在该漏歌路径**。新实现（读前取、写时复用）在语义上仍更严谨，但迁移理由应改为"消除一次重复 IPC + 让印章与行快照严格同源"。这也解释了该项为何一直列在"未验证"里且无复现步骤 |
| D3 | 第 4 项：1200 px → 192 px "峰值 0.34 MB" | 0.34 MB ≈ 300×300×4 = 360 KB 的采样位图；`ArtworkRepository.kt:267-270` 随后还有一张 192 边长缩放副本（147 KB）同时存活，真实峰值 ≈ 0.5 MB。仍远优于 5.49 MB，但数字低估约 40% |
| D4 | 编号体系 | commit message 与 `review/2026-10-19-release-1.0.48.md` 的 1–9 编号不一致（缓冲池 = commit 第 4 / 文档第 9）。建议以文档为唯一编号源，commit message 引用编号 |

---

## 5. 合并前置验证清单（最小可执行集）

```bash
# 1) B1：重打标签当次生效
adb shell content update --uri content://media/external/audio/media/<id> --bind title:s:'新标题'
# 冷启 → 列表标题应立即变化；logcat -s LibraryViewModel 不应出现 rescan skipped

# 2) P1-1 / P1-5：读取原子性与回落可见性
adb logcat -s LibraryViewModel MediaStoreRepository

# 3) B3 / P1-4：解码器与起播
adb logcat -s PlaybackService    # 期望 audio decoder=c2.android.inproc.aac.decoder initMs≈17
# 三种格式：AAC / FLAC 24bit-96k / WAV；记录 Displayed 时间与首帧延迟

# 4) P1-2 / P1-3：池释放是否真的可达
adb shell am send-trim-memory com.milan.music UI_HIDDEN
adb shell am send-trim-memory com.milan.music BACKGROUND
adb shell dumpsys meminfo com.milan.music | head -20

# 5) B2：小组件
adb shell am broadcast -a com.milan.music.widget.REFRESH -p com.milan.music
# 观察是否崩溃 + cover config 日志

# 6) 回归门槛
./gradlew lintVitalRelease testDebugUnitTest
```

---

## 6. 合并结论

**Request changes。** 三条阻断项各自约 1–5 行改动，根因清晰、修复不会引入新设计：

1. `LibraryViewModel.kt:194` —— 用列表等值替代 id 集合比较（B1）；
2. `MusicWidgetProvider.kt:192` —— 硬件位图转软件副本 + 逐实例 `runCatching`（B2）；
3. `PlaybackService.kt:58` —— `setEnableDecoderFallback(true)`（B3）。

P1-1~P1-6 建议同批：其中 P1-2/P1-3 决定第 9 项是否有实际收益，P1-4 决定发布说明能否作为合并依据，P1-6 是唯一能防止同类回归的手段。

媒体层（media3 交互）事实基础扎实，`targetBufferSize(0)` 不会导致停产、selector 顺序会被尊重——这两点已用 1.11.1 源码核实，无需再投入。问题全部集中在**应用层判定逻辑与失败兜底**，属于可在一次迭代内闭环的范围。

---

## 7. 修复记录（工作区未提交，基于 `b90a9c7`）

三条阻断全部闭合，P1-1 / P1-2 / P1-5 一并处理；P1-3 / P1-4 / P1-6 未动（理由见下）。

| 编号 | 文件:行 | 改动 |
| --- | --- | --- |
| B1 | `ui/library/LibraryViewModel.kt:208` | `cachedIds != freshIds` → `cached != fresh`；`Track` 是 data class，列表等值即逐字段比较，重打标签后当次生效 |
| B2 | `widget/MusicWidgetProvider.kt:210-215` | `roundedCover` 改为 `Bitmap?`，HARDWARE 源先 `copy(ARGB_8888)`；`copy` 返回 null 时降级为"无封面"而非抛异常 |
| B2 | `widget/MusicWidgetProvider.kt:54`、`177` | `onUpdate` 逐实例 `runCatching` + 日志；`coverFor` 二次兜底；`handleToggle` 同样收口 |
| B3 | `play/PlaybackService.kt:65` | `.setEnableDecoderFallback(true)`，selector 恢复为"偏好"而不是唯一候选 |
| P1-1 | `data/media/LibraryCacheStore.kt:89,105` | 新增 `Snapshot` + `snapshot()`，行与水位线来自同一次 `preferences()` 读取；顺带删掉已无调用方的 `loadStamp()`，避免该 API 被再次误用 |
| P1-2 | `play/PlaybackService.kt:244` | `isPlaybackActive()` 排除 `STATE_IDLE` / `STATE_ENDED`，播完后 `TRIM_MEMORY_BACKGROUND` 才真正释放池 |
| P1-5 | `LibraryViewModel.kt:193`、`LibraryCacheStore.kt:113`、`ArtworkRepository.kt:239,376` | 四处静默降级改为 `Log.w`：重扫返回空、缓存 JSON 损坏、封面解码失败、HARDWARE 副本失败 |

**未动项与理由**

- **P1-3**（`TRIM_MEMORY_BACKGROUND` 与前台服务互斥）：改阈值属于行为决策，需要先确认"播完但停在通知栏"是否要释放池；P1-2 修好后该回调至少有一种可达场景，建议真机确认后再调等级。
- **P1-4**（`preload 5s`、`bufferForPlayback 120ms` 未披露）：属发布说明文案，不改代码。
- **P1-6**（无单测）：仓库当前没有 `src/test` 源集，也没有任何 test 依赖（`app/build.gradle.kts` 无 test 配置）。接入 JUnit + 首个测试用例是独立改动，未纳入本次修复。

**验证**

```
./gradlew :app:compileDebugKotlin --offline   # BUILD SUCCESSFUL
./gradlew :app:assembleDebug --offline        # BUILD SUCCESSFUL in 34s
./gradlew :app:lintDebug --offline            # BUILD SUCCESSFUL
```

lint 报告 7 条告警全部为既有项（`MediaStoreRepository.kt:155-156` 的 InlinedApi、Gradle 9.8.1 提示等），无一条来自本次改动的文件。

**仍需真机确认的两点**（本地无法闭环）

1. B2 只在设备真的从 `loadThumbnail` 拿到 HARDWARE 位图时才走到新分支，`adb logcat -s MusicWidget` 应无 `cover render failed`；
2. B3 生效后 `audio decoder=` 日志应仍显示 `c2.android.inproc.*`（顺序不变），只是失败时多了一次回退机会。
