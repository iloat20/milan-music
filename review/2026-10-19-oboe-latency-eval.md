# Oboe / AAudio 低延迟原生音频接口评估

| 项 | 值 |
|---|---|
| 评估对象 | Oboe（AAudio / OpenSL ES 双后端）、MMAP 独占模式、实时优先级回调 |
| 本项目 | milan-music 1.0.47 · Media3 1.11.1 · 已启用 offload · 本地文件播放 |
| 性质 | 只读评估，未改任何代码 |
| 结论 | **描述基本准确，但对本项目是零收益最高代价的一项**：本地播放没有"输入"，低延迟没有作用对象；且需自写 AudioSink 从而丢掉 offload |

---

## 1. 核实表

| 你的描述 | 核实结果 | 依据 |
|---|---|---|
| Oboe 是 Google 官方 C++ 音频库 | ✅ 成立 | `github.com/google/oboe`，官方文档在 `developer.android.com/games/sdk/oboe/` |
| **在 API 27+ 自动选用 AAudio** | ✅ 成立，且比"自动"更细 | 官方原文：「Oboe API is a C++ wrapper that calls AAudio on **Android 8.1 (API level 27) or higher**. On earlier Android versions, Oboe uses OpenSL ES」 |
| 旧版本回退 OpenSL ES | ✅ 成立 | 同上 |
| AAudio 的 MMAP 模式可做到极低延迟 | ✅ 成立，但**不保证** | 需 `setSharingMode(Exclusive)`；官方原文「Your app **may not** get exclusive access」 |
| 运行在实时优先级线程上 | ✅ 成立 | 回调跑在 `SCHED_FIFO` 线程上 |
| **"可以在应用自己的实时线程上完成解码—DSP—输出"** | ❌ **不成立，且官方明确禁止** | 见 §2，这是本次评估最重要的一条 |
| 避免系统调度抖动导致的欠载 | ⚠️ 部分成立 | RT 线程减少抖动，但保障来自 buffer 深度与线程模型，不是靠把活都塞进回调 |

**补充一个你可能没注意的细节**：AAudio 本身在 API 26 就已存在，但 Oboe 在 API 26 上**故意不用它**——那一版的 AAudio 有 RefBase 相关的崩溃缺陷，Oboe 在此版本强制回退 OpenSL ES。所以"27"这个门槛不是猜的，是 Oboe 主动划的。

---

## 2. 决定性的一条：实时线程里不能解码

官方低延迟文档有一节专门列禁止事项：

> Avoid doing the following in a callback: **Allocating or freeing memory**, **File or network I/O**, **Waiting on a mutex or lock**, Sleep, **Heavy one-time CPU calculations**

道理很直接：AAudio 的 MMAP 回调间隔只有几毫秒，回调里任何一次阻塞都会立刻欠载——听感上就是爆音。而 AAC/Opus 解码**恰好全是这些操作**：分配输出缓冲、读文件、等 codec 队列的锁、每帧都要做完整变换。

进程内解码器（上一轮引入的 `c2.android.inproc.*`）省掉的是 **Binder 往返**，并没有改变"解码在 codec 线程、输出在 audio 线程"这个结构。把解码搬进实时回调不会更快，只会更不稳定。

正确的结构从来是：

```
解码线程（普通优先级） → ring buffer（wait-free 读写） → audio 回调（只做 DSP + memcpy） → 输出
```

用户说的"在实时线程上完成解码—DSP—输出"，只有 DSP 和输出属于那里，解码必须留在外面。

---

## 3. 第二个陷阱：采样率

官方文档第 4 条：

> Use the natural sample rate of the device. You can do this by **not specifying a sample rate**, and you almost certainly get 48000 Hz. **If you do specify a sample rate, the audio framework sends your data on a different path that can have much higher latency.**

这条对音乐播放器是致命的，因为本地音乐的主流采样率是 **44.1 kHz**。二选一：

- 指定 44100 → 走另一条路径，低延迟收益没了，Oboe 白换
- 不指定 → 设备给 48000，44.1k 的曲目必须重采样 → **引入 SRC**

而上一轮评估的 bit-perfect 的全部目的就是消除 SRC。**Oboe 的低延迟和 bit-perfect 在采样率这一层直接互斥**，不可能同时满足。

---

## 4. 第三点：这类接口解决的是"输入到输出"，播放器没有输入

Oboe / AAudio 的低延迟指标衡量的是 **round-trip**：摸一下屏幕/弹一下琴键，到耳朵听到声音，中间隔多久。这个数字对游戏、乐器、K 歌监听是生死线（超过 20 ms 就能感觉到脱节）。

