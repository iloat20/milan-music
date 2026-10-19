# 后台降低输出采样率（48 kHz → 44.1 kHz）省电评估

- 日期：2026-10-19
- 版本基线：1.0.47
- 结论：**不做。无接口、方向相反、量级对不上 12%。**

---

## 0. 结论表

| 待验证的说法 | 判定 | 依据 |
|---|---|---|
| Media3 支持动态改输出采样率 | ❌ **无此 API** | `DefaultAudioSink.Builder` 全部方法中无采样率设定项（字节码实证，见 §1） |
| 降采样能减少解码工作量 | ❌ **不成立** | 解码成本由源帧数决定，与输出采样率无关 |
| 48k → 44.1k 能省 12% 整机功耗 | ❌ **量级差两个数量级** | 理论上限约 0.5–0.8%（见 §3） |
| 实现后会增加功耗 | ✅ | 需自建 SRC，且 AudioFlinger 可能再转回 48k，变成两次重采样 |

**建议动作**：并入 `2026-10-19-memory-power-audit.md` §3「明确不做（避免伪优化）」表，不写代码。

---

## 1. 接口层：Media3 根本没有采样率这个旋钮

`androidx.media3.exoplayer.audio.DefaultAudioSink$Builder` 的全部公开方法（`javap` 于 media3-exoplayer-1.11.1.aar，与项目依赖版本一致）：

```
setAudioCapabilities(AudioCapabilities)
setAudioProcessors(AudioProcessor[])
setAudioProcessorChain(AudioProcessorChain)
setEnableFloatOutput(boolean)
setEnableAudioTrackPlaybackParams(boolean)
setEnableAudioOutputPlaybackParameters(boolean)
setAudioTrackBufferSizeProvider(AudioTrackBufferSizeProvider)
setAudioOffloadSupportProvider(AudioOffloadSupportProvider)
setExperimentalAudioOffloadListener(AudioOffloadListener)
setAudioTrackProvider(AudioTrackProvider)
build()
```

**没有 `setSampleRate`，也没有 `setOutputSampleRate`。** AudioTrack 的采样率来自渲染器最终给出的 `AudioFormat`，渲染器又按源格式给。

唯一能改 `AudioFormat.sampleRate` 的入口是 `AudioProcessor`：

```java
public abstract AudioFormat configure(AudioFormat) throws UnhandledAudioFormatException;
```

即：处理器**自己**把输入格式改写成 44.1 kHz，并**自己**负责重采样。而官方的 `SonicAudioProcessor` 只提供：

```
setSpeed(float)
setPitch(float)
```

`setSpeed(44100/48000)` 理论上能凑出重采样效果，但同时改变播放速度与音高，需再用 `setPitch` 反向补偿——这是一条典型的高 CPU、低音质的绕路，不是"降低采样率"。

**结论：要实现这件事，必须自己写一个 polyphase 重采样器接入 `setAudioProcessors`。** 这是新增 DSP 阶段，不是配置调整。

---

## 2. 方向：省的是样本数，付的是重采样

| 环节 | 48 kHz 输出 | 44.1 kHz 输出 |
|---|---|---|
| FLAC/AAC 解码 | 按源帧数算，**不变** | 按源帧数算，**不变** |
| 应用侧 DSP | 无 | **新增 SRC**（96,000 → 88,200 sample/s） |
| AudioFlinger 混音 | 若混音器为 48k，**零转换** | 若混音器为 48k，**再做一次 44.1 → 48 转换** |
| AudioTrack 数据搬运 | 192 KB/s | 176 KB/s |
| DAC / 耳放 | 常开，功耗与采样率基本无关 | 同左 |

关键在于第三行：**Android 的混音器工作采样率通常是固定的 48 kHz**（`AudioTrack` 指定非 48k 时框架会插入自己的重采样）。所以应用侧降频的净效果是：

- 少搬 8.1% 的样本（192 KB/s → 176 KB/s，15 KB/s）
- 增加一次应用侧 SRC（96,000 sample/s × 每样本 ~30 次乘加 ≈ 3M MAC/s）
- 可能再增加一次框架侧 SRC（44.1 → 48）

**多干了两份活，只换来 15 KB/s 的搬运量差。** 这是负收益。

---

## 3. 功耗账：12% 的量级对不上

按 48k → 44.1k 计算的样本数差：

```
(48000 - 44100) / 48000 = 8.125%
```

假设混音环节占音频链路总功耗的 10%（这是偏高的估计，实际混音是纯整数累加，比解码轻得多）：

```
8.125% × 10% = 0.81%（音频链路内）
```

熄屏播放时音频链路约占整机功耗的 60–80%，折算到整机：

```
0.81% × 80% ≈ 0.65%
```

**理论上限约 0.5–0.8%，还不扣掉新增 SRC 的开销。** 距离 12% 差了两个数量级。

### 12% 那个数据大概率是什么

公开测试里确实存在"hi-res 播放比 CD 音质播放耗电高 10–20%"的结论。但那个对比的两端是：

- A 组：播放 **192 kHz / 24-bit 源文件**，解码器输出 192k PCM，AudioTrack 跑 192k，混音器额外转换
- B 组：播放 **44.1 kHz / 16-bit 源文件**

差异来自**源文件与解码路径**，不是运行时对已解码 PCM 做重配置。而对 FLAC/ALAC 这类无损格式，**解码器没有"以更低采样率输出"的选项**——采样率是流参数，输出就是它。想让 48k 源变 44.1k，只能在解码**之后**加 SRC，回到 §2 的负收益结论。

唯一在采样率维度上真实省电的场景是"源本身就是高解析，且在解码前就被降级"。本项目曲库是本地 MediaStore 扫出来的普通音乐文件，主流为 16/44.1 与 16/48；即使存在 96k/192k 的 hi-res 文件，也没有合法的解码前降级路径。

---

## 4. 顺带核实的三个相邻项

| 项 | 现状 | 判定 |
|---|---|---|
| `setEnableAudioFloatOutput(true)`（`PlaybackService.kt:45`） | 16-bit 源走 `toInt16PcmAudioProcessor`，float 标志不参与 | 已正确，无需为省电关闭（`batch-j` 已论证） |
| 进度轮询 500 ms | `PlayerController.kt:355-374` 双门控：`progressObserved` && `isPlaying` | 后台无唤醒，已修 |
| 小组件广播 | `MusicWidgetProvider.kt:66` 有 `ids.isNotEmpty()` 守卫 | 无小组件时不刷新，已修 |
| release 日志热路径 | `isMinifyEnabled = true` + `isShrinkResources = true` | 已启用 |

`memory-power-audit.md` 列的 P1 / P4 / P6 均已闭环，采样率这维度没有剩余空间。

---

## 5. 如果仍要数据说话

真机跑 A/B 对照，用 `dumpsys batterystats` 的 mAh 差值终结争论：

1. 固定一条 48 kHz / 16-bit 曲目，循环播放 30 min，记录 `batterystats` 的音频子系统耗电
2. 同一个 APK 接入自建 44.1k 重采样器，同样条件再跑一次
3. 预期结果：B 组耗电**不低于** A 组

代价是写一个 polyphase resampler（约 150 行）+ 一次 1 小时的设备测试。花这个代价去证伪一个已经能从架构层否定的方案，只有在你需要一份可引用的实测报告时才值得。

---

## 6. 回退

不涉及代码改动，无回退项。
