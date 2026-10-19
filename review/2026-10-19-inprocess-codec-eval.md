# 进程内软件音频解码器（Android 17 / API 37）评估

| 项 | 值 |
|---|---|
| 评估对象 | `c2.android.inproc.aac.decoder` / `c2.android.inproc.opus.decoder` |
| 平台版本 | Android 17（API 37），经 Mainline APEX 更新 |
| 本项目 | milan-music 1.0.47 · Media3 1.11.1 · minSdk 26 · targetSdk 37 |
| 性质 | 只读评估，未改任何代码 |
| 结论 | **技术描述成立，但对本项目当下几乎无收益** —— 因为 1.0.47 已启用 offload，offload 路径根本不创建解码器<br>（该结论已被真机验证推翻，见文末 §8；实机 offload 永不命中，D1 为主路径） |

---

## 1. 结论表

| 你的描述 | 核实结果 | 证据 |
|---|---|---|
| 传统软解跑在独立 `mediaswcodec` 进程，每帧走 Binder IPC + 上下文切换 | ✅ 成立 | 官方文档「Out-of-process versus in-process decoding」 |
| Android 17 起提供进程内软件音频解码器（Opus / AAC） | ✅ 成立，且经 APEX 下发 | 同上「Starting in Android 17 (API level 37)…」 |
| AAC 用纯 Rust 重写 | ✅ 成立，Fraunhofer FDK2 AAC 移植 | Fraunhofer IIS 官方博客 |
| Opus 用 LFI 沙盒化 | ✅ 成立：4 GB 线性内存槽 + pku + 系统调用拦截，越界返回 `C2_CORRUPTED` | 官方文档 + AOSP 内部书第 43 章 |
| 解码延迟降约 40%、CPU 下降 | ✅ 成立（官方口径"approximately 40%"）；Fraunhofer 口径是"整体复杂度降 40–60% vs Android 16" | 官方文档 / Fraunhofer |
| 显式启用方式：`createByCodecName("c2.android.inproc.aac.decoder")` 或自定义 `MediaCodecSelector` | ✅ 两种都成立，media3 侧有一行现成支持 | `MediaCodecSelector.java:25-40` |
| **暗示"API 37 为此新增了公开 API"** | ❌ 不成立。API 36 与 37 的 `android.jar` 里 `MediaCodec` / `MediaCodecInfo` / `MediaCodecList` 公开接口**逐行相同** | 本地 javap 对比，见 §3 |
| **"对音质有间接价值"** | ⚠️ 需限定：消除的是 underrun/卡顿，不是失真。且 FDK2 由定点改浮点，**不是 bit-exact 替换** | Fraunhofer 原文「ported from fixed-point to floating-point」 |
| **对本项目可直接见效** | ❌ 不成立。offload 生效时链路上没有解码器 | `MediaCodecAudioRenderer.java:357-366`，见 §4 |

---

## 2. 官方文档的关键原文

`developer.android.com/media/platform/in-process-codecs`：

- 存在意义：IPC 延迟、共享内存 `C2Buffer` 同步、重载下的调度争用（会导致 buffer starvation / stuttering）。
- 安全前提：Rust（编译期内存安全）或 LFI（硬件保护的 4 GB 线性内存槽 + Linux pku + 零系统调用）。
- LFI 适用架构：**ARM64 与 x86_64**，不需要定制 CPU 指令。
- 关键一句：`MediaCodecInfo::getSecurityModel()` 把 LFI 路径报为 `SECURITY_MODEL_MEMORY_SAFE`，与 `SECURITY_MODEL_SANDBOXED`（独立进程）区分开 —— 平台自己也没把两者说成等价。

---

## 3. 本地核实：API 37 没有新增公开 API

对 `$SDK/platforms/android-36/android.jar` 与 `android-37.0/android.jar` 分别反编译后逐行 `diff`：

```
android.media.MediaCodec      36: 84 行   37: 84 行   diff 为空
android.media.MediaCodecInfo  13 个 public 方法          diff 为空
android.media.MediaCodecList                            diff 为空
```

`SECURITY_MODEL_MEMORY_SAFE = 1` / `SECURITY_MODEL_SANDBOXED = 0` **在 API 36 就已存在**。

也就是说：平台这次加的是**新的解码器实例**（一个 codec 名字），不是新的 API。应用侧能做的只有两件事——按名字选它，或按 `getSecurityModel()` / `isSoftwareOnly()` 重排候选列表。这对我们是好消息：**不需要 `Build.VERSION.SDK_INT >= 37` 分支，只需要"存在就优先"**。

`grep` 整个 `android-37` 的 `MediaCodecInfo*.class`，`inproc` 出现次数为 0 —— codec 名字是运行时由 `MediaCodecList` 提供的，编译期不存在。

---

## 4. 对本项目的决定性事实：offload 已启用，解码器不在链路上

1.0.47 的 `PlaybackService` 设置了 `AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED`。media3 1.11.1 源码：

```java
// MediaCodecAudioRenderer.java:357-366
// In direct mode, if the format has DRM then we need to use a decoder that only decrypts.
// Else we don't need a decoder at all.
if (supportsFormatDrm && (!formatHasDrm || MediaCodecUtil.getDecryptOnlyDecoderInfo() != null)) {
  audioOffloadSupport = getAudioOffloadSupport(format);
  if (audioSink.supportsFormat(format)) {
    return RendererCapabilities.create(
        C.FORMAT_HANDLED, ADAPTIVE_NOT_SEAMLESS, TUNNELING_SUPPORTED, audioOffloadSupport);
  }
}
```

