# Android 17 传输与响度能力评估（LHDC v5 / CTA-2075 / xHE-AAC 与 IAMF）

| 项 | 值 |
|---|---|
| 评估对象 | ① LHDC v5 蓝牙编解码器「系统级激活」 ② CTA-2075 响度管理 API ③ xHE-AAC / IAMF 强制解码 |
| 本项目 | milan-music 1.0.47 · Media3 1.11.1 · minSdk 26 · targetSdk 37 · compileSdk 37 |
| 取证环境 | Android SDK `platforms/android-37.0`（`android.jar` / `android-stubs-src.jar` / `data/api-versions.xml`）+ Gradle 缓存中的 `media3-exoplayer-1.11.1.aar`、`media3-extractor-1.11.1.aar` 字节码 |
| 性质 | 只读评估，未改任何代码 |
| 结论 | **三项里只有"响度"是真实痛点，但 CTA-2075 解决不了它**：平台 API 早于 Android 17 两个版本存在，且只对带响度元数据的码流生效，本地库主流格式没有元数据。LHDC 在应用层无任何可查询接口，「标注支持」只能写死文案。xHE-AAC / IAMF 的版本归属整体写错，且"平台能解"不等于"ExoPlayer 能播"。 |

---

## 1. 结论表

| 原文描述 | 核实结果 | 证据 |
|---|---|---|
| Android 17 在系统层激活 LHDC v5 编解码器 | ⚠️ 平台侧不可验证；对应用而言等于**不存在** | API 37 的 `android.bluetooth.BluetoothCodecConfig` 中无任何 LHDC 常量；`SOURCE_CODEC_TYPE_*` 全系 `since ≤ 34` 且已 `deprecated="35"` |
| 播放器无需做任何适配，系统自动协商更高规格 | ✅ 成立，但因果关系要反过来 | 应用不参与 A2DP 编解码器协商，协商发生在蓝牙栈与耳机之间；"无需适配"意味着**应用也没有任何抓手** |
| 设置里可标注"支持 LHDC v5 直通"作为差异化卖点 | ❌ 不可作为事实性声明 | 无公开 API 能读到当前 A2DP 编解码器类型；`BluetoothCodecStatus` 在 API 35 后已不从应用层暴露 codecType。文案无法随设备变化，在未启用 LHDC 的机型上属虚假宣传 |
| LHDC v5 传输速率 1 Mbps、24-bit / 192 kHz | ⚠️ 厂商规格，非平台可验证项 | 192 kHz 是编解码器侧输入上限，A2DP 实际采样率仍由厂商固件与耳机能力决定，应用无法观测 |
| CTA-2075 响度管理 API 由 Android 17 Beta 1 引入 | ❌ 版本错 | `android.media.LoudnessCodecController` **`since="35"`**（Android 15），`create` / `addMediaCodec` / `getLoudnessCodecParams` / `removeMediaCodec` 全为 35 起 |
| ExoPlayer 检测到响度元数据时自动启用 | ✅ 成立，但机制与描述不同 | Media3 1.11.1 `MediaCodecAudioRenderer` 的 7 参构造在 `SDK_INT >= 35` 时**无条件** `new LoudnessCodecController()`；是否真正产生增益取决于 `addMediaCodec()` 的返回值（编解码器是否接受响度参数） |
| 能让本地曲库不同专辑的感知响度自动拉平 | ❌ 机制不匹配 | 控制器作用于 **MediaCodec 解码器**，增益来自码流内的响度元数据。FLAC / ALAC / MP3 / 无 DRC 元数据的 AAC-LC 里没有这种信息，注册后不产生任何增益 |
| 自研播放内核也可直接调用系统 API 实现同等效果 | ⚠️ 可行但门槛被低估 | 必须自己持有 `MediaCodec` 实例并注册进去（在 ExoPlayer 外面拿不到），且同样受"码流必须带响度元数据"约束 |
| Android 17 才把 xHE-AAC 纳入平台强制解码 | ❌ 版本错两代 | `AudioFormat.ENCODING_AAC_XHE` `since=28`；`MediaFormat.MIMETYPE_AUDIO_AAC_XHE` `since=33` |
| Android 17 才具备 IAMF 解码能力 | ❌ 版本错一代 | `MediaFormat.MIMETYPE_AUDIO_IAMF` `since=36`；`ENCODING_IAMF_*` 12 个常量全部 `since=36`（Android 16） |
| IAMF 可作为空间音频的长期储备 | ⚠️ 平台能解 ≠ 播放器能播 | `media3-extractor-1.11.1` 内无 `IamfExtractor`，仅 `mp4/BoxParser` 出现 `iamf` 字样；`media3-exoplayer` 侧有 `IamfUtil`，属渲染/布局支持，不是容器解析 |
| CDD 要求 PCM/WAVE 最高 24-bit / 192 kHz、8 声道 | ✅ 与既有能力一致，非 API 37 新增 | 与本项目 `setEnableAudioFloatOutput(true)` 不冲突，float 输出向上不设天花板 |

