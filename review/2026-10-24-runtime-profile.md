# 2026-10-24 运行时画像审查：内存 / CPU / GPU（米兰音乐 1.0.64）

**结论先行**：这份应用不是"到处漏"，而是**三个大头各自集中**：

1. **GPU/图形缓冲是最大单项，约 100–140 MB**，其中倒影叠层（5 层 `blur` + 每层一个离屏层）独占约 2/3，播放页背景的 `blur(72.dp)` 全屏层排第二。Java 位图预算只有 24 MB，图形缓冲是它的 4–6 倍 —— 调 LRU 切片的收益远小于关掉一层模糊。
2. **Java 位图账目已经 100% 占满**：五处切片合计正好 16/16，而类注释写的是 14.5/16（R2 已改为 30/32）。任何一次封面解码的瞬时分配（1–4 MB）都在预算之外，后台播放时进程也没有"还钱"的动作（`onTrimMemory` 只在系统施压时触发）。
3. **CPU 常驻项只剩一个**：播放中每 5 秒一次 DataStore 写（720 次/小时），息屏也在写（R7 已改为 15 秒）。进度轮询、扫描、通知广播都已按正确条件门控，没有发现新的每秒级唤醒。

本文正文是**只读审查**的原始记录（写作时未改任何代码）；其中 R1 / R2 / R7 已在同日的批次 1 中实施，执行记录见 `2026-10-24-batch-1-runtime.md`。**当前没有连接真机、SDK 内也没有模拟器**，因此所有数字都是静态推算，统一按 1080×2376 px（393×864 dp @2.75x，Pixel 7 级）折算，并标注 `待实测`。§4 给出可直接粘贴的取证命令。

---

## 0. 优先级总表

| 编号 | 问题 | 位置 | 目标指标 | 风险 | 批次 |
|---|---|---|---|---|---|
| R1 | API 31+ 仍在解码并缓存永不绘制的翻转封面 | `ArtworkRepository.kt:188-203` · `CoverReflection.kt:258` | 常驻 −1.0～1.5 MB，瞬态 −3～4 MB/曲，每曲一次 512² 翻转 | 极低（死代码） | 1 ✅ |
| R2 | 位图预算切片合计 16/16，注释与代码不符 | `ArtworkRepository.kt:56-59,75-118` · `ArtworkBitmapLoader.kt` | 恢复 1.5 MB 瞬态余量，避免解码时挤掉缓存 | 低 | 1 ✅ |
| R3 | 倒影 5 层 `blur`，每层一个全宽离屏层 | `CoverReflection.kt:375-397` | 图形缓冲 −27～40 MB | 中（需截图对比阶梯） | 2 |
| R4 | 播放页背景 72dp 全屏 `blur` | `PlayerScreen.kt:615-636` | 图形缓冲 −15 MB | 低～中（可做等价缩放） | 2 |
| R5 | 边缘模糊层 `blur(28.dp)` 铺满封面框 | `PlayerScreen.kt:406-417` | 图形缓冲 −5～7 MB | 中（上缘观感） | 2 |
| R6 | 列表 + 背景两个 `hazeSource`，滚动每帧双份捕获 | `HomeScreen.kt:268-288` · `Theme.kt:110` | 滚动帧时间 −1～3 ms | 中（玻璃取景） | 3 |
| R7 | 位置快照每 5 s 写一次 DataStore | `PlaybackService.kt:443-453` · `PlaybackSnapshotStore.kt:204-210` | 写盘 720 → 240 次/小时 | 极低 | 1 ✅ |
| R8 | 播放页打开后首页整棵树（含 Haze 源）仍常驻 | `MilanApp.kt` · `HomeScreen.kt:268` | 峰值 PSS −15～25 MB | 高（转场/状态） | 3 |
| R9 | 无 Baseline Profile | `app/baseline-prof.txt` 不存在 | 冷启 −10～25%（待实测） | 低 | 3 |
| R10 | 正在播放行的 `PlayingBars` 常驻 60 fps | `TrackRow.kt:114-148` | 极小，仅作观察项 | — | 不做 |

