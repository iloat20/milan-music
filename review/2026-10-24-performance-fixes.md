# 性能与缺陷修复报告（2026-10-24）

基线：MilanMusic 1.0.64（versionCode 65），AGP 9.4.1 / Kotlin 2.4.21 / Gradle 9.8.2。
范围：播放/扫描/恢复的运行时缺陷，以及不改变视觉参数的 CPU、电量与启动优化。
未改布局、动效、配色、倒影参数与交互。

## 一、结论摘要

| 类别 | 结论 | 状态 |
|---|---|---|
| 媒体库扫描 | 游标中途抛错时会把“部分结果”当成完整库写盘并盖水位线，造成静默缺歌 | 已修复 |
| 播放崩溃 | 快照写入的 DataStore 异常在无异常处理器的服务作用域中直接抛到主线程 | 已修复 |
| 播放页电量 | 播放页盖住首页后，列表当前行的无限动画仍以 60 fps 运行 | 已修复 |
| 启动 | 已缓存曲库在水位线 binder 调用之后才发布；顺序已调整为先出内容 | 已优化 |
| 播放卡顿 | 切歌峰值主要来自 5 层倒影模糊与全屏背景模糊，属视觉参数 | 未改，待真机 A/B |
| 内存 | Java 位图预算已有余量；大头是 GPU 图形缓冲 | 沿用批次 1，待真机量测 |

## 二、本轮修复

### 1. 扫描中断不再覆盖曲库

**现象**：MediaProvider 重启、索引重建或游标读取到一半失败时，曲库可能少一批歌；重启后仍不恢复。极端情况下，用户会以为文件损坏或被删除。

**根因**：

- `MediaStoreRepository.loadTracks()` 捕获所有 `Exception` 后返回 `tracks`。这个列表在异常时只是已经读到的前缀，但类型是 `List<Track>`，调用方无法区分“完整扫描”和“中途失败”。
- `LibraryViewModel` 只用 `fresh.size == 0` 判断异常，非空的部分结果会被 `allTracks.value = fresh` 发布，并由 `libraryCache.save(fresh, stamp)` 持久化。
- 写入使用的 `stamp` 是扫描前读取的 MediaStore generation。若失败发生在同一代索引内，下一次启动会判定“未变化”并跳过重扫，缺失的曲目会长期不可见。

**改动**：

- `MediaStoreRepository.kt:64` 新增 `scanTracks(): TrackScan`；`TrackScan.complete` 仅在游标成功走到末尾后置为 `true`（`TrackScan` 定义见 `:256`）。
- 查询失败仍返回已读行供诊断，但 `complete = false`，不再伪装成完整快照。
- `MediaStoreRepository.kt:153` 抽出 `query()`；API 30+ 若 OEM provider 拒绝 `ALBUM_ARTIST` 投影，会在**尚未读取任何行**时退回不含该列的投影，避免整次扫描失败。
- `LibraryViewModel.kt:357` 新增纯策略 `shouldApplyScan(scannedCount, complete, cachedCount)`：不完整结果永不覆盖；完整但为空的结果只在本地也没有缓存时采用。
- `LibraryViewModel.kt:208`、`:287` 的首次加载与手动刷新都走该策略；不采用时只结束 loading，不写缓存、不 bump revision，下次启动重试。

**预期效果**：

- 部分扫描不会再替换 5 000 首的完整曲库，也不会写入当前 generation。
- 下一次启动仍会重扫并自愈；缺失曲目不再“永久消失”。
- 不增加正常扫描的 I/O；仅多一个布尔分支。

**验证**：

- 红态：`LibraryScanPolicyTest > an incomplete scan never replaces the library` 在修复前失败；修复后通过。
- 覆盖：不完整+有缓存、不完整+无缓存、完整空+有缓存、完整空+无缓存、完整非空替换。
- 真机补充：播放中让 MediaProvider 重启（或 `adb shell content call --uri content://media --method scan_volume` 期间快速刷新），确认列表不缩短；`logcat -s MediaStoreRepo` 应出现 `audio scan incomplete ...`，且无 `libraryCache.save`。

---

### 2. 快照写入失败不再拖垮播放进程

**现象**：存储空间耗尽、DataStore 文件损坏或 provider 异常时，后台播放可能直接崩溃，而不是仅失去“上次播放位置”。

**根因**：

- `PlaybackService` 在 `onTimelineChanged`、`onMediaItemTransition`、播放状态变化、15 秒 ticker 与 `onTaskRemoved` 中用 `serviceScope.launch { persistQueue/persistPosition }` 写 DataStore。
- `serviceScope` 只有 `SupervisorJob + Dispatchers.Main.immediate`，没有 `CoroutineExceptionHandler`；DataStore 的 `edit` 可以抛 `IOException`、`IllegalStateException` 等。未捕获异常在主线程协程中会终止进程。
- 这些写路径发生在息屏播放和“划掉任务”时，正是用户最不希望播放中断的时刻。

