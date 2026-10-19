# 进程内解码的间接收益与 Android 17 后台音频加固（补充评估）

| 项 | 值 |
|---|---|
| 评估对象 | ① 「进程内解码 → DSP → 输出整合进同一条低抖动管道」的架构推论 ② Android 17 后台音频加固对播放器生命周期的影响 ③ CDD 编解码器初始化延迟 ≤ 30 ms |
| 本项目 | milan-music 1.0.47 + 未发版 D1（`InProcessCodecSelector`）· Media3 1.11.1 · minSdk 26 · targetSdk 37 |
| 取证环境 | `media3-exoplayer-1.11.1-runtime.jar` 字节码（Gradle 缓存）· `AndroidManifest.xml` · 本地源码 · Android 17 官方行为变更文档 · Android 17 CDD / MPC 补充说明 / CTS `PerformanceClassEvaluator` · OPPO PLJ110 真机日志（`2026-10-19-device-verification.md`） |
| 性质 | 只读评估，未改任何播放代码 |
| 结论 | **方向对，机制错。** 第 1 条描述的收益在本机是真实的（offload 不命中，inproc 就是常驻路径），但"运行在 AAudio/Oboe 实时线程上""彻底消除欠载"两处与 media3 的实际实现不符；第 2 条事实成立，**但本项目早已满足**，不是待办项；第 3 条的 30 ms 在版本归属和要求对象上双重错位。 |

---

## 1. 结论表

### 1.1 进程内解码的间接收益

| 原文描述 | 核实结果 | 证据 |
|---|---|---|
| 解码可运行在应用自己的实时优先级线程（如 AAudio/Oboe 线程）上 | ❌ 不成立，两处错 | media3 1.11.1 的音频包 103 个类中 `AAudio` / `Oboe` 出现 **0 次**；输出实现是 `AudioTrackProvider` / `AudioTrackAudioOutputProvider`；本项目源码 `AAudio`/`Oboe` 亦为 0 处 |
| 解码与输出可整合进"同一条管道" | ⚠️ 只能说到"同一进程"，说不到"同一条线程" | 解码在 Codec2 组件自己的线程上执行，PCM 由 renderer 线程写 `AudioTrack`；即使自研 Oboe 输出，AAudio 回调线程也不允许承担解码 |
| 消除跨进程调度争用与每帧两次 Binder | ✅ 成立 | 官方「Out-of-process versus in-process decoding」原文明确列举 IPC 开销与 buffer starvation |
| 彻底消除缓冲区欠载和卡顿风险 | ⚠️ 过度承诺，应改为"降低概率与方差" | 剩余边界：`AudioTrack` → AudioFlinger → HAL 仍在应用之外；应用进程自身的 GC / 线程竞争 / cpuset 限制仍在 |
| 负载高时（后台同步、曲库扫描并发）尤为重要 | ✅ 方向成立，且本机有对应场景 | 冷启动即全量扫 1233 行（`queue size=1233`），扫描与首播并发 |

### 1.2 后台音频加固与 CDD 延迟

| 原文描述 | 核实结果 | 证据 |
|---|---|---|
| Android 17 对后台音频交互实施更严格限制 | ✅ 成立 | 官方「后台音频强化」，涉及播放、音频焦点、音量 API |
| 必须有可见 activity 或前台服务，且不能是 `SHORT_SERVICE` | ✅ 成立（措辞需修：是"可见的 activity **或** 非 SHORT_SERVICE 的前台服务"，不是"可见的前台服务"） | 同上；且**不区分 targetSdk**，跑在 17 上的应用一律适用 |
| 无效生命周期下播放与音量 API 静默失败，焦点返回 `AUDIOFOCUS_REQUEST_FAILED` | ✅ 原文一致 | 同上 |
| 因此"迫使"本项目改造后台播放架构 | ❌ 不成立，本项目已满足 | `AndroidManifest.xml:59` `foregroundServiceType="mediaPlayback"`；`:13-14` 两个 FGS 权限；`PlaybackService : MediaSessionService`（官方推荐做法，明确写"not likely to be impacted"） |
| **遗漏项**：targetSdk 37 的附加约束 | ⚠️ 原文未提 | 后台启动时必须运行**具备 WIU 能力**的 FGS（响应用户操作或应用可见时启动才获得） |
| 编解码器初始化延迟"部分等级下需 ≤ 30 ms" | ⚠️ 数字对，归属错 | 属 **音频编码器** [5.1/H-1-8]，非解码器；MPC ≥ 33 起；该 30 ms 档自 Android 13（API 33）已存在，**不是 Android 17 收紧** |
| 由此推出"系统级解码启动速度在变快，对切歌响应有正向影响" | ❌ 推论不成立 | MPC 由设备自行声明，未声明 MPC 的机型不受该表约束；且要求对象是编码器侧 |