---

## 2. LHDC v5：为什么"无需适配"等于"无法作为卖点"

### 2.1 应用层没有任何观测面

三个可能的接口全部不可用：

1. **`BluetoothCodecConfig.SOURCE_CODEC_TYPE_*`**：枚举里只有 SBC / AAC / APTX / APTX_HD / LDAC / LC3 / OPUS，**没有 LHDC**，且这些常量在 API 35 统一标记废弃。厂商要接入 LHDC 只能走私有 codec id，名字永远不会出现在 `android.jar` 里。
2. **`BluetoothCodecStatus`**：随上面那批常量一起被降级，API 35 之后的公开形态不再向普通应用暴露"当前用的哪种编解码器"。
3. **`AudioDeviceInfo`**：只描述设备（`TYPE_BLUETOOTH_A2DP`、采样率列表、声道掩码），不含编解码器字段。

结论：**即使设备真的在用 LHDC v5，应用也无法确认，更无法在 UI 上如实显示。**"支持 LHDC v5 直通"这句文案写进设置页，等于在任何机型上都宣称一件应用看不见的事。

### 2.2 播放器侧唯一真实的杠杆：不要动采样率

蓝牙链路对应用唯一可控的输入是 **送进 AudioTrack 的 PCM 格式**。这里的现实约束是：

- ExoPlayer 默认按源采样率输出（不强制 48 kHz），当前 `setEnableAudioFloatOutput(true)`（`PlaybackService.kt:45`）只改位深，不改采样率——**已经是这条链路上最正确的形态**，没有进一步优化空间。
- 真正的重采样发生在 AudioFlinger 混音器（固定工作采样率）与蓝牙 HAL，两步都在应用之外。
- 所以"抬高蓝牙音质天花板"这件事，应用侧可做的动作是**不做动作**：不引入任何强制重采样的 DSP（均衡器、变速、空间化如未来实现需注意输出格式）。

### 2.3 如果仍想给用户一个交代

可做的是**诚实的探测**，不是宣传语：`AudioManager.getDevices(GET_DEVICES_OUTPUTS)` 过滤 `TYPE_BLUETOOTH_A2DP`，展示设备名与 `getAudioProfiles()` 报告的采样率/位深范围。这能回答"你的耳机支持什么"，但回答不了"现在用的是 LHDC 还是 AAC"。建议明确写"当前音频输出：蓝牙（编码由系统协商）"，把不可知的部分交给系统。

---

## 3. CTA-2075：API 是真的，但它解决的不是本地曲库的问题

### 3.1 机制

```
码流（含响度元数据） → MediaCodec 解码器 → LoudnessCodecController 注入增益参数
                                                  ↑
                                    需要应用把 MediaCodec 注册进来
```

平台侧 `LoudnessCodecController` 的四个公开方法全部围绕 `MediaCodec` 对象展开：`create(sessionId)` 绑定音频会话，`addMediaCodec(codec)` 返回 boolean，`getLoudnessCodecParams(codec)` 取当前参数，`removeMediaCodec(codec)` 解绑。**没有 `AudioTrack` 入口，也没有 `AudioEffect` 形态**——它不是一个可以挂在混音链路上的效果器。

Media3 1.11.1 的集成方式已核实（`javap` 反编译 `MediaCodecAudioRenderer` 7 参构造）：