批次 1 = 无视觉风险、只删浪费；批次 2 = 视觉参数调整，需真机截图 A/B；批次 3 = 需要产品决策或构建配置。

✅ = 已实施，执行记录见 `2026-10-24-batch-1-runtime.md`（R1 / R2 / R7）。

---

## 1. 内存

### 1.1 Java 堆：账目已经满了（批次 1 已修）

`BitmapBudget.totalBytes()` 取 `memoryClass / 8`，192 MB 堆 → **24 MB** 总预算（下限 16 MB、上限 32 MB）。五处切片的实际值：

| 缓存 | 键 | 切片 | 192 MB 堆下 | 典型占用 |
|---|---|---|---|---|
| 列表封面 192 px | albumId | 8/16 | 12.00 MB | 滚动几屏后 6–9 MB |
| 播放页封面 512 px | albumId | 3/16 | 4.50 MB | 3–4 张 × ~1 MB |
| 播放页背板 96 px | albumId | 1/16 | 1.50 MB | 数十张 × 36 KB |
| 翻转封面 512 px | albumId | 2/32 | 1.50 MB | 1–2 张 |
| 通知/锁屏封面 512 px | trackId | 3/16 | 4.50 MB | 当前曲 + 相邻 |
| **合计** | | | **24.00 MB = 16/16** | |

> 上表是**批次 1 之前**的账目。R2 已把切片改成 30/32（list 16 / player 6 / backdrop 2 / reflection 1 / notification 5），合计 22.50 MB，余 1.50 MB —— 详见 `2026-10-24-batch-1-runtime.md`。

`ArtworkRepository` 的类注释（`:56-59`）写的是"list 8, player 2, backdrop 1, reflection 0.5, notification 3，合计 14.5/16，留 1.5 作解码瞬态"。**代码已经改过两轮（player 2→3、reflection 1/32→2/32），注释没跟上**，余量被吃光。

这不是"数字难看"的问题：一次 `loadPlayerArtwork` 的峰值是 `loadThumbnail` 源图（~1 MB）+ `softwareCopy`（~1 MB）+ 裁剪结果（~1 MB）；`loadReflection` 再叠 `scaled` + `IntArray` + `createBitmap`（约 3 MB）。这些都在五个 LRU 之外，**恰好落在本该留出的 1.5 MB 里**，当时只能靠系统回收或 LRU 自己挤。

### 1.2 后台不还钱

`onTrimMemory`（`:236-275`）只在 `UI_HIDDEN` / `BACKGROUND` / `RUNNING_*` 时触发。播放服务是前台服务，息屏播放时进程一直处于 `RUNNING` 且系统不施压 —— 列表缓存的 12 MB 就这样陪跑一整晚。上一轮审查（`2026-10-19-memory-power-audit.md` M1）已经建立了这个出口，**方向是对的，缺的是"主动在播放页全屏时收缩"**（见 R8）。

### 1.3 图形缓冲：真正的大头

> 图形缓冲不属于 Java 堆，`dumpsys meminfo` 里显示为 `Graphics`（`gl mtrack` / `EGL mtrack`），由 HWUI/Skia 分配。下面的"单缓冲"按 `宽 × 高 × 4 B` 算，模糊的中间缓冲按 2–3 倍估。

| 层 | 像素尺寸 | 单缓冲 | 份数 | 小计 |
|---|---|---|---|---|
| 倒影 5 层（`rampMask` 离屏 + `blur`） | 1080×1540 | 6.65 MB | 5 层 × 2–3 | **66–100 MB** |
| 播放页背景 `blur(72.dp)` | 1080×2376 | 10.26 MB | 2 | **20.5 MB** |
| 边缘模糊 `blur(28.dp)` | 1080×1188 | 5.13 MB | 2 | 10.3 MB |
| 当前页 `heroFadeMask` | 1080×1188 | 5.13 MB | 1 | 5.1 MB |
| 隔壁两页 `heroBottomFadeMask` | 1080×1188 | 5.13 MB | 2 | 10.3 MB |
| 交换链 / 合成缓冲 | 1080×2376 | 10.26 MB | 2–3 | 20–31 MB |
| **播放页合计** | | | | **≈ 100–140 MB（取中 ~120 MB）** |

