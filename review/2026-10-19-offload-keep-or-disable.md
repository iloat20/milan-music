# offload 的收益、门槛与「该不该关」

**日期**：2026-10-19
**范围**：只读分析，未改动任何代码
**结论**：不关。但当前配置在 Android 10–12 上等于已经关了——真正要决策的不是 offload 开关，而是 gapless 要求。
**决策反转（2026-10-19 晚）**：已关闭。真机命中率为 0，本文 §一/§八 的前提不再成立，见 §九。

---

## 一、结论先行

| 问题 | 结论 |
|---|---|
| offload 值得留吗 | 值得。它是本地播放器唯一能拿到的「解码搬去 DSP」收益，且不花钱 |
| 现在该关掉吗 | 不该。关掉退不回任何功能，只丢功耗 |
| 有什么隐患 | 少数 DSP 实现会截尾/吞头；seek 与位置上报有轻微代价 |
| 真正该看的旋钮 | `setIsGaplessSupportRequired(true)`（`PlaybackService.kt:91`）——它在 Android 10–12 上把 offload 的命中率压成 0 |
| 需要做什么 | 先测命中率，再决定是否放宽 gapless；不建议全局关闭 offload |

---

## 二、offload 到底省什么

四条收益，按确定性排序：

**1. 解码从 CPU 搬走。** 压缩帧直接交给 DSP，CPU 只负责把帧写进缓冲，不再有
`MediaCodec` 解码线程按帧唤醒。这是「最大单项功耗收益」的实际来源。

**2. 不经过系统 mixer。** offload 轨在 AudioFlinger 里不进 mixer，也就不参与系统混音器的重采样
（前提是设备 DSP 接受该采样率）。方向与 bit-perfect 一致，但不等同——SRC 可能发生在 DSP 内部。

**3. sink 会主动休眠。** 缓冲喂满后音频输出进入 sleeping 状态
（`onSleepingForOffloadChanged(true)`），渲染循环停摆，CPU 的自然唤醒消失。这也是本项目唯一的
offload 观测点。

**4. 天花板：不省整机待机。** `PlaybackService.kt:71` 设了 `WAKE_MODE_LOCAL`，持有 partial
wake lock，CPU 不能进 suspend。所以 offload 省的是**解码工作量**，不是「CPU 整机睡下去」。
要拿满这部分收益需要放开 wakelock，但那会让部分 OEM 在息屏后卡住解码线程，得不偿失。

---

## 三、生效门槛（media3 1.11.1 字节码取证）

`DefaultAudioOffloadSupportProvider.getAudioOffloadSupport()` 是唯一入口，逐条门槛：

| # | 门槛 | 依据 | 本项目实际 |
|---|---|---|---|
| 1 | `SDK_INT >= 29` | 偏移 13 `bipush 29`，不满足直接 `DEFAULT_UNSUPPORTED` | `minSdk = 26`，Android 8/9 用户永远不命中（自动回落，无害） |
| 2 | `Format.sampleRate != -1` | 偏移 22 | 本地文件都带采样率，恒过 |
| 3 | mime 能映射到平台 encoding，且系统版本 ≥ 该 encoding 引入版本 | `MimeTypes.getEncoding` + `Util.getApiLevelThatAudioFormatIntroducedAudioEncoding` | AAC-LC / HE-AAC / MP3 / FLAC / Opus 可；**WAV、ALAC 无平台 encoding，永不命中** |
| 4 | 声道配置可映射（`getAudioTrackChannelConfig != 0`） | 偏移 86 | 立体声恒过 |
| 5 | 平台侧判定 | API 33+ `getDirectPlaybackSupport`；31/32 `getPlaybackOffloadSupport`；29/30 `isOffloadedPlaybackSupported` | 厂商配置决定 |
| 6 | 播放器偏好三道闸 | `DefaultTrackSelector.rendererSupportsOffload` | mode=ENABLED 通过；speed 未要求；**gapless 要求这一条会导致不通过** |

平台常量（android-37 jar）：`DIRECT_PLAYBACK_OFFLOAD_SUPPORTED = 1`、
`DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED = 3`、`DIRECT_PLAYBACK_BITSTREAM_SUPPORTED = 4`。

---

## 四、决定性发现：gapless 要求在 Android 10–12 上关死了 offload

`rendererSupportsOffload` 的判定是**合取**，任一条不满足就整条不选 offload：