```
getstatic android/os/Build$VERSION.SDK_INT
bipush 35
if_icmplt 30
new LoudnessCodecController   // SDK >= 35 无条件创建
aconst_null                   // SDK < 35 传 null
```

也就是说：**"自动启用"是真的，"检测到元数据才启用"是错的**——控制器在任何 Android 15+ 设备上都会被建出来，`AsynchronousMediaCodecAdapter` 在 `codec.start()` 之后再调 `addMediaCodec()`，返回 false 时静默移除。所以它的行为是"尽力而为"，全程无日志、无回调提示。本项目在 Android 17 真机（OPPO）上已经在跑这条路径，没有任何代码需要加。

### 3.2 为什么对本地曲库接近零收益

`addMediaCodec` 能否成功，取决于解码器是否声明支持响度参数传递。实际生效的内容形态是带 dialnorm / 响度元数据的 Dolby 系码流（AC-3 / E-AC-3 / AC-4）以及部分带 MPEG-4 DRC 的 AAC。本地曲库的现实分布是：

| 格式 | 带响度元数据 | 控制器是否产生增益 |
|---|---|---|
| FLAC / ALAC / WAV | 无 | 否 |
| MP3 | 无 | 否 |
| AAC-LC（iTunes 转码等） | 通常无（DRC 元数据常被剥离） | 否 |
| AC-3 / E-AC-3 / AC-4（演唱会 BD 抓轨） | 有 | 是 |

即：**用户抱怨的"这张专辑比上一张响 6 dB"，恰好落在控制器完全不管的那一格里。**

### 3.3 真正能解决这件事的做法：ReplayGain / EBU R128

业界标准做法是自己算响度，不依赖码流元数据：

1. **扫描**：对每首曲目做一次 EBU R128 / ITU-R BS.1770 积分响度（LUFS）计算，得到曲目增益与峰值。一首歌的整轨扫描在手机上是百毫秒级。
2. **存储**：增益值要落到自有存储（现有 `data/userdata/` 的 DataStore 结构可扩展），不能写回文件标签——MediaStore 不暴露写标签能力，且会触发重新扫描。
3. **施加**：`AudioProcessor` 在 `MediaCodecAudioRenderer` 的音频处理链上乘增益，配 `volume` 参数而不是 `LoudnessEnhancer` 效果器（后者走 `audiofx`，无法与 offload 共存，且是全局会话级）。

**这条路的真实代价是与 offload 直接冲突**：`PlaybackService.kt:97-99` 的注释已经写明——offload 绕过 ExoPlayer 的后处理，任何均衡器/变速都必须在启用期间把 offload 切到 `DISABLED`。响度增益属于同一类。也就是说：**响度归一化是拿"确定的功耗上升"换"主观听感一致"**，与 1.0.46/1.0.47 两批功耗优化的方向相反，属于产品决策，不是技术选型。

补充一个事实性纠正：CTA-2075 的目标响度是 **-24 LKFS**（广播口径），而音乐流媒体普遍用 -14 ~ -16 LUFS（ReplayGain 2.0 用 -18 LUFS 参考）。即便控制器生效，它把内容拉到的是广播口径，对音乐聆听偏低，不能直接当作"音乐响度归一化"。

---

## 4. xHE-AAC 与 IAMF：解码支持早就有，容器支持才是缺口

- **xHE-AAC**：`ENCODING_AAC_XHE since=28`，MIME `audio/mp4a.40.42` `since=33`。并非 Android 17 新增。对本地库无意义——xHE-AAC 的定位是 12–24 kbps 语音/流媒体，音乐曲库不会用。
- **IAMF**：MIME 与 12 个 `ENCODING_IAMF_*` 常量全部 `since=36`。平台侧有能力，但 `media3-extractor-1.11.1` 里**没有 IamfExtractor**，仅 MP4 的 `BoxParser` 出现 `iamf` 字样（说明 IAMF-in-ISOBMFF 有部分解析入口）。`media3-exoplayer` 侧存在 `IamfUtil`（含 `OutputLayout`），是渲染层的东西，不是解封装。

所以"中长期功能储备"这句要打个折：**要真播 IAMF，缺口不在解码器，在 extractor 与轨道选择**（Object Audio 的场景/元素映射）。而且 IAMF 的空间感要落到耳机上还得过系统的空间音频渲染，这条链本项目没有任何一段是自己能控的。