**改动**：

- `PlaybackSnapshotStore.kt:306` 新增 `saveSnapshotSafely(label, onFailure, block)`：捕获 `Exception` 并返回 `false`；`CancellationException` 原样抛出，保持结构化并发。
- `PlaybackService.kt:419`、`:439` 的队列与位置写入统一经过该函数；失败只记录 `snapshot ... write failed; playback continues`（`:456`），不中断播放。
- 快照仍是 best-effort：失败不会阻止 `onTaskRemoved` 的 `stopSelf()`，也不会影响当前播放。

**预期效果**：

- 存储/缓存异常从“进程崩溃”降级为“丢失一次恢复点”。
- 15 秒 ticker 单次失败不会取消整个 ticker；下一轮继续尝试。

**验证**：

- 红态：`PlaybackSnapshotTest > a failed snapshot write is contained` 在修复前抛出 `IOException`；修复后断言返回 `false` 且异常被上报。
- `snapshot writes still honour cancellation` 断言 `CancellationException` 仍向上传播，防止把服务销毁当普通写失败吞掉。
- 真机补充：临时填满应用数据分区或注入写失败，息屏播放 10 分钟，确认进程不重启、`logcat` 只有 warning。

---

### 3. 播放页覆盖首页后停止隐藏的无限动画

**现象**：打开播放页后，首页仍保留在 Compose 树中；当前曲目行的 `PlayingBars` 继续以 3 条、约 60 fps 的无限动画运行。画面被播放页完全盖住，但 Choreographer 仍被持续唤醒。

**根因**：

- `MilanApp` 用 `AnimatedVisibility` 把播放页叠加在 `HomeScreen` 上，首页不卸载（这是下滑手势连续性的要求）。
- `TrackRow` 只看 `isCurrent && isPlaying` 就创建 `rememberInfiniteTransition`；它不知道播放页已经盖住自己。

**改动**：

- `TrackRow.kt:120` 新增纯策略 `shouldAnimatePlayingBars(isPlaying, uiCovered)`。
- `HomeScreen.kt:307` 用该策略计算列表的 `isPlaying`；`MilanApp.kt:40` 把 `playerOpen` 作为 `isCovered` 传入（`HomeScreen` 默认 `false`，不改变其他调用）。
- 播放页可见时动画停止；返回首页后 `isCovered = false`，动画立即恢复。列表标题高亮、收藏按钮与迷你播放器不受影响。

**预期效果**：

- 播放页停留期间，首页的 3 个无限动画不再请求帧；静止的播放页只保留进度 ticker（每 500 ms，且仅在播放页可见时运行）。
- 减少无意义的重绘与 GPU 提交，降低亮屏待机功耗；不改变任何可见状态。

**验证**：

- 红态：`NowPlayingIndicatorTest > bars stop while the player sheet covers the library` 在修复前失败；修复后 3 个用例全绿。
- 真机补充：打开播放页静置 30 s，`adb shell dumpsys gfxinfo com.milan.music framestats` 的帧数应显著低于修复前；返回首页后当前行波形正常。

---

### 4. 启动时先发布缓存曲库，再读取水位线

**现象**：已有缓存时，首屏仍要等一次 MediaStore generation 查询完成后才显示歌曲。

**根因**：`LibraryViewModel.ensureLoaded()` 先 `libraryCache.snapshot()`，再 `mediaStore.stamp()`，最后才 `allTracks.value = cached`。缓存解码已完成，但发布被一次 binder 调用挡住。

**改动**：`LibraryViewModel.kt:173-196` 调整为：读取缓存 → 立即发布并结束 loading → 再取 stamp 判断是否需要扫描。扫描前读取水位线的正确性不变（仍是“先 stamp 后 rows”）。

**预期效果**：

- 首屏内容提前一个 MediaStore binder 往返出现；典型设备约数毫秒到数十毫秒，索引繁忙时更明显。
- 不改变缓存命中时的最终内容、扫描条件或写盘顺序。

**验证**：

- 单测全量通过（该路径依赖 Android，未做 JVM 级计时断言）。
- 真机：`am force-stop` 后 `am start -W` 记录 TotalTime，对比修改前后各 5 次；或 Perfetto 看 `libraryCache.snapshot` 到首帧的间隔。

## 三、性能瓶颈分析与未改项

### 3.1 播放卡顿

现有证据（静态估算，详见 `2026-10-24-runtime-profile.md`）：切歌峰值由倒影 5 层全宽 `blur`、播放页 72 dp 全屏背景 `blur`、边缘 28 dp `blur` 构成；倒影叠层节点约 1 540 px 高。可见长度在同日按用户指定从 104 dp 放宽到 128 dp，节点高度与模糊层数未变。

未直接改参数的原因：这些是视觉参数，仓库约定必须真机截图 A/B，尤其镜面线在历史上返工九轮。可落地方案按风险排序：

