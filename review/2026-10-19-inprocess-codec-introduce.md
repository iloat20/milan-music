# 引入进程内软件音频解码器偏好（D1）

| 项 | 值 |
|---|---|
| 前置 | `2026-10-19-inprocess-codec-eval.md`（评估结论：技术成立，但 offload 命中时无效） |
| 改动 | 新增 `play/InProcessCodecSelector.kt`；`PlaybackService` 接线 + 两路埋点 |
| 版本 | 未发版（工作区改动，versionCode 仍为 48 / 1.0.47） |
| 验证 | `compileDebugKotlin` ✅ · `lintDebug` ✅ · `assembleRelease`(R8) ✅ · dex 探针 ✅ |
| 状态 | 代码就绪，**收益待真机确认**（需要 offload 命中率） |

---

## 1. 改了什么

```kotlin
// PlaybackService.kt
val renderersFactory = DefaultRenderersFactory(this)
    .setMediaCodecSelector(InProcessCodecSelector)   // 新增
    .setEnableAudioFloatOutput(true)
```

`InProcessCodecSelector` 是新文件（约 70 行，含注释），逻辑只有四处判断：

1. `requiresSecureDecoder || requiresTunnelingDecoder` → 原样返回。DRM 与隧道播放是硬件专属路径，进程内解码器不服务这类场景，不改动等于不碰现有 DRM 行为。
2. `!mimeType.startsWith("audio/")` → 原样返回。`DefaultRenderersFactory` 把同一个 selector 交给视频渲染器，而视频没有进程内解码器可优先。
3. 硬件解码器保持最前，其次 `c2.android.inproc.*`，最后其余软件解码器。
4. 列表里没有 inproc → 原样返回（`partition` 保持相对顺序，老设备拿到的列表与默认完全一致）。

没有 `Build.VERSION.SDK_INT >= 37` 分支。API 37 加的是**解码器实例**而不是 API（评估文档 §3 已用 `android.jar` 逐行 diff 证实），所以"存在就优先、不存在就回退"由运行时的 `MediaCodecList` 天然保证。

---

## 2. 为什么不用评估文档里的那版草案

草案是：

```kotlin
infos.sortedByDescending { it.name.contains(".inproc.") }
```

它把 inproc 提到**整个列表最前面**，包括越过硬件解码器。这在本项目里是个真实的方向性错误：

- 多数设备的 AAC 确实是软件解码，此时草案与现方案结果相同；
- 但部分 SoC 暴露硬件 AAC 解码器。越过它就等于把一帧解码从 DSP 挪回 CPU —— 与"降功耗"的目标正好相反，而且这条路径上没有 IPC 可以省。

现方案分成三层（硬件 → inproc → 其余软件），最坏情况是"什么都不变"，不存在把硬件解码换掉的路径。

`MediaCodecSelector.PREFER_SOFTWARE` 存在说明默认顺序确实是硬件优先，这也从侧面印证了不能无条件前移。

---

## 3. 埋点：一次真机同时回答两个问题

评估文档 §7 把"先测 offload 命中率"列为推荐路径。埋点不单独做一次真机验证，直接并进这次改动，因为**没有它就无法判断这次改动是否生效**。

### 3.1 一个被字节码推翻的写法

第一版用的是 `AudioOffloadListener.onOffloadedPlayback(boolean)`。构建通过，但 dex 探针发现 `"audio offload="` 在产物里出现 **0 次**，而同一处写的 `"audio decoder="` 出现 1 次。`mapping.txt` 显示 `PlaybackService$onCreate$2 -> t02` 是个**空类**，方法体全被 R8 删掉了。

往上追到 library 的字节码（`media3-exoplayer-1.11.1.aar`，`javap -c`）：

```
ExoPlayerImpl.updatePlaybackInfo(...)                       // 唯一读取 audioOffloadListeners 的地方
   812: getfield  sleepingForOffload:Z   (旧值)
   819: getfield  sleepingForOffload:Z   (新值)
   822: if_icmpeq 871                    // 没变化就跳过
   826: getfield  audioOffloadListeners
   863: invokeinterface AudioOffloadListener.onSleepingForOffloadChanged:(Z)V
   871: return
```

`onOffloadedPlayback` **在整个 media3 里没有任何调用点**，是 1.11.1 的历史遗留死方法。R8 判定它不可达，于是连 override 带日志一起删了 —— 删除是对的，那段代码在运行时确实永远不会执行。

### 3.2 改成有调用点的那个

```kotlin
player.addAnalyticsListener(... onAudioDecoderInitialized(...) { Log.i(TAG, "audio decoder=$decoderName initMs=$d") })
player.addAudioOffloadListener(... onSleepingForOffloadChanged(isSleeping) { Log.i(TAG, "audio offloadSinkSleeping=$isSleeping") })
```

两条日志互补且互斥：