```
audioOffloadMode != DISABLED
&& (!isSpeedChangeSupportRequired || caps 支持变速)
&& (!isGaplessSupportRequired      || caps 支持无缝)
```

而 `caps` 里的 gapless 位在旧系统上根本不可能为真：

| 系统 | helper | gapless 位 |
|---|---|---|
| API 29–30 | `getOffloadedPlaybackSupportV29` | **从不赋值，默认 false** |
| API 31–32 | `getOffloadedPlaybackSupportV31` | `SDK_INT > 32 && support == 2` 才 true → **在 31/32 上恒 false** |
| API 33+ | `getOffloadedPlaybackSupportV33` | `(support & 3) == 3` 才 true，即设备必须报 `GAPLESS_SUPPORTED` |

结论：`PlaybackService.kt:91` 的 `setIsGaplessSupportRequired(true)`

- Android 10/11/12（API 29–32）→ **offload 命中率恒为 0**，全部走 PCM 兜底；
- Android 13+（API 33+）→ 仅当设备 DSP 明确回报「offload + gapless」才命中。

这不是 bug，是 media3 的设计：它宁可整体回落到软件路径，也不用一个不保证 gapless 的硬件通道。
对我们来说，代价是「最老的三个大版本上功耗收益为零」，收益是这些设备上专辑连播不会出现缝隙。

---

## 五、代价清单（保留 offload 需要接受什么）

| 代价 | 具体表现 | 严重度 |
|---|---|---|
| 结尾截断 / 开头吞音 | 个别 DSP 实现按自己解析的时长收尾，与文件真实时长有偏差 | 中——只在 offload 路径复现，最难定位 |
| seek 响应 | 拖动需 flush DSP 缓冲再重新提交，部分机型有短暂静音 | 低—中 |
| 位置上报 | 位置 = AudioTrack 播放头 + offload delay/padding，进度条可能滞后一个 DSP 缓冲 | 低 |
| 音量 | 由平台在 DSP 端施加，个别机型档位偏粗 | 低 |
| 功能天花板 | 任何需要 PCM 的处理（EQ、交叉淡化、响度归一化、不支持的变速）都必须退出 offload | 高——但它只是「以后要加功能时再切」，不是现在的问题 |
| 路径混杂 | 同库内 FLAC/MP3/AAC 可能 offload，WAV/ALAC 不会；问题难复现 | 中 |
| 观测受限 | offload 生效时 `MediaCodecSelector` 不被调用，D1 的进程内解码器在这些曲目上等于不存在 | 已知，无实际损失 |

---

## 六、判断表：什么时候才该关

| 触发条件 | 动作 |
|---|---|
| 现状（纯本地、无 EQ、无变速、息屏长播为主） | **不关**，保持 `AUDIO_OFFLOAD_MODE_ENABLED` |
| 以后加 EQ / 交叉淡化 / 响度归一化 | 功能开启期间切 `AUDIO_OFFLOAD_MODE_DISABLED`，默认仍开；不要为了新功能长期全局关 |
| 收到「某首歌结尾被切/开头丢」且只在 offload 路径复现 | 按机型或编码灰度关闭，不做全局关 |
| Android 10–12 用户占比高、且功耗是核心卖点 | 放宽 gapless 要求（见下），不是关 offload |
| 调试「这台设备到底支不支持」 | 临时用 `AUDIO_OFFLOAD_MODE_REQUIRED` 逼它必须命中，不支持就会明显异常——只做临时手段 |

---

## 七、建议动作

1. **不改 offload 开关。** 保持现在的 `ENABLED`（有则用、无则回落）。
2. **先测命中率**，一条 logcat 就够：
   ```
   adb logcat -s PlaybackService | grep -E "offload|decoder"
   ```
   | 输出 | 含义 |
   |---|---|
   | `audio offloadSinkSleeping=true` | offload 生效，CPU 不再解码 |
   | 只有 `audio decoder=...` | 回落 PCM，D1 的排序在此起作用 |
   | 两者都没有 | 曲目太短，缓冲未喂满即结束，换长曲重测 |