---

## 5. 与本项目现状的关系

| 现有实现 | 与三项能力的关系 |
|---|---|
| `PlaybackService.kt:43-45` `DefaultRenderersFactory` + `setEnableAudioFloatOutput(true)` | 对 LHDC 无影响（不改采样率）；对 CTA-2075 无影响（控制器在渲染器内部） |
| `PlaybackService.kt:100-108` offload `ENABLED` + gapless 必需 | 与 ReplayGain 冲突（见 §3.3）；与 CTA-2075 不冲突（控制器在解码器侧，offload 命中时根本没有 MediaCodec，控制器自然失效） |
| `InProcessCodecSelector`（D1） | 与 CTA-2075 有弱耦合：控制器注册的是被选中的那个 MediaCodec，inproc 解码器若不声明响度能力，`addMediaCodec` 返回 false |
| 无均衡器/AudioProcessor | 意味着 ReplayGain 是全新链路，不是改开关 |
| 真机验收记录（OPPO / Android 17） | offload 因设备只报 `BITSTREAM(4)` 而永不命中 → 当前该设备上 **MediaCodec 路径常驻**，即 CTA-2075 控制器在跑，但曲库是 FLAC/MP3，实际无增益 |

---

## 6. 三条路

### A. 什么也不做（建议对 LHDC / xHE-AAC / IAMF 三项采用）

理由：三项都没有应用侧的可执行动作，也没有可验证的收益。LHDC 连观测面都没有；xHE-AAC 与本地曲库无交集；IAMF 缺 extractor。**唯一的动作是不要在新文案里写"支持 LHDC v5 直通"**——那是一句无法被验证、也无法被证伪的话，与这个 App "不联网、不采集、不做营销噪音"的定位不符。

### B. ReplayGain 曲目增益（唯一有真实收益的方向，需产品决策）

最小可行形态：入库时算 LUFS → 存增益 → `AudioProcessor` 施加 → 设置页一个总开关（默认关）。必须同步决定的三个约束：

1. 开启时把 offload 切 `DISABLED`（功耗上升，与 1.0.47 方向相反）；
2. 扫描成本要摊到入库流程里，且要能在曲库变更后增量补扫；
3. 只做静态曲目增益，不做动态压缩（动态压缩会改变动态范围，属于"改母带"，发烧友明确反感）。

### C. 借助 CTA-2075 做"零成本"响度（不可行）

只能在应用自己持有 MediaCodec 并自行喂 PCM 的前提下做（等于换掉 ExoPlayer 的音频渲染器），收益仍受"码流必须有响度元数据"限制，对 FLAC/MP3 依然为零。**投入产出比最差的一条。**

---

## 7. 未验证清单

以下项为本次评估未能通过静态取证确认、需要真机或运行期数据才能定论：

1. **OPPO Android 17 真机上 `addMediaCodec()` 的实际返回值**：需要临时在 `MediaCodecAudioRenderer` 之外无法插桩，建议改为验证现象——播放一首 AC-3 演唱会抓轨与一首 FLAC，对比是否有可闻响度差。若 AC-3 也无变化，说明该机型解码器不接受响度参数。
2. **Android 17 是否在系统蓝牙栈内新增了 LHDC v5 的厂商扩展**：AOSP 公开 API 无对应常量，但厂商私有实现可能存在；无法通过 SDK 判断，需要设备端 `dumpsys bluetooth_manager` 观察当前 codec 名称（仅调试用，不可进产品逻辑）。
3. **`LoudnessCodecController` 在 AAC-LC + MPEG-4 DRC 元数据下的行为**：静态代码只能确认"注册"，不能确认"是否产生非零增益"。
4. **ReplayGain 的 LUFS 扫描成本**：未做基准测试，百毫秒级是行业经验值，需在目标机型实测（与 `2026-10-19-scan-metadata-io-eval.md` 的扫描预算合并评估）。
5. **IAMF 在 Media3 1.11.1 的端到端可播性**：`BoxParser` 的 `iamf` 字样不足以证明解封装完整，需用一个 IAMF 样本文件实测（本地库无此内容，优先级低）。