播放器没有输入端的实时性要求。它唯一的延迟指标是"按下播放键到出声"，那是**起播缓冲深度**的问题：

- 想让它更快，调小 `DefaultAudioSink` 的 buffer 就行，几行代码
- 想让它更稳，就调大
- 而播放过程中的 20 ms 还是 40 ms，用户完全无法分辨——音频是连续的，没有参照物

这不是"AudioTrack 够用"，而是**低延迟这个指标对本地播放不构成需求**。这一点你自己在描述里也写了：「AudioTrack 的延迟在本地播放场景中往往不是问题」。既然如此，替换音频接口的理由就只剩 DSP 链路了。

---

## 5. 而 DSP 链路并不需要低延迟

如果未来要做 AutoEQ / 参量 PEQ，正确的做法是 Media3 的 `AudioProcessor`：

```kotlin
DefaultAudioSink.Builder()
    .setAudioProcessors(arrayOf(ParametricEqProcessor(...)))
```

`androidx.media3.common.audio.AudioProcessor`（`configure` / `isActive` / `queueInput` / `getOutput`）就是为这个设计的，`BaseAudioProcessor` 提供了缓冲管理。EQ 是**静态处理**：用户拖动滑杆后，晚 200 ms 听到变化完全可以接受。低延迟在这里没有需求。

真正需要 Oboe 的是"处理延迟必须小于人耳可感知阈值"的场景，而 EQ 的延迟恒定存在且与 buffer 大小无关（它是逐样本的滤波器，不是 look-ahead 压缩器）。

---

## 6. 换成 Oboe 的实际代价

| 项 | 说明 |
|---|---|
| 自写 AudioSink | `DefaultRenderersFactory.buildAudioSink()` 是 protected，可以 override，但 `AudioSink` 接口要完整实现 `configure / play / pause / flush / reset / setVolume / setPlaybackParameters / handleDiscontinuity / getCurrentPositionUs / setAudioAttributes / …`——这是 ExoPlayer 里最难写对的接口之一 |
| **丢掉 offload** | `AudioSink` 一换，compressed offload 那条路就没了。这是 1.0.46 的最大单项功耗收益，也是第三次撞同一堵墙（前两次：实时 DSP 修复、bit-perfect 的 float 冲突） |
| 丢掉 float 输出 | 要自己处理 int16/float 转换 |
| 丢掉 AudioTrack 的设备 workaround | Media3 在 `DefaultAudioSink` 里积累了大量的 per-device 补丁（buffer size、discontinuity、格式协商），换实现等于从零开始 |
| NDK + CMake + 原生构建 | 项目目前是纯 Kotlin/Compose 零原生代码，引入 NDK 会让构建时间、APK 体积、ABI 拆分、崩溃排查全面复杂化 |
| 与进程内解码器无关 | D1 的收益是免 IPC，与输出去哪儿无关，不构成换 Oboe 的理由 |

---

## 7. 结论

四项技术描述里有三项准确（API 27 门槛、OpenSL ES 回退、MMAP 不保证、实时线程），一项错误（实时线程里解码），还有两个描述里没提但更关键的约束（44.1 kHz 与低延迟路径互斥、播放器没有输入端）。

**这一项不适合做，理由是需求不存在而非技术不可行**：

1. 本地播放没有 round-trip 延迟需求
2. 实时 DSP 已在上一轮评估中否决
3. 即使将来做 PEQ，`AudioProcessor` 的延迟完全够用
4. 唯一确定的代价是失去 offload，而收益无法量化

如果哪天产品方向变成"实时监听 / 乐器播放 / DSP 工作台"（有输入端、有 <10 ms 要求），Oboe 才是正确答案。以现在的定位，它是把 1.0.46/1.0.47 攒下来的功耗优化还回去，换一个没人需要的指标。

---

## 8. 这四项串起来看

| 项 | 需求来源 | 与 offload 的关系 | 建议 |
|---|---|---|---|
| D1 进程内解码器 | 已有（兜底路径） | 不冲突 | ✅ 已引入 |
| USB DAC bit-perfect | 少数用户 | 不冲突（USB 本来不走 offload） | 先做探测 |
| 有损修复 DSP | 主观听感 | **冲突** | 只做检测 |
| Oboe 低延迟 | 不存在 | **冲突** | 不做 |

四项里只有前两项是"在不损失已有优化的前提下加东西"，后两项的共同点是**都要拆掉 offload**。这个规律值得记下来：凡是需要在 PCM 层动手的方案，都会和 offload 打架；能绕过这个矛盾的，只有完全不碰 PCM 的方案。