3. **在 Android 13+ 与 Android 12 各测一台**，用来验证第四节那张表——如果 Android 12 上确实从不出现 sleeping，说明收益为零，那么 `setIsGaplessSupportRequired(true)` 就是一个可以讨论的旋钮。
4. **gapless 是否放宽，属产品决策，不擅自改。** 改成 `false` 能提高旧系统命中率，代价是连播专辑可能出现无法预测的缝隙；对「整张专辑顺序听」的用户是倒退。

---

## 八、一句话回答

offload 省的是「解码那部分功耗」，门槛卡在系统版本与厂商 DSP 回报上；关掉它换不到任何功能，
所以不该关。真正值得看的是 gapless 要求把 Android 10–12 的命中率压成了零——这件事要不要动，取决于
「旧机型用户多不多」和「连播缝隙能不能接受」，两个都得先有真机数据。

---

## 九、决策反转：2026-10-19 关闭 offload

§一 与 §八 的「不关」建立在两个未验证前提上：① 存在会命中的设备群；② 命中的解码功耗节省在本项目里是净收益。真机数据推翻①，工程约束推翻②的可兑现性。

### 9.1 前提①被推翻：命中率为 0

OPPO PLJ110（Android 17）实测，四种格式全部返回 `4`：

```
PROBE direct=mp3/44100  support=4
PROBE direct=aac/44100  support=4
```

`4 = DIRECT_PLAYBACK_BITSTREAM_SUPPORTED`，缺 `OFFLOAD_SUPPORTED(1)` 位。media3 1.11.1 的判定是 `(support & 1) == 0 → DEFAULT_UNSUPPORTED`，因此 `getAudioOffloadSupport()` 恒为 0，`rendererSupportsOffload` 首步即失败。**配置写着 ENABLED，行为等于 DISABLED**，连日志都只有 `audio decoder=...` 一行。

### 9.2 前提②在本项目不可兑现

- `WAKE_MODE_LOCAL` 持有 partial wakelock，CPU 不能 suspend，offload 省的只是「解码工作量」，不是「睡得更深」。
- D1 生效后解码已从「mediaswcodec + 每帧两次 Binder」降到进程内（`initMs=17`，对比 MP3 软解 76 ms），解码开销的绝对值已经很小。
- 剩下唯一还值得追的功耗收益，抵不过下面三类已知缺陷的排查成本。

### 9.3 已落地改动

| 位置 | 改动 |
|---|---|
| `PlaybackService.kt` | 删除 `setAudioOffloadPreferences(ENABLED + setIsGaplessSupportRequired(true))` 整块，回到 media3 默认（`AUDIO_OFFLOAD_MODE_DISABLED`，Builder 字段初始值 `iconst_0`，字节码核实） |
| `PlaybackService.kt` | 删除 `addAudioOffloadListener`（offload 不再发生，回调永不触发）及相关注释 |
| `PlaybackService.kt` | 注释改写：说明为何关闭、关闭后 `InProcessCodecSelector` 成为常驻路径、重新启用需要同时恢复 gapless 硬要求 |
| `InProcessCodecSelector.kt` | KDoc 更新：选择器不再是兜底路径，每次播放都会被咨询 |
| `README.md` | 技术栈与文档索引同步 |

gapless 不受影响：PCM 路径由 `AudioSink` 按码流的 `encoderDelay` / `encoderPadding` 裁剪，与 offload 的 gapless 是两套机制。

### 9.4 代价（明确记账，不粉饰）

在**真正上报 `OFFLOAD_SUPPORTED` 的机型**上，本应用从此放弃「解码搬去 DSP」的功耗收益，改为纯 CPU 软解。这是有意的取舍：用确定的功耗上限损失，换掉 offload 路径的三类缺陷——

1. 个别 DSP 按自解析时长收尾，出现结尾截断或开头吞音，且只在 offload 路径复现；
2. 位置上报 = 播放头 + DSP 缓冲，进度条可能滞后一个缓冲；
3. offload 绕过 ExoPlayer 后处理，任何 EQ / 交叉淡化 / 响度增益都必须先退出该模式。

以及一个工程收益：**链路只剩一条**，`audio decoder=` 一行日志即可确定当前实际路径，问题定位不再二分。

### 9.5 回退方式

恢复两行即可：`setAudioOffloadMode(AUDIO_OFFLOAD_MODE_ENABLED)` + `setIsGaplessSupportRequired(true)`。回退前需要先有证据表明目标机型群的 bit0 命中率显著——判据是一次 `getDirectPlaybackSupport` 探针，不是系统版本号。