---

## 2. 第 1 条：方向正确，但机制描述有三处偏差

### 2.1 取证：media3 不存在 AAudio/Oboe 通路

对 Gradle 缓存里的 `media3-exoplayer-1.11.1-runtime.jar` 做字节码检索：

```
androidx/media3/exoplayer/audio/            103 个 class
grep -rl "AAudio\|Oboe"                     → 0
整个 jar 中类名匹配 aaudio|oboe              → 0
DefaultAudioSink 引用                       → AudioTrack / AudioTrackProvider
                                              / AudioTrackAudioOutputProvider
                                              / AudioTrackConfig / OffloadMode
```

media3 的音频输出**只有 `AudioTrack` 一条路**（Java 层 `android.media.AudioTrack`，即 `AudioTrackProvider`）。media3 1.11 新增的是 `AudioOutputProvider`，可替换的仍是 `AudioTrack` 的参数形态（buffer size、offload、performance mode），不是 AAudio 后端。

本项目侧同样为零：`app/src/main/java` 中 `AAudio` / `Oboe` 无任何引用，`PlaybackService.kt:99` 的注释也只是提到 `AudioTrack`。

**因此"整合进同一条 AAudio/Oboe 管道"这件事在当前技术栈里没有落点**——要用 AAudio 就得自研 `AudioSink`（且 media3 的 `AudioOutputProvider` 并未为此提供完成品），这是换内核量级的改动，与"进程内解码"无关。

### 2.2 即便自研 Oboe 输出，解码仍不会跑在实时回调线程上

AAudio 的 data callback 只允许做"从环形缓冲区拷 PCM"这一件事。把解码放进回调线程，等于把可变长度的解码抖动直接压到音频时钟上，是实时音频编程里的典型反模式。任何自研的 Oboe 管线也是：

```
解码线程（可变耗时） → 环形缓冲 → AAudio 回调线程（固定预算、拷贝）
```

进程内解码优化的是**第一段与第二段之间的延迟与调度域**，不是把它们合成一段。

### 2.3 "彻底消除"应改为"降低方差"

剩下的边界是结构性的，不是实现瑕疵：

1. `AudioTrack` 写入后仍要跨进 AudioFlinger 混音，再进 HAL；这两段从来不在应用进程内。
2. 应用进程自身会被 GC、其他线程、cpuset/ADPF 限制影响——进程内解码只是把**跨进程**的争用变成了**进程内**的争用。
3. 官方措辞是"降低 IPC 延迟与重载下的 buffer starvation"，没有承诺消除。

### 2.4 真实的收益落在哪里：本机 offload 不命中 → inproc 是常驻路径

这一点必须写清楚，否则会得出"改了也没用"的错误结论。`2026-10-19-inprocess-codec-eval.md` §4 曾据"offload 已启用"推断解码器不在链路上；`device-verification.md` §4 已在真机推翻该推断：

```
PROBE direct=aac/44100  support=4      # 4 = DIRECT_PLAYBACK_BITSTREAM_SUPPORTED
media3: (support & 1) == 0 → DEFAULT_UNSUPPORTED
```

OFFLOAD_SUPPORTED 位缺失 → offload 永不命中 → **MediaCodec 软解是本机唯一实际路径**。真机日志同时证明 D1 生效：

```
I PlaybackService: audio decoder=c2.android.inproc.aac.decoder initMs=17
```

对比同机 MP3 软解 76 ms、FLAC 33–41 ms，初始化开销低一个档位。所以：

> 在这台设备上，"进程内解码"不是兜底路径的边角优化，而是**主路径**。它带来的抖动与延迟收益是实打实的，只是实现机制是"去掉一次进程跳转"，不是"接入 AAudio 实时管道"。

### 2.5 反向代价（未实测，需挂表）

