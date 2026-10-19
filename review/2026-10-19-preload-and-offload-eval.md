# 预加载与硬件卸载：逐条核实

**日期**：2026-10-19
**范围**：只读核实，未改动代码
**来源**：media3 1.11.1（`media3-exoplayer` / `media3-common` aar 字节码）、Android 37 平台 jar

---

## 一、结论先行

| 论点 | 判定 | 一句话 |
|---|---|---|
| `PreloadMediaSource` 存在且可预缓冲 | ✅ 属实 | 但它的服务对象是列表滚动预览，不是「首播延迟」 |
| offload 把**解码后的 PCM** 交给硬件通道 | ❌ 事实错误 | offload 送的是**未解码的压缩帧**；PCM 源被代码显式排除在 offload 之外 |
| offload 让 CPU 进入更深休眠 | ⚠️ 一半 | 平台设计如此，但本项目 `WAKE_MODE_LOCAL` 持有 partial wakelock，CPU 不能 suspend |
| 日常播放「显著降低功耗」 | ⚠️ 需限定 | 在 Android 10–12 上命中率恒为 0（见 `offload-keep-or-disable.md`） |
| AURALIS 已做 offload + USB Bit-perfect 双路径 | ❓ 无法证实 | 应用确实存在，但公开描述里没有 USB Bit-perfect 或双通道卸载 |

---

## 二、最要紧的一处：offload 不碰 PCM

「将解码后的 PCM 交给设备的硬件音频卸载通道」这句如果成立，offload 就完全没有意义了——
解码本来就在 CPU 上做，把解完的 PCM 再转手一次不会省任何功耗。实际链路正好相反：

`AudioTrackAudioOutputProvider.getOutputConfig()` 的 offload 分支（字节码偏移 76–131）：

```
sampleRate = format.sampleRate          // 源的压缩采样率，不解码
frameSize  = -1                         // iconst_m1，压缩流没有固定帧长
encoding   = MimeTypes.getEncoding(format.sampleMimeType, format.codecs)
             → 例如 ENCODING_AAC_LC(10)，HE-AAC 还会被改写成 AAC-LC + 采样率减半
```

同一方法的**第一个分支**就把 PCM 源挡掉了：

```
format.sampleMimeType == "audio/raw"  →  isOffload = false
```

也就是说：**offload 的输入是压缩码流，输出端 DSP 自己解码**。这也是它唯一的收益来源——
CPU 不再跑 `MediaCodec`。这条与前面 D1 的结论互相印证：offload 生效时 `MediaCodecSelector`
根本不会被调用（`MediaCodecAudioRenderer` 不建解码器）。

顺带一个反例校验：如果按「送 PCM」来理解，那 WAV 应该最容易 offload，事实是 WAV 永远命中不了。

---

## 三、「让 CPU 进入更深休眠」在本项目不成立

平台层面，offload 的设计目标确实是让 AP 少干活。但收益兑现需要 CPU 真的能停：

- `PlaybackService.kt:71` 设了 `setWakeMode(C.WAKE_MODE_LOCAL)`，持 partial wake lock
  → CPU 不能进 suspend，只能降频空转。
- 所以本项目里 offload 省的是**解码工作量**，不是「睡得更深」。要拿后者必须去掉 wakelock，
  代价是部分 OEM 息屏后会卡住解码线程——1.0.46 已经权衡过，不值得。

再叠加上一份结论：因为 `setIsGaplessSupportRequired(true)`（`PlaybackService.kt:91`），
`DefaultTrackSelector.rendererSupportsOffload` 在 API 29–32 上永远判定不支持。
「日常播放显著降功耗」这句在 Android 10–12 用户身上是**零**。

---

## 四、PreloadMediaSource：API 真实，但用错了场景

media3 1.11.1 里确实有这一套（均已核实存在）：

| 类 | 作用 |
|---|---|
| `PreloadMediaSource` + `PreloadMediaSource.Factory` | 包装普通 `MediaSource`，提供 `preload(durationUs)` / `clear()`，内部是 `PreloadMediaPeriod` |
| `DefaultPreloadManager.Builder` | 需要 `RenderersFactory` / `TrackSelector.Factory` / `LoadControl` / `BandwidthMeter` / **独立 preload Looper** |
| `ExoPlayer.setPreloadConfiguration(PreloadConfiguration)` | 更轻的等价物：只预载播放列表里的**下一首** |

`PreloadConfiguration` 只有一个字段 `targetPreloadDurationUs`，`DEFAULT` 是
`Long.MIN_VALUE + 1`（`-9223372036854775807`）作为「关闭」哨兵。

### 为什么它救不了「首播延迟」

本地文件的首播耗时拆开是这样：

| 阶段 | 量级 | 预加载能不能省 |
|---|---|---|
| 读盘（ContentResolver + 文件头） | 几 ms | 能，但本来就不是瓶颈 |
| 解码器初始化 | 10–50 ms | **不能**（必须在按下播放后建） |
| 攒够缓冲才出声（`bufferForPlaybackMs`） | **250 ms** | 能，且这是唯一的大头 |

`PlaybackService.kt:56-60` 的 `DefaultLoadControl` 里，`bufferForPlaybackMs = 250`。
也就是说「按下播放」到「出声」之间固定先等 250 ms 音频，这个数比预加载能省的量大一个数量级。

