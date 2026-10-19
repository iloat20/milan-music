# 硬件解码器优先与「硬解省 35%」核实

- 日期：2026-10-19
- 版本基线：1.0.47
- 结论：**硬件优先已在代码里实现；「35%」的归属是 audio offload，不是 `hardwareAccelerated`；offload 已决策关闭且有实证。**

---

## 0. 结论表

| 提议 | 判定 | 落点 |
|---|---|---|
| 优先硬件解码器而非软解 | ✅ **已实现** | `InProcessCodecSelector.kt:64`：`hardware + inProcess + otherSoftware` |
| 硬解比软解省约 35% 功耗 | ⚠️ **数字真实，对象错位** | 那是 **audio offload**（压缩帧进 DSP）或**视频**硬解的量级 |
| 音频域存在 `hardwareAccelerated` 解码器 | ❓ **大概率不存在** | AOSP 音频 codec 全为 C2Soft；需真机确认分区是否为空 |
| 需要改代码 | ❌ 不需要 | 除非真机确认排序有误 |

---

## 1. 代码现状：硬件已经在最前，不是"待优化项"

`app/src/main/java/com/milan/music/play/InProcessCodecSelector.kt:58-64`：

```kotlin
val (hardware, software) = ranked.partition { it.hardwareAccelerated }
val (inProcess, otherSoftware) = software.partition { it.name.contains(IN_PROCESS_MARKER) }

if (inProcess.isEmpty()) return ranked
return hardware + inProcess + otherSoftware
```

`partition` 保证 `hardware` 分区整体排在返回列表头部。`MediaCodecAudioRenderer` 按顺序取第一个 `isFormatSupported` 的条目，所以**只要有硬件解码器能解码当前格式，它一定被选中**。

同一个文件 `:25-27` 的注释已经把这条建议的论证写在里面了：

> Hardware decoders stay ahead of everything. Some SoCs expose a hardware AAC decoder; promoting a software one past it would trade a DSP job for a CPU job and cost power instead of saving it, so only the software tier is reordered.

即：**这次改动当初的设计目标就是"绝不把软解排到硬解前面"**，只对软件层内部做了 in-process 优先的重排（为降低 Binder 跳数与调度唤醒，见 `2026-10-19-inprocess-codec-introduce.md`）。

---

## 2. 「35%」的归属

这个数字在公开资料里确实存在，但它描述的是另外两种机制：

| 机制 | 收益量级 | 本项目状态 |
|---|---|---|
| **视频**硬解（H.264/HEVC hardware decoder） | 30–40% | 不适用，本应用无视频 |
| **audio offload**（压缩帧交 DSP 解码） | 30–50% 的音频链路功耗 | **已评估后关闭**，见 §3 |
| `hardwareAccelerated=true` 的音频 MediaCodec | 无公开量级 | 大概率不存在，见 §4 |

`MediaCodec` 的 `hardwareAccelerated` 与 audio offload 是**两条完全不同的路径**：

- **硬件 MediaCodec**：解码仍在 AP 上发生，只是用固定功能单元而非通用 CPU 核；`MediaCodec` 仍然按帧回调，CPU 仍要醒来搬数据。
- **audio offload**：压缩帧直接写进 `AudioTrack` 的 offload 轨道，**DSP 自己解码**，`MediaCodec` 完全不在链路上。`2026-10-19-preload-and-offload-eval.md:41` 已用字节码确认：offload 的输入是未解码压缩码流，PCM 源被显式排除。

所以「省 35%」这件事如果要兑现，靠的是 offload，而不是把 `MediaCodecSelector` 的排序再调一次——那已经是最优形态了。

---

## 3. offload 为什么关着

`2026-10-19-offload-keep-or-disable.md` §9 记录了一次决策反转，实证链条是：

1. 设备上报 `getDirectPlaybackSupport()` = **4**（`DIRECT_PLAYBACK_BITSTREAM_SUPPORTED`），缺少 `OFFLOAD_SUPPORTED(1)` 位
2. media3 1.11.1 的判定是 `(support & 1) == 0 → DEFAULT_UNSUPPORTED`，因此 `getAudioOffloadSupport()` 恒为 0
3. `rendererSupportsOffload` 首步即失败 → **配置写着 ENABLED 时行为也等于 DISABLED**
4. 既然命中率为 0，`PlaybackService.kt:127-130` 明确把 offload 留在默认的 `AUDIO_OFFLOAD_MODE_DISABLED`，并写清了理由

另外 §9.2 记了一条结构性障碍：offload 与 `setIsGaplessSupportRequired(true)` 在 Android 10–12 上互斥，而 gapless 是本项目明确要保的行为。

**结论：不是"没想到用硬解"，是在目标机型上实测命中率为 0 后主动关掉的。**

---

## 4. 唯一残留问题：`hardware` 分区可能是空的

这是本轮唯一值得查的点。`hardwareAccelerated` 的来源（`MediaCodecUtil.isHardwareAccelerated()` 字节码）：

```
API 29+  → MediaCodecInfo.isHardwareAccelerated()
API 26-28 → !isSoftwareOnly(codecName)  // 名字启发式
```

而 AOSP 侧音频解码组件全部是 C2Soft 软件实现（`c2.android.aac.decoder`、`c2.android.mp3.decoder`、`c2.android.flac.decoder` 等），`isHardwareAccelerated()` 返回 false。厂商侧若有音频解码器，也以 `c2.<vendor>.*` 软件形态为主。

**如果目标设备上没有 `hardwareAccelerated=true` 的音频解码器，`hardware` 分区为空，排序退化成 `inProcess + otherSoftware`——"硬件优先"这一层在事实上空转。**

这不影响正确性（列表仍合法），但意味着：

- 这条建议在本项目**没有可兑现的收益**
- 之前关于 in-process 的收益判断仍然独立成立（它只在软件层内重排）

### 怎么确认

现有日志 `PlaybackService.kt:163` 只打被选中的那一个：

```
Log.i(TAG, "audio decoder=$decoderName initMs=$initializationDurationMs")
```

要看全量分布，需要在 `InProcessCodecSelector` 里加一条 debug-only 枚举日志，输出 `name / hardwareAccelerated / softwareOnly / vendor / mimeType`。约 10 行，`BuildConfig.DEBUG` 守卫，release 无开销。

---

## 5. 建议动作

| 选项 | 内容 | 代价 |
|---|---|---|
| A（推荐） | 不写代码，本文件入档 | 0 |
| B | 加 debug-only 解码器枚举日志，真机跑一次确认 `hardware` 分区是否为空 | ~10 行 + 一次设备测试 |
| C | 若真机确认存在硬件音频解码器但未被选中，再谈修排序 | 目前无证据支持 |

**不做**：重新开启 offload。命中率 0 的实证未变，且与 gapless 冲突。

---

## 6. 回退

不涉及代码改动，无回退项。