> 上表是"全部生效"的上界：编译器的绘制裁剪与 Compose 的 layer 复用会让部分离屏层在未被绘制时不实际分配，所以真机 `Graphics` 可能低于表值。**倒影那 5 层的数量级不受影响** —— 它们在切歌时全部参与绘制。

三处可以核对的事实：

- 倒影节点高 = `CoverRamp 128dp + 倒影区 432dp = 560dp`（`CoverReflection.kt:75-77,279`，`heroHeight = 屏高 × 50%`，`CoverCarousel.kt:208-212`），在 2.75x 下 1540 px。**它比封面本身还高 30%**，而真正可见的只有 `FadeEnd = 104dp` 那一段（`CoverReflection.kt:106`）。
- `BlurRamp` 有 6 个 stop，循环 `lastIndex downTo 1` → **5 层全宽模糊**，半径 2/5/12/22/34 dp。每层外面套 `rampMask`（`CompositingStrategy.Offscreen`），所以每层至少两个缓冲。
- 隔壁两页即使停在屏幕外，`heroBottomFadeMask()` 仍会给它们各建一个全尺寸离屏层（`CoverCarousel.kt:577,602`）—— 三页常驻是轮播的手感要求，但这两层只在滑动时才被看见。

**播放页整进程 PSS 估算**：Java ~35–45 MB + 图形 ~100–140 MB + 原生解码/库 ~25–35 MB + APK/代码 ~15 MB ≈ **175–235 MB**。这是"打开播放页"的峰值，不是首页。

### 1.4 首页

首页没有全屏模糊，只有：

- 两个 `hazeSource`：`AmbientBackdrop`（静态 Canvas）和列表 `AnimatedContent`；
- 3 个 `GlassSurface`（搜索框、排序菜单、底栏，`HomeScreen.kt:470,550,646`）+ 迷你播放器自带的 1 个（`MiniPlayer.kt:51`）；排序菜单只在展开时存在，稳态是 3 个面板；
- Haze 输入按 `Balanced = 0.5` 降采样，一屏 capture 约 2.6 MB，加上玻璃面板的中间纹理，合计约 **15–25 MB**。

首页本身不是问题；问题是**播放页打开后首页不卸载**（R8），两份同时计入 PSS。

---

## 2. CPU

### 2.1 息屏播放（最该省电的场景）

| 项 | 频率 | 估算 |
|---|---|---|
| 音频解码 + 输出 | 连续 | 1–3% 单核 |
| 位置快照写 DataStore | 每 5 s（`SNAPSHOT_INTERVAL_MS`） | 720 次/小时；每次一次小文件重写 + fsync |
| 进度轮询 | **0**（`subscriptionCount > 0 && isPlaying` 门控，正确） | 0 |
| UI 帧 | 0（屏幕灭） | 0 |

估算 **2–5% 单核**，其中快照写盘占的比例不大，但它把进程从 CPU idle 里反复拽出来 —— 这是息屏功耗里唯一还能砍的周期项。

### 2.2 亮屏首页

- 静态时 Haze 只捕获一次，没有持续重绘（`AmbientBackdrop` 是纯静态 Canvas，没有无限动画）。
- `PlayingBars` 在"有曲目正在播放"时是无限动画，会以 60 fps 触发一小块区域重绘（`TrackRow.kt:120-138`）。面积小，主线程成本可忽略，但**它让 Choreographer 永不空闲**，对"亮屏待机"的功耗有边际影响。
- 估算 **3–6% 单核**。

### 2.3 滚动列表

- 每帧：Haze 重新捕获列表源（0.5 倍 → 64 万像素），3–4 个玻璃面板重跑折射/色散 shader，列表项复用 + 封面解码偶尔插入。
- 估算 **30–45% 单核**，帧时间 8–14 ms（60 fps 边缘；120 Hz 屏会掉帧）`待实测`。