进程内解码把解码 CPU 从系统 `mediaswcodec` 进程搬进应用进程，可能带来两个方向的副作用：

| 副作用 | 机理 | 验证手段 |
|---|---|---|
| 后台播放时被应用级限制牵连 | 系统编解码进程不受应用后台 cpuset/节流约束，应用进程受；本应用绝大多数时间是后台播放 | 息屏播放 30 min，对比 D1 开/关的 `underrun` 计数与 `dumpsys batterystats` 中该 uid 的 CPU 时间 |
| 功耗归因漂移 | 解码耗电从系统进程转入应用 uid，batterystats 里"应用耗电"必然上升，与原系统进程耗电下降不一定抵消 | 同上，需看整机 mAh 而非单 uid |

在拿到这两组数据前，不应把"进程内解码降低功耗"写成结论——**它确定降低的是延迟与抖动，功耗是净收益还是净损失尚未测量**。

---

## 3. 第 2 条：事实成立，但本项目已满足，不是待办

### 3.1 官方要求是两档，原文常被压成一档

| 档位 | 适用条件 | 要求 |
|---|---|---|
| 基础档 | 所有运行在 Android 17 上的应用，**不看 targetSdk** | 有可见 activity，**或**运行非 `SHORT_SERVICE` 类型的前台服务 |
| 附加档 | 应用 `targetSdk = 37` | 后台状态下必须是**具备 WIU（while-in-use）能力**的 FGS：在响应用户操作时启动，或在应用可见时启动 |
| 豁免 | 已获精确闹钟权限且操作 `USAGE_ALARM` 流 | 免除 WIU 要求 |

失败语义：播放与音量 API **静默失败**（不抛异常、无失败消息）；音频焦点返回 `AUDIOFOCUS_REQUEST_FAILED`。这正是最难排查的一类问题——没有堆栈，只有"没声音"。

### 3.2 本项目逐条对账

