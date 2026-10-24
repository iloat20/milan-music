# 批次 1 执行记录：倒影死代码 / 位图预算余量 / 快照写盘频率

基线：v1.0.64（versionCode 65）。
范围：`2026-10-24-runtime-profile.md` 的 R1、R2、R7 —— 只删浪费，**零视觉变更**。
未动布局、动效、交互与文案；播放页的绘制结果与改前一致。

| 编号 | 问题 | 结论 |
|---|---|---|
| R1 | API 31+ 仍在解码并缓存永不绘制的翻转封面 | ✅ 短路 + 保留封面预热 |
| R2 | 位图预算切片合计 16/16，注释与代码不符 | ✅ 统一 32 分母，30/32，余 1.5 MB |
| R7 | 位置快照每 5 s 写一次 DataStore | ✅ 15 s，720 → 240 次/小时 |

---

## 一、R1 API 31+ 不再生产翻转封面

### 改动

`ArtworkRepository.loadReflection`：

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    loadPlayerArtwork(track)
    return null
}
```

API 26–30 的路径不变，仍然产出 48 px 翻转图；`REFLECTION_SIZE`（512 px）常量删除。

### 为什么是死代码

`CoverReflection` 的判据与两条路径：

- `canRamp(cover, sdk) = cover != null && sdk >= 31`；
- `canDrawBand(hasCover, hasMirror, sdk) = (hasCover && sdk >= 31) || hasMirror`；
- `ramp == true` 时走阶梯路径：`RampLayerContent` 从 `cover` 取行号，翻折由画布变换
  `translate(0, 2·band) · scale(1, -1)` 完成 —— **`mirror` 在整个分支里没有被读取**；
- 只有 `!ramp`（API 30 及以下，或没有封面）才用 `Image(bitmap = mirror)` 走降级路径。

也就是说，第 59 批把判据从 `mirror == null` 改成 `cover != null` 之后，31+ 上这张 512×512
翻转图就只剩缓存占位和瞬时分配，没有任何 draw call 消费它。

### 为什么仍要 `loadPlayerArtwork`

`PlayerViewModel.loadArtwork` 的次序约定（注释 `:101-107`）是「先 `loadReflection`，再取封面」：
`loadReflection` 内部会经 `loadPlayerArtwork` 把封面解码进 `playerCache`，紧随其后的
`loadPlayerArtwork` 必然命中。直接 `return null` 会破坏这个约定，并把调用方暴露给
`loadPlayerArtwork` 的取消竞态。因此 31+ 分支**只跳过翻转，不跳过封面预热**。

### 收益

- 常驻：`reflectionCache` 在 31+ 上不再有条目，约 −1 MB；
- 瞬态：每曲少一次 `softwareCopy`（512² ≈ 1 MB）+ `IntArray`（1 MB）+ `createBitmap`（1 MB）；
- CPU：每曲少一次 512² 行翻转；
- 视觉：无变化（这张图本来就不参与绘制）。

---

## 二、R2 位图预算改回有余量

### 改动

五处切片统一到 32 为分母：

| 缓存 | 改前 | 改后 | 192 MB 堆 |
|---|---|---|---|
| 列表封面 | 8/16 | 16/32 | 12.00 MB（不变） |
| 播放页封面 | 3/16 | 6/32 | 4.50 MB（不变） |
| 播放页背板 | 1/16 | 2/32 | 1.50 MB（不变） |
| 翻转封面 | 2/32 | 1/32 | 1.50 → 0.75 MB |
| 通知封面 | 3/16 | 5/32 | 4.50 → 3.75 MB |
| **合计** | **16/16** | **30/32** | **24.00 → 22.50 MB，余 1.50 MB** |

同时修正 `ArtworkRepository` 类注释（原写 14.5/16）、`BitmapBudget` 的切片说明与
`ArtworkBitmapLoader` 的 3/16 注释，三处现在与代码一致。

### 取舍

- 翻转切片降到 1/32 只在 API 26–30 有意义：条目是 48 px（~10 KB），0.75 MB 能放几十张，
  不会再出现「单张比 cap 大、插入即自淘汰」的问题；
- 通知切片 4.5 → 3.75 MB，512 px 封面从 4 张降到 3 张。通知栏与锁屏始终只有当前曲，
  连续快速切歌时最多少留一张，下一次解码走本地 MediaProvider，不产生可见闪烁；
- 列表与播放页封面**字节数完全不变**，滚动回看深度与轮播的三页常驻保证都不受影响。

### 余量的用途

一次 `loadPlayerArtwork` 的峰值是源图 + `softwareCopy` + 裁剪结果（约 2–3 MB），
`loadReflection` 在 26–30 上再叠 48 px 的小分配。1.5 MB 不能让峰值完全落进预算，
但足以让「插入新封面」不再必然挤掉一张正在用的条目 —— 这正是原注释想表达、而代码已经丢失的性质。

---

## 三、R7 快照写盘 5 s → 15 s

### 改动

`PlaybackService.SNAPSHOT_INTERVAL_MS = 15_000L`，并同步 `PlaybackSnapshotStore` 中
「five seconds」的三处注释。

### 行为

- 播放中每 15 s 写一次 `milan_playback_position`（position + wasPlaying）；
- 切歌、暂停/继续、`persistQueue`、服务销毁仍立即写，不依赖 ticker；
- 进程被杀的代价：最多丢 15 s 进度，恢复后从该点继续。

### 收益

写盘 720 → 240 次/小时，减少 DataStore 的小文件重写与 fsync；息屏播放时 CPU 被拽醒的
周期从 5 s 拉到 15 s。**不是** wake lock 变化 —— 音频播放期间进程本来就醒着。

---

## 四、验证

| 项 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | 通过 |
| `:app:testDebugUnitTest` | **115 tests / 0 failures**（14 个测试类） |
| `:app:lintDebug` | 通过（无新增告警） |
| 真机 | **未执行**：`adb devices` 为空，SDK 无模拟器 |

需要在真机上补的取证（命令见 `2026-10-24-runtime-profile.md` §4）：

1. 播放页 `dumpsys meminfo -d` 的 `Graphics` 与 Java Heap（R1/R2 只动 Java 侧，`Graphics` 应不变）；
2. 息屏播放 10 min 的 `batterystats` Wakeups / mAh（R7）；
3. 切歌 20 次的 `gfxinfo framestats`，确认没有因 R1 引入新的掉帧（理论上只会更少）。

---

## 五、本批未做

- **R4**（背景模糊 1/4 渲染，−15 MB）、**R3**（倒影 5 层 → 3 层，−27～40 MB）、
  **R5**（边缘模糊只画上 1/3，−5～7 MB）都属于视觉参数，按仓库约定需先真机截图 A/B；
- **R6**（Haze 双源）、**R8**（首页卸载）、**R9**（Baseline Profile）留待批次 3；
- 没有引入新的缓存、线程或依赖。