### 2.4 切歌

一次 `PlayerViewModel.loadArtwork` 串行做四件事：

1. `loadReflection` → 512² `softwareCopy` + `scale` + `IntArray` + `createBitmap` + 行翻转（**API 31+ 完全是白做**）；
2. `loadPlayerArtwork` → 解码 + `trimLightMargins`（整张 512² 逐像素扫描 + 可能的再裁剪）；
3. `loadBackdrop` → 96 px 解码；
4. `paletteOfBitmap` → 96² 逐像素（`Dispatchers.Default`）。

全部在 IO/Default，不卡主线程，但**每曲 10–30 ms 的纯浪费**（R1）加上 3–4 MB 瞬时分配。切歌瞬间的掉帧主要来自 5 层模糊层重建，不是这段 CPU。

### 2.5 冷启动

历史实测 207–228 ms，`LibraryViewModel` 已按 MediaStore generation 水位线跳过重扫。项目**没有 baseline profile**（R9），这是启动阶段唯一还没吃到的常规收益。

---

## 3. GPU

Android 没有公开的"每进程 GPU 占用率"接口。可测量的只有三样：**帧时间**（`gfxinfo`）、**图形缓冲**（`meminfo` 的 `Graphics`）、**GPU 频率/负载节点**（厂商 sysfs，需权限）。所以下面用"每帧要填多少像素"来推算。

| 场景 | 每帧像素工作量 | 判断 |
|---|---|---|
| 首页静止 | 一次 capture + 3–4 个面板 shader，之后只合成 | 轻 |
| 首页滚动 | 0.64 Mpx capture + 3–4 个面板 × 折射/色散/模糊 | **中重，最可能掉帧** |
| 播放页静止 | 只在颜色动画/进度条变化时重绘；模糊层留在缓存 | 轻（但显存常驻 ~100 MB） |
| 播放页切歌 | 重建 5 层 1080×1540 模糊 + 全屏 72dp 模糊 | **峰值，1–3 帧卡顿** |
| 播放页横滑 | 3 页封面 + 倒影 5 层跟着位移（`translationX` 在 layer 上，不重组） | 中 |

两个具体判断：

- **倒影的 5 层不是"5 次模糊"那么简单**：每层都是 `Offscreen` 合成 → 一个源层 + 模糊的横/纵 pass。半径最大的两层（22/34 dp）在 1540 px 高的节点上采样范围很大，是切歌峰值的主因。
- **`blur(72.dp)` 在 1080 px 宽上已经是"平色 + 轻微纹理"**：源图是封面最下 20% 拉伸十余倍，再 `alpha 0.40`。它的视觉贡献接近一层噪声，却要一个全屏 RGBA 缓冲 + 模糊中间缓冲。

---

## 4. 真机取证命令

连接设备后按顺序跑，括号里是要看的字段。

```bash
# 0) 设备与刷新率
adb shell wm size; adb shell wm density; adb shell dumpsys display | grep -i fps

# 1) 内存总账（Java Heap / Graphics / GL mtrack / EGL mtrack / Native）
adb shell dumpsys meminfo com.milan.music -d

# 2) 播放页峰值：打开播放页 → 等 5 s → 再取一次
adb shell dumpsys meminfo com.milan.music | sed -n '1,25p'

# 3) 帧时间：先 reset，滚动 10 s，再取
adb shell dumpsys gfxinfo com.milan.music reset
# （滚动列表 / 滑动封面 / 切歌各做一遍，分三轮）
adb shell dumpsys gfxinfo com.milan.music framestats | head -80

# 4) 渲染线程与主线程谁在忙
adb shell top -H -p $(adb shell pidof com.milan.music) -n 1 -b | head -20

# 5) 息屏播放的唤醒与耗电
adb shell dumpsys batterystats --reset
# （息屏播放 10 min）
adb shell dumpsys batterystats com.milan.music | grep -E "Wakeups|CPU|mAh"

# 6) 冷启动
adb shell am force-stop com.milan.music
adb shell am start -W -n com.milan.music/.MainActivity

# 7) 可选：Perfetto（看 GPU 队列、Haze 每帧耗时）
#    categories: gfx, view, wm, am, power, sched
```