以及 `MediaCodecAudioRenderer.java:144`：`/** Codec used for DRM decryption only in passthrough and offload. */`

**结论**：offload 命中时（无 DRM），音频以编码帧直接进 `AudioTrack`，解码在 DSP 上完成，`MediaCodecSelector` 从头到尾不会被调用。

因此自定义选择器的真实作用范围只有 **offload 未命中的兜底路径**：

| offload 未命中的原因 | 在本 App 里的概率 |
|---|---|
| 设备/格式不支持 offload（罕见格式、异常采样率） | 中 |
| `setIsGaplessSupportRequired(true)` 收紧后，设备只支持 offload 不支持 gapless | 中（1.0.46 的硬约束） |
| 带 DRM 内容 | 无（本地播放） |
| 音频后处理被启用（将来加均衡器/变速） | 现在无，将来可能有 |

**所以这项优化的收益上限 = offload 兜底路径的占比 × 该路径的解码开销。** 在拿到 offload 命中率之前，无法判断是否值得做。

---

## 5. 若要做：实现草案（约 40 行，未实施）

media3 已经把默认列表暴露成函数引用，不需要自己拼 `MediaCodecList`：

```kotlin
// MediaCodecSelector.java:25-40
MediaCodecSelector.DEFAULT = MediaCodecUtil::getDecoderInfos
MediaCodecSelector.PREFER_SOFTWARE = DEFAULT → getDecoderInfosSortedBySoftwareOnly(...)
```

草案：拿 `DEFAULT` 的列表，把名字含 `.inproc.` 的条目稳定前移，其余顺序不动；不改过滤条件，等于"有就用、没有就走原路"。

```kotlin
private val preferInProcess: MediaCodecSelector = MediaCodecSelector { mimeType, secure, tunneling ->
    val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
    infos.sortedByDescending { it.name.contains(".inproc.") }   // 稳定排序，等价于前移
}
```

接线点（`PlaybackService` 已有的 `DefaultRenderersFactory`）：

```kotlin
DefaultRenderersFactory(this).setMediaCodecSelector(preferInProcess)
```

风险与代价：

1. **进程内解码把攻击面搬进我们进程**。Rust/LFI 的保证很强，但这是"平台认为可以"而非"零风险"；非 inproc 格式仍留在 swcodec。
2. **不是 bit-exact**：FDK2 移植时由定点改浮点，输出 PCM 与 C 版 FDK 不会逐样本相同（精度通常更高，但"更高"≠"更好听"）。
3. **minSdk 26 无需分支**，但要在真机上确认列表里到底有没有这两个 codec —— 这是 APEX 下发的，与 OS 版本不完全绑定。
4. offload 命中时**完全无效**：不要指望它能改善主流播放路径的功耗。

---

## 6. 验证方法（都要真机，当前无设备）

| 目的 | 手段 |
|---|---|
| 设备上是否存在 inproc codec | `adb shell dumpsys media.player \| grep -i inproc`；或临时打一行 `MediaCodecList(ALL_CODECS).codecInfos.map { it.name }` |
| 实际选中了哪个解码器 | `AnalyticsListener.onAudioDecoderInitialized(EventTime, String decoderName, long, long)`（`AnalyticsListener.java:1007`）—— 直接把 `decoderName` 打到 logcat，这是唯一决定性证据 |
| offload 是否命中（决定这项有没有意义） | `dumpsys media.audio_flinger \| grep -iE "offload\|direct\|deep"` |
| 收益 | `dumpsys batterystats` 息屏 30 min 前后对比；`dumpsys gfxinfo` 无用（非渲染路径） |

---

## 7. 决策点

按收益排序，只有三条路：

1. **先测 offload 命中率**（推荐）。命中率高 → 这项直接放弃，省一次改动；命中率低 → 兜底路径占比就是收益上限，再决定。
2. **照草案实施**（约 40 行 + 一次真机验证）。风险极低、可回退，但很可能测不出差异。
3. **不动**。在 offload 为主路径的前提下，这项属于"理论上正确、实测上不可见"。

与 `2026-10-19-memory-power-audit.md` 的关系：这是该审计之外的新增项，编号建议 **D1（decoder）**，暂不进入任何批次，等 §7 的取舍。

---

## 8. 勘误（真机验证后）

§4 的前提「offload 已启用 ⇒ 解码器不在链路上」在本机不成立：

```
PROBE direct=aac/44100  support=4        # 4 = DIRECT_PLAYBACK_BITSTREAM_SUPPORTED，缺 OFFLOAD_SUPPORTED(1)
media3 1.11.1: (support & 1) == 0 → DEFAULT_UNSUPPORTED → offload 永不命中
I PlaybackService: audio decoder=c2.android.inproc.aac.decoder initMs=17
```

即：OPPO PLJ110 / Android 17 上 MediaCodec 软解是**唯一实际路径**，D1 不是兜底优化而是主路径，且实测生效（AAC 17 ms，同机 MP3 软解 76 ms、FLAC 33–41 ms）。

因此 §4 的收益上限要从「offload 兜底路径的占比 × 该路径开销」上修为「整条软解路径」，§7 的决策点 1（先测命中率）已由真机回答：命中率 0。详见 `2026-10-19-device-verification.md` §4 与 `2026-10-19-bg-audio-and-pipeline-eval.md` §5。