1. **R4**：背景模糊在 `width/4 × height/4` 画布上以 `blur(18.dp)` 渲染，再放大 4 倍；缓冲从约 10.3 MB 降到约 0.64 MB，数学近似等价。需一张播放页截图确认 `alpha 0.40` 下无可见差异。
2. **R3**：倒影只画 34/12/2 dp 三层，预计图形缓冲 −27～40 MB；必须对比镜面线是否出现断层。
3. **边缘模糊只画上缘 1/3**：缓冲约 5.1 MB → 1.5 MB；需对比上缘 24% 的取景。

验证工具：`dumpsys meminfo -d` 的 `Graphics`、`dumpsys gfxinfo framestats` 的 90th percentile、Perfetto `gfx` 轨道。

### 3.2 内存

- Java 位图预算已按 30/32 分配（192 MB 堆约 22.5 MB，余 1.5 MB 瞬态），见 `ArtworkRepository` 与 `BitmapBudget`。
- API 31+ 不再生产永不绘制的翻转封面，见 `ArtworkRepository.loadReflection`。
- 当前大头是 GPU 图形缓冲（约 100–140 MB 上界），不在 Java 堆；只能通过 3.1 的视觉参数调整解决。
- 没有引入新的缓存、线程或依赖。

### 3.3 启动

- 已有 MediaStore generation 水位线，曲库未变时只付一次 binder；本次把缓存发布提前。
- 尚无 Baseline Profile。它是冷启动 −10～25% 的常规手段，但需要新增 `baselineprofile` 模块与设备测试，属于构建基础设施改动，本轮未做。

### 3.4 电量

- 位置快照从 5 s 改为 15 s，写盘 720 → 240 次/小时；切歌、暂停、销毁仍立即写。
- 进度 ticker 仍由 `subscriptionCount > 0 && isPlaying` 双重门控，屏幕熄灭或播放页关闭即停。
- 本轮新增隐藏动画停止，减少播放页停留时的无效帧。
- 音频 offload 保持默认关闭：参考设备只报告 `DIRECT_PLAYBACK_BITSTREAM_SUPPORTED`，名义开启仍走 MediaCodec，已有专项结论。

## 四、验证记录

| 项 | 结果 |
|---|---|
| 红态 1 | `LibraryScanPolicyTest > an incomplete scan never replaces the library` FAILED（AssertionError） |
| 红态 2 | `PlaybackSnapshotTest > a failed snapshot write is contained` FAILED（IOException） |
| 红态 3 | `NowPlayingIndicatorTest > bars stop while the player sheet covers the library` FAILED（AssertionError） |
| 目标测试转绿 | 两个测试类 + 指示器测试全部通过 |
| 全量单测 | **121 tests / 0 failures / 0 errors / 0 skipped**（`--rerun-tasks`） |
| `:app:lintRelease` | **No issues found.** |
| `:app:assembleRelease` | 成功（R8 + 资源压缩 + lintVital） |
| 最终包 | b66；2,530,956 B；`com.milan.music` / 66 / 1.0.65；arm64-v8a |
| 签名 | v2 `true`，v3 `true`，证书 `CN=Milan Music` |
| 对齐 | `zipalign -c -P 16 -v 4` → Verification successful |
| SHA-256 | `da606cd2c8b9f98cbe8f666097baa8d255d619890cddfa386d6f0c4aeef1aa7e` |
| 真机 | **未执行**：当前无连接设备；上述性能数字除单测外均为静态推算 |

## 五、改动文件

| 文件 | 改动 |
|---|---|
| `data/media/MediaStoreRepository.kt` | `scanTracks` + `TrackScan.complete` + 可选投影回退 |
| `ui/library/LibraryViewModel.kt` | 扫描结果策略、缓存先发布、调用点改造 |
| `play/PlaybackSnapshotStore.kt` | `saveSnapshotSafely` 异常边界 |
| `play/PlaybackService.kt` | 队列/位置写入经过异常边界 |
| `ui/components/TrackRow.kt` | `shouldAnimatePlayingBars` |
| `ui/library/HomeScreen.kt` | 接收 `isCovered`，门控列表动画 |
| `ui/MilanApp.kt` | 传入播放页可见状态 |
| `test/.../LibraryScanPolicyTest.kt` | 不完整扫描回归测试 |
| `test/.../PlaybackSnapshotTest.kt` | 写失败与取消传播测试 |
| `test/.../NowPlayingIndicatorTest.kt` | 隐藏动画回归测试 |

## 六、后续建议

1. 连真机后按 `2026-10-24-runtime-profile.md` §4 取 `Graphics`、`gfxinfo`、`batterystats`、`am start -W` 四组基线。
2. 逐项做 R4 → 边缘模糊 → R3，每项单独截图对比镜面线/上缘/背景，确认后再打包。
3. 评估 Baseline Profile；在 5 000 首曲库上测 `onPlaybackResumption` 的 Binder 大小与回调耗时。