判读标准（建议先定基线再改）：

- `Graphics` > 120 MB → R3/R4 必须做；
- 滚动 `gfxinfo` 的 90th percentile > 16.7 ms → R6 值得做；
- `Wakeups` 里本应用 > 1 次/分钟（息屏播放）→ 查 R7 与音频焦点；
- 切歌 `framestats` 出现 > 32 ms 的帧 → R3 优先。

---

## 5. 优化方案

### 批次 1：只删浪费，零视觉风险

**R1｜API 31+ 不再生产翻转封面 ✅**

`canDrawBand(hasCover, hasMirror, sdk)` 在 `sdk >= 31` 时只看 `cover`（`CoverReflection.kt:258-259`），阶梯路径从 `cover` 取行号、在画布上翻折，**从不读 `mirror`**。也就是说 `loadReflection` 在 31+ 上产出的那张 512×512 翻转图，除了占用 `reflectionCache` 和 3–4 MB 瞬态，没有任何消费者。

改法：`loadReflection` 在 `SDK >= S` 时先 `loadPlayerArtwork(track)` 预热（保持 `PlayerViewModel` 的次序约定，注释 :101-107），然后直接返回 `null`。

收益：常驻 −1.0～1.5 MB、每曲瞬态 −3～4 MB、每曲省一次 512² 翻转；`reflectionCache` 在 31+ 上归零，可把切片让给别处（R2）。

**R2｜预算切片改回有余量 ✅**

统一到 32 为分母，总和 30/32：

| 缓存 | 现在 | 建议 | 192 MB 堆 |
|---|---|---|---|
| list | 8/16 | 16/32 | 12.00 MB |
| player | 3/16 | 6/32 | 4.50 MB |
| backdrop | 1/16 | 2/32 | 1.50 MB |
| reflection | 2/32 | 1/32（31+ 可 1/64） | 0.75 MB |
| notification | 3/16 | 5/32 | 3.75 MB |
| 合计 | 16/16 | 30/32 | **22.50 MB，余 1.50 MB** |

余量不是"闲着"：它对应一次解码的源图 + 中间副本。通知切片 4.5 → 3.75 MB 只意味着连续快速切歌时少留一张 512 px 封面（4 → 3 张），锁屏与通知栏始终只有当前曲，属于可接受的取舍。同时把类注释改成与代码一致的算法（避免下一轮又漂）。

**R7｜快照写盘 5 s → 15 s ✅**

`SNAPSHOT_INTERVAL_MS` 改 15_000。进程被杀最多丢 15 s 进度，而暂停/切歌/`onStop` 仍会立即写。写盘 720 → 240 次/小时。

### 批次 2：视觉参数，需要截图 A/B

**R4｜播放页背景模糊改为 1/4 尺寸渲染**

现在是 `Canvas(fillMaxSize).blur(72.dp)`。改成在一个 `width/4 × height/4` 的 `Canvas` 上画同一张色带、`blur(18.dp)`，再用 `graphicsLayer { scaleX = 4; scaleY = 4 }` 放大。模糊半径与采样密度同比缩小，**输出在数学上近似等价**，缓冲从 10.26 MB 降到 0.64 MB。这是批次 2 里风险最低的一项，但仍需一张播放页截图对比（`alpha 0.40` 下差异应不可见）。

**R3｜倒影 5 层 → 3 层**

保留 `BlurRamp` 的 stop 定义（包络数学不变），只画 `index = 5, 3, 1`（34 / 12 / 2 dp）。中间两层的权重本来就低于两侧，去掉后阶梯更"软"而不是更"断"。预期 −27～40 MB 图形缓冲。必须真机看镜面线附近有没有可见的断层（项目里第 51/58 批都是这条线出的问题）。

**R5｜边缘模糊层只画上缘 1/3**