`PreloadMediaSource` 真正解决的问题是「用户还没选中，但可能马上要选」——
列表快速滚动时的试听、网格预览、播放列表连播预热。它需要维护第二套
TrackSelector/Renderer 配置和独立 Looper，每个预载项占一个 `MediaPeriod` 的 SampleQueue
（存的是**压缩样本**，所以 FLAC 约 0.6 MB/5 s、AAC 约 0.2 MB/5 s，WAV 是原始 PCM 约 0.9 MB/5 s）。
对一个刚做完内存专项的本地播放器，这是纯支出。

### 如果要动手，性价比排序

| 方案 | 成本 | 收益 |
|---|---|---|
| **A. `bufferForPlaybackMs` 250 → 120** | 1 行，0 内存；抗抖动余量下降 | 首播延迟直接砍约 130 ms，可立刻体感 |
| B. `setPreloadConfiguration(PreloadConfiguration(5_000_000))` | 每个下一首几百 KB 的 SampleQueue | 切下一首更快；但 offload + gapless 已覆盖大部分场景 |
| C. `PreloadMediaSource` + `DefaultPreloadManager` | 独立 Looper + 第二套 Renderer/TrackSelector 配置 + 每项一个 MediaPeriod | 只有做「列表试听」才有意义 |

注意 A 的一个前提：offload 生效时出声起点由 AudioTrack 的卸载缓冲决定，
`bufferForPlaybackMs` 主要作用在 PCM 路径，改完要分两条路径各测一次。

---

## 五、AURALIS 那条

应用是真实存在的（`com.auralis.music.player`，Google Play 2025-12 上架，约 2.2 万下载），
但公开描述只有均衡器、低音增强、混响、可视化、3D 主题，**没有 USB Bit-perfect，
也没有双通道卸载的表述**。这条无法证实，也不该拿来当技术依据。

---

## 六、建议

1. **不做 `PreloadMediaSource`。** 场景错配 + 内存支出，与我们刚做完的内存专项方向相反。
2. **可做 A：把 `bufferForPlaybackMs` 从 250 降到 120。** 这是本轮唯一有实测价值的一行改动，
   零内存成本，风险是弱设备上起播更容易断一次。
3. **offload 保持现状**，理由已在前一份文档里给过；不要因为「送 PCM 给 DSP」这种错误理解去改它。
4. 切歌慢如果有用户反馈，先加 A，再考虑 B；C 留到真有列表试听需求时再说。

---

## 七、实施记录（2026-10-19 18:2x）

### A ✅ 已改

`PlaybackService.kt:56-62`

```kotlin
/* bufferForPlaybackMs = */ 120,          // 原 250
/* bufferForPlaybackAfterRebufferMs = */ 500,   // 不动
```

### B ✅ 已改

`PlaybackService.kt:87`，构建完成之后、设置 offload 偏好之前：

```kotlin
player.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(5_000_000L))
```

单位微秒，5 秒压缩样本。**必须显式设置**——`PreloadConfiguration.DEFAULT` 是
`Long.MIN_VALUE + 1`（`-9223372036854775807`）这个哨兵，等于关闭。

### C ❌ 未做

`PreloadMediaSource` 与 B 在职责上重叠：B 已经由播放器自己预热**下一首**，
C 是另起一套 preload 管理器（独立 Looper + 第二套 TrackSelector/Renderer 配置 +
每个预载项一个 `MediaPeriod`）去预热**用户还没选的任意项**。两个同时开会重复占用缓冲预算。
C 只有在「列表滚动试听」这种明确需求下才成立，本应用没有这个交互。

---

## 八、验证

| 项 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | ✅ |
| `:app:lintDebug` | ✅ 0 error / 4 warning（与基线同为 AGP 版本提示） |
| `:app:assembleRelease`（含 `lintVitalRelease`） | ✅ |
| release 字节码 | `const/16 v12, 0x78`（120）进入 `DefaultLoadControl` 参数表；`const-wide/32 v5, 0x4c4b40`（5,000,000）构造 `PreloadConfiguration` |
| R8 | `ExoPlayerImpl.setPreloadConfiguration`（源行 934–939）与 `ExoPlayerImplInternal.setPreloadConfiguration`（源行 465）被内联进 `PlaybackService.onCreate`，故产物里搜不到方法名 |
| 产物 | 2,477,875 B，SHA-256 `fad1452cbf299c4e49cbaa563133192fda1f998a40adc5bb45923f4df42ce89a` |

> 第 5 次出现「压缩后字节数与 1.0.44–1.0.47 完全相同」，判断是否重建仍然只能看 SHA-256。

**未发版**：versionCode 仍 48 / versionName 1.0.47。

### 待真机确认

1. 首播延迟是否真的缩短（与 1.0.47 对比同一首歌的起播时间）。
2. 弱存储设备（eMMC / 慢速 SD 卡）上起播是否出现一次欠载——这是把 250 降到 120 的唯一风险。
3. offload 路径单独测一次：卸载下出声起点由 DSP 缓冲决定，`bufferForPlaybackMs` 未必是主导项。
4. 切歌时观察是否出现重复解码（B 与 offload / gapless 的交互）。