| logcat | 含义 |
|---|---|
| `audio offloadSinkSleeping=true` | 平台接受了直出音轨，sink 进入 offload 休眠 —— 解码器未创建，selector 全程未被调用 |
| `audio decoder=c2.android.inproc.aac.decoder` | 进程内解码器已生效，IPC 与调度抖动消除真实发生在播放路径上 |
| `audio decoder=c2.android.aac.decoder` | 兜底路径走到了，但设备上没有 inproc 解码器（APEX 未下发 / 非 ARM64） |
| `audio decoder=...omx.google.aac.decoder` | 同上，另一家厂商的软件解码器 |
| 两条都没有 | 要么播放没起来（看 `playback error`），要么这首太短没等到 offload 状态变化 |

`audio offloadSinkSleeping` 只在状态**变化**时触发，所以它缺席不代表 offload 没生效 —— 但 `audio decoder` 的缺席加上正常出声，已经足以判定走的是 offload 路径。反过来只要出现 `audio decoder=`，就一定是兜底路径。

频率是每首歌至多一次（解码器按格式复用，offload 状态不逐帧变化），用 `Log.i` 才能在 release 包里看到；`Log.d/v` 已被 R8 的 `-assumenosideeffects` 剥离。

---

## 4. 验证

| 项 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `:app:lintDebug` | 0 error，4 warning（`AndroidGradlePluginVersion` ×1 + `NewerVersionAvailable` ×3，与基线一致） |
| `:app:assembleRelease`（R8） | BUILD SUCCESSFUL，`lintVitalRelease` 无 issue |
| 真机 | **未验证**（设备不在线） |

dex 探针（release，R8 之后）：

```
audio decoder=              1
audio offloadSinkSleeping=  1
.inproc.                    1
initMs=                     1
InProcessCodecSelector.INSTANCE -> a0
InProcessCodecSelector.getDecoderInfos(...) -> e   (源行 42/55/70/71/72 均在 mapping 内)
```

接线也做了字节码核对：`onCreate` 里是 `sget-object InProcessCodecSelector.INSTANCE` 接 `iput-object` 到 `DefaultRenderersFactory` 的 `MediaCodecSelector` 字段（`Lvd1;`），紧随其后是 `setEnableAudioFloatOutput(true)` 的布尔字段写入 —— 顺序与源码一致。

产物：2,477,875 B，SHA-256 `bdcd358de3c890469bc1cd437cc0ae9acb14bb2d03bba5c1ccb8429c71cfb080`。

**注意：这一次的 APK 压缩后字节数又是 2,477,875 —— 与 1.0.44/45/46/47 完全相同**，但内容必然不同（多了两个字符串常量与一个新类）。判断产物是否重建只能用 SHA-256，不能用文件大小。

真机待测（并入既有 9 项清单）：

1. `adb logcat -s PlaybackService` 播放一首 AAC，看出现的是哪条日志；
2. 若出现 `decoder=` 且名字不含 `inproc`，先 `adb shell dumpsys media.player | grep -i inproc` 确认设备有没有下发该解码器，再判定是"预期内无收益"还是"选择器没生效"；
3. 若 inproc 生效，用 `dumpsys batterystats` 息屏 30 min 对比，并留意多声道 AAC / 异常采样率文件是否仍能正常出声（回退是否走到下一个候选）。

---

## 5. 风险与回退

| 风险 | 处置 |
|---|---|
| 进程内解码把解码器放进我们进程，攻击面转移 | 该路径由平台以 Rust（编译期内存安全）或 LFI（4 GB 硬件保护内存槽 + pku + 零系统调用）实现，`getSecurityModel()` 报 `SECURITY_MODEL_MEMORY_SAFE`；风险低于等价的 C/C++ 库，但确实不是零 |
| 输出不再是 bit-exact（FDK2 由定点改浮点） | 精度通常更高，但"更高"≠"更好听"。只在兜底路径生效 |
| 某首歌 inproc 解码失败 | 排序只是偏好：渲染器遍历列表取第一个 `isFormatSupported` 为真的条目，失败会落到下一个候选，与改动前一致 |
| 完全回退 | 删 `PlaybackService` 里 `.setMediaCodecSelector(InProcessCodecSelector)` 一行即可，新文件留着不影响（未被引用会被 R8 移除） |

---

## 6. 结论

引入的**正确性**是确定的：不改变任何过滤条件，只在软件层内部换顺序，最坏情况退化为默认行为。

**收益**仍取决于 offload 命中率，而这一点只能由 §3 的日志回答。如果日志显示 `audio offloadSinkSleeping=true` 且从不出现 `audio decoder=`，那么 D1 的价值就只覆盖少数兜底曲目 —— 此时不必回退，它已经在没有代价的位置待着了。

副产品：§3.1 证明了 `onOffloadedPlayback` 在 media3 1.11.1 上是死方法。以后想观测 offload 只能走 `onSleepingForOffloadChanged` 或 `dumpsys media.audio_flinger`，不要在 `onOffloadedPlayback` 里写任何有副作用的逻辑 —— 它在 release 包里根本不存在。