`edgeHaze` 的可见范围由上缘 mask 限定在 0–24% 高度（`PlayerScreen.kt:536-548`），但 `Image` 仍按 `fillMaxSize` 建全框离屏层。把 `Image` 的高度改成 `heroHeight * 0.3`、`contentScale = Crop` 保持取景，缓冲从 5.13 MB 降到 1.5 MB。风险是上缘那 24% 的取样范围变化，需要对比。

### 批次 3：需要决策或构建配置

**R6｜Haze 源二选一**

现在 `AmbientBackdrop` 和列表都是 `hazeSource`。如果玻璃面板只压在列表上，背景那层 source 永远采不到 —— 用 `HazeSourceSelection` 明确指定，或把背景层移出 source 注册，滚动时少一次全屏捕获。需要真机看玻璃边缘的取景再定。

**R8｜首页在播放页全屏后卸载**

`MilanApp` 目前让首页整棵树（含 Haze 源、列表、3 个玻璃面板）常驻在播放页下面。如果播放页是不透明全屏，可以在转场结束后 `AnimatedVisibility(exit = ...)` 把首页内容移出组合，返回时用 `rememberSaveable` 恢复滚动位置。收益 15–25 MB 峰值 PSS；风险是返回动画首帧（列表要重新组合）。**属于产品取舍，先不做。**

**R9｜Baseline Profile**

用 `androidx.baselineprofile` 生成并随 APK 打包，目标冷启 −10～25%。与运行时占用无关，但属于"启动 CPU"的自然收尾。

**R10｜`PlayingBars` 降到 30 fps（观察项，不建议动）**

无限动画让 Choreographer 常亮。改成 `tween(760 + index * 220)` 只省一点点，观感会变钝。除非 `batterystats` 证明它显著，否则保留。

---

## 6. 不做清单（沿用既有结论）

| 项 | 结论 | 依据 |
|---|---|---|
| RGB_565 列表封面 | 不做 | 渐变/玻璃下有色带，收益 < 视觉损失 |
| `largeHeap` | 不做 | 掩盖问题，且 `BitmapBudget` 已按 memoryClass 缩放 |
| 手动 `recycle` / `System.gc` | 不做 | 与 Compose 生命周期冲突 |
| 引入 Coil/Glide | 不做 | 本地 MediaProvider，现有 MemoCache 已合并并发请求 |
| 减少 Media3 缓冲 / 关闭 offload | 不做 | 已有专项结论（`2026-10-19-offload-keep-or-disable.md`） |
| 去掉 `WAKE_MODE_LOCAL` | 不做 | 后台播放需要 |
| 列表作为 Haze 源 | **产品决策，保留** | `2026-10-19-memory-power-audit.md` P2 |

---

## 7. 与 2026-10-19 审查的关系

`2026-10-19-memory-power-audit.md` 解决的是"有没有回收出口、有没有门控"；本轮回答的是"**这些出口和门控之后，钱花在哪**"。上一轮 M1–M8 / P1–P8 里已实施的项（trim 钩子、Haze 档位、通知位图上限、进度门控、扫描水位线）在本轮复核中**均仍然生效**，没有回退。

本轮新增的三条，全部是上一轮"账目"之外的维度：

1. 图形缓冲第一次被单独记账（上一轮只把 Haze 输入层算到 MB 级）；
2. 位图预算的"注释 vs 代码"漂移第一次被量出来（16/16 vs 14.5/16）；
3. API 31+ 的倒影死代码第一次被定位（第 59 批把判据从 `mirror == null` 改成 `cover != null` 之后，翻转图就只剩缓存占位了）。

---

## 8. 建议执行顺序

1. 真机跑 §4 的 1/3/4/5，拿到 `Graphics`、`gfxinfo` 90th、`top -H`、`Wakeups` 四个基线数；
2. 批次 1（R1/R2/R7）✅ 已完成（`2026-10-24-batch-1-runtime.md`）；真机连上后重跑同一组命令，确认写盘次数与后台 PSS 变化；
3. 批次 2 逐项做，每项单独截图对比镜面线 / 上缘 / 背景三段；
4. 批次 3 单独立项，先议产品语义再动代码。