| 官方要求 | 本项目现状 | 判定 |
|---|---|---|
| 承载播放的是非 SHORT_SERVICE 的 FGS | `MediaSessionService` + `foregroundServiceType="mediaPlayback"` | ✅ |
| 声明 `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | `AndroidManifest.xml:13-14` | ✅ |
| 推荐用 media3 `MediaSessionService` 管理生命周期 | `PlaybackService : MediaSessionService`（`PlaybackService.kt:23`） | ✅ 官方原文：这样做"not likely to be impacted" |
| FGS 在应用可见/用户操作时启动（WIU 前提） | 播放由 UI 或媒体通知/媒体键触发，服务由 `MediaSessionService` 拉起 | ✅ 结构上满足，未逐场景实测 |
| 无可见界面时也不调用音频 API | 所有音频操作都经 `player`，不在 Activity 里直调 `AudioManager.setStreamVolume` | ✅ 静态核对通过 |

结论：**第 2 条描述的风险在本项目不存在，它描述的是"没有前台服务的播放器"会踩的坑。**这段文字如果要写进文档，应改写为"本项目已按此模型实现"，而不是"需要改造"。

### 3.3 仍然值得核对的三个生命周期细节

官方"最佳实践"里有三条比"有没有 FGS"更容易做错：

1. **瞬时中断下保持 FGS 存活**：缓冲、`AUDIOFOCUS_LOSS_TRANSIENT` 这类 10 分钟以内的瞬时故障，FGS 不应停止，否则恢复播放要重新走启动路径。
2. **播放结束即停 FGS 并结束会话**：暂停后无限期挂着 FGS 会引出"用户无法结束播放"的观感，也是本次加固要治的病根之一。
3. **`onTaskRemoved` 的判断**（`PlaybackService.kt:173-178`）：当前逻辑是"未在播放或队列为空才 `stopSelf()`"，符合第 2 条精神；但"暂停态被划掉任务卡"这一路径未实测。

以上三条都属于行为验证项，需要真机（`dumpsys activity services` 看 FGS 状态 + `dumpsys media_session` 看会话存活）。

---

## 4. 第 3 条：30 ms 的归属错位

### 4.1 数字本身是对的，对象和版本都不对

Android 17 CDD 的 MPC 补充说明 [5.1/H-1-8]「**音频编码器**初始化延迟」：

| MPC 值 | 10 | 20 | 30 | 31 | 33 | 34 | 35 | 37 |
|---|---|---|---|---|---|---|---|---|
| 上限 (ms) | ≤55 | ≤50 | ≤50 | ≤40 | **≤30** | **≤30** | **≤30** | **≤30** |

对应 CTS 要求常量（`PerformanceClassEvaluator.createR5_1__H_1_8`）标注：`R=50 / S=40 / TIRAMISU=30`——即 **30 ms 这一档自 Android 13 起就存在**，Android 17 没有收紧，只是把 37 一并列入表内。

三点必须同时成立才算"系统级解码启动变快"，而三点都不成立：

1. 要求对象是**编码器**（录音侧），不是播放侧的解码器。解码器侧的 30 ms 只出现在 CTS `R5_1__H_1_13`，并未进入 Android 17 CDD 的 MPC 表。
2. 该表只约束**声明了 MPC 值**的设备；未声明 MPC 的机型不受约束，无法据此推断"系统整体变快"。
3. 测量条件是"1080p→720p 视频转码 + 1080p 录制初始化"的负载场景，与本地播放器切歌不是同一种负载。

### 4.2 但"切歌响应受解码初始化影响"这个判断本身是对的

不需要 CDD 支撑就能证明：本项目自己的日志已经把初始化开销量出来了——`initMs=17`（inproc AAC）对 76 ms（MP3 软解）。**切歌延迟里可控的那一段就是这几十毫秒**，压低它的手段是选更轻的解码器（D1 已做）与 120 ms 的首播水位（A 已做），不是指望 CDD 让 OEM 变快。

---

## 5. 对既有文档结论的更正

| 文档 | 原结论 | 更正 |
|---|---|---|
| `2026-10-19-inprocess-codec-eval.md` §4 | "offload 已启用，命中时链路上没有解码器，因此 D1 收益几乎为零" | 推断前提在本机不成立：`support=4` 缺 `OFFLOAD_SUPPORTED` 位，offload 永不命中。D1 在本机是**主路径**且实测生效（`initMs=17`）。收益上限重估为"整条软解路径" |
| `2026-10-19-offload-keep-or-disable.md` §四 | 把 offload 零命中归因于 `setIsGaplessSupportRequired(true)` | 已由 `device-verification.md` §4 更正：bit0 缺失的设备放宽 gapless 同样不命中 |
| 本文第 1 条 | —— | "整合进 AAudio/Oboe 管道"在本技术栈无落点；正确表述见 §2.2 |

---

## 6. 如果这段文字要进对外文档

| 可以写 | 不要写 |
|---|---|
| 进程内解码去掉了 `mediaswcodec` 进程跳转与每帧两次 Binder 往返，降低了解码延迟与重载下的抖动 | 解码运行在 AAudio/Oboe 实时线程上 |
| 在本机（offload 不命中）场景下，该路径是播放主路径，实测解码器初始化 17 ms | 彻底消除缓冲区欠载与卡顿 |
| Android 17 要求后台音频交互必须有可见 activity 或非 SHORT_SERVICE 的前台服务；本项目用 `MediaSessionService` 承载，属官方推荐模型 | 该限制"迫使本项目改造后台播放架构" |
| 解码器初始化开销直接影响切歌响应，选轻量解码器是可控手段 | CDD 已将解码器初始化收紧到 30 ms，系统级解码变快 |

一句话版本：**收益是真的，机制不是文中说的那个；加固是真的，但本项目已经站在正确的一侧。**

---

## 7. 未验证清单

1. **D1 开/关的欠载计数对比**：需要 Perfetto 或 `dumpsys media.audio_flinger` 的 underrun 计数，在后台播放 + 并发扫描的负载下取 30 min 样本。当前只有"无欠载日志"这一弱证据。
2. **进程内解码的功耗方向**：`dumpsys batterystats` 需按 uid 对比整机 mAh，而不是看应用 uid 单项（解码耗电会从系统进程转入应用 uid）。
3. **后台 FGS 生命周期**：`AUDIOFOCUS_LOSS_TRANSIENT` 后的恢复、暂停态划掉任务卡、播完是否停 FGS——三项都要 `dumpsys activity services` 实测。
4. **`AAudio` 路径是否存在可行性**：media3 1.11.1 未提供 AAudio 后端，自研 `AudioSink` 的工程量与收益（本地文件播放的延迟预算本就宽裕）不成比例，暂不评估。
