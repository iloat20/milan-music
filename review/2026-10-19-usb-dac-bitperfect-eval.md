# USB DAC 直通与 Bit-perfect 输出评估

| 项 | 值 |
|---|---|
| 评估对象 | `AudioMixerAttributes` / `MIXER_BEHAVIOR_BIT_PERFECT`（API 34+）、libusb UAC 直通、DoP |
| 本项目 | milan-music 1.0.47 · Media3 1.11.1 · minSdk 26 · targetSdk 37 |
| 性质 | 只读评估，未改任何代码 |
| 结论 | **官方路径可用且有真实收益，但只对 USB DAC 用户生效**；libusb 直通被官方文档明确反对，不建议；DSD 需要自写 extractor，属于换引擎 |

---

## 1. 结论表

| 你的描述 | 核实结果 | 证据 |
|---|---|---|
| 传统路径经系统混音器，强制 SRC 引入底噪与失真 | ✅ 成立 | AudioFlinger 混音器通常固定 48 kHz，非 48k 源在此重采样 |
| Android 14（API 34）+ 开放 `AudioMixerAttributes` | ✅ 成立 | `ApiSince=34`；`setPreferredMixerAttributes` / `getSupportedMixerAttributes` / `clearPreferredMixerAttributes` 均自 34 起 |
| 可将输出采样率/位深与源文件对齐 | ✅ 成立 | 官方示例：`AudioFormat(ENCODING_PCM_24BIT_PACKED, CHANNEL_OUT_STEREO, 44100)` 后 `setPreferredMixerAttributes` |
| **"完全绕过 Android 系统混音器"** | ⚠️ 措辞不准 | `MIXER_BEHAVIOR_BIT_PERFECT` 的语义是**把混音器配成不混音、不调音量、不做音效**，数据原样送 HAL；仍在 AudioFlinger 内，不是绕过它 |
| 需要权限 | ✅ `MODIFY_AUDIO_SETTINGS`（normal 级，清单声明即可，无运行时弹窗） | 绑定层的 `RequiresPermission` 注解 |
| 适用范围 | ⚠️ 仅 **USB 设备** + 仅 `USAGE_MEDIA` | 官方原文「Currently, only USB device will be allowed」「only AudioAttributes#USAGE_MEDIA is supported」 |
| **"内置 libusb UAC1/UAC2 驱动，完全跳过系统音频栈"** | ⚠️ 技术上存在，但官方明确反对 | 「When you try to directly access the USB audio peripheral with USB APIs, problems arise: security issues, limitation of media playback from other apps, and loss of alarms, notifications, and ringtones」 |
| DSD 通过 DoP 直通 | ✅ 传输层支持 | 官方原文「Using BIT_PERFECT allows you to stream DSD over PCM on supported Android devices」；但见 §3.3，解析要自己做 |
| 收益：消除 SRC 底噪 | ✅ 成立，但仅限 USB DAC 一条路径 | 蓝牙、扬声器、3.5 mm 不受影响 |

---

## 2. 官方口径：BIT_PERFECT 到底做了什么

`developer.android.com/media/platform/improve-audio-playback`：

> When the mixer behavior is `BIT_PERFECT`, **no audio mixing, volume adjustment, or audio effect processing is applied** to playback. Data is sent as-is to the HAL and finally to the USB device.

以及关键的匹配条件：

> When your app's playback **matches the encoding format, channel mask, and sample rate** of the preferred mixer attributes, the playback attaches to the output audio stream whose mixer is configured with the preferred mixer attributes.

**这是一条静默约束**：三个字段（encoding / channelMask / sampleRate）任何一个不匹配，播放就落回默认 mixer —— 不报错、不抛异常，只是又回到 SRC。所以"设了 BIT_PERFECT"和"真的走了 BIT_PERFECT"是两件事，必须靠 `OnPreferredMixerAttributesChangedListener` 和实际格式核对来验证。

音量也是坑：BIT_PERFECT 下**音量调节不生效**。对播放器来说这意味着系统音量键会变成哑的，需要产品层面决策（很多专业播放器在这种情况下会切到 DAC 硬件音量或提示用户）。

---

## 3. 对本项目的三个硬约束

### 3.1 只有 USB 输出设备，且要跟源格式逐字段对齐

`getSupportedMixerAttributes(usbDevice)` 返回候选列表，必须从中**精确匹配**源文件的 `encoding + channelMask + sampleRate`。44100 Hz 的 24-bit FLAC 与 48000 Hz 的 16-bit MP3 需要两套配置，切歌时得重设。DAC 不支持该采样率时（比如只支持 44.1/48，而源是 96k），要么放弃 bit-perfect，要么软件重采样（那就没意义了）。

### 3.2 当前 `setEnableAudioFloatOutput(true)` 与 bit-perfect 直接冲突

`PlaybackService.kt` 现在开着 float 输出，AudioTrack 会被建成 `ENCODING_PCM_FLOAT`，与 mixer 的 `ENCODING_PCM_24BIT_PACKED` 永远匹配不上。要做 bit-perfect 必须**按设备切换**成整数位深输出：USB DAC 在场时关 float，其余场景保持现状（float 对内置 DAC 的高解析源仍然更优）。

这条不是改一行就能收尾的——`DefaultRenderersFactory` 的 float 开关在 `onCreate` 里一次性设定，而 USB 插拔是运行时事件，需要重建 renderers 或重建 player。

### 3.3 DSD：传输层有路，但没有入口

官方那句话只保证"BIT_PERFECT 能把 DoP 送出去"。要真的播放 DSD 还差三件事：

1. **解析 `.dsf` / `.dff`** —— Media3 1.11.1 没有任何 DSD extractor（`media3-extractor` 的 mime 列表里没有 dsd）。
2. **打包 DoP 帧** —— 把 1-bit DSD 流塞进 24-bit PCM 的每个样本，高 8 位交替 `0x05` / `0xFA` 标记，采样率 = DSD 率 / 16。
3. **媒体库入口** —— MediaStore 不索引 DSD 文件（系统不认识），需要 SAF 目录选择或自建扫描。

合计等于"新写一个 extractor + 一条独立播放通路"，不是给现有链路加开关。

---

## 4. 与本项目现状的关系

| 现有能力 | 与 bit-perfect 的关系 |
|---|---|
| 音频 offload（`AUDIO_OFFLOAD_MODE_ENABLED`） | **两条不同的直通**。offload 是把编码帧原样交给 DSP，对内置 DAC 已经免混音；但 USB 输出一般不支持 compressed offload（`AudioTrack` 需 `isOffloadedPlaybackSupported` 且走 USB HAL），所以 USB DAC 恰恰是 offload 不生效、需要 mixer attributes 的场景 |
| `setEnableAudioFloatOutput(true)` | 冲突，见 §3.2 |
| `InProcessCodecSelector`（刚引入的 D1） | 无冲突。混合路径下它照样减少 IPC，只是 bit-perfect 路径上 SRC 归零、解码仍在 CPU |

补充一个判断依据：Media3 的默认 `AudioProcessorChain` 里，`ResamplingAudioProcessor` 名字有误导性 —— 它做的是**编码位深**转换（int16 ↔ float），不改采样率；唯一改采样率的是 `SonicAudioProcessor`，只在变速时启用。所以源采样率是原样交给 `AudioTrack` 的，SRC 发生在 AudioFlinger 的 mixer 里 —— 这正是 `AudioMixerAttributes` 要修的位置。这一条是从 `AudioCapabilities` 的公开 API（只有 `supportsEncoding` / `getMaxChannelCount`，没有采样率字段）与 `DefaultAudioSink` 的字节码推断的，**未经真机实测**。

---

## 5. 收益范围（决定这件事值不值得做）

| 输出路径 | 当前是否 SRC | BIT_PERFECT 收益 |
|---|---|---|
| 手机扬声器 / 3.5 mm 耳机 | 是（48k mixer） | 无（非 USB，API 不允许设置） |
| 蓝牙（SBC/AAC/LDAC） | 是，且二次编码 | 无 |
| USB DAC | 是 | **有**，且是唯一手段 |
| 内置 DAC + offload 命中 | 否（直通 DSP） | 无 |

也就是说：**这项功能只服务"插了 USB DAC 的用户"**。在没有用户画像的前提下，这是典型的"专业播放器功能"——UAPP、Neutron、Hiby 这类应用的核心卖点，而它们的用户就是这批人。

---

## 6. 三条路

### A. 探测（零风险，先做这个）

不加任何播放行为，只读：

1. `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` 过滤 `TYPE_USB_DEVICE` / `TYPE_USB_HEADSET` / `TYPE_USB_ACCESSORY`；
2. 对 USB 设备调 `getSupportedMixerAttributes`，把每一项的 encoding / channelMask / sampleRate 打日志；
3. `AudioDeviceInfo.getAudioProfiles()` 对照，看系统认为它能接什么。

一条 `adb logcat` 就能回答"我的用户到底有没有这个场景"。不改变任何播放行为，不影响验证清单里已有的项。

### B. 官方 BIT_PERFECT 路径（中等工作量，建议 A 有数据后再做）

USB DAC 插入时：查支持列表 → 找到与当前曲目格式逐字段匹配的项 → `setPreferredMixerAttributes(USAGE_MEDIA, device, attrs)` → 关 float 输出并按该格式喂 AudioTrack → 拔插时 `clearPreferredMixerAttributes` 复原。附带要解决：音量键在 BIT_PERFECT 下失效的提示、切歌时格式变化的重设、`OnPreferredMixerAttributesChangedListener` 的回调（用户可能手动改了系统设置）。

### C. libusb UAC 直通 + DSD（不建议）

官方文档明确把这条路列为**问题来源**（安全、抢占其他应用音频、丢失闹钟通知）。技术上 Android 公开的 `UsbDeviceConnection` 连 isochronous transfer 都没有，只能靠隐藏 API 取 fd 交给 `libusb_wrap_sys_device`，属于会被系统更新打断的非官方依赖。再叠加 §3.3 的 DSD 解析，等于放弃 Media3 换一套播放引擎。

---

## 7. 建议

先做 **A**（探测 + 日志，约 30 行，不动播放路径）。拿到"我们的用户里有多少人插 USB DAC、他们的设备支持哪些格式"之后再决定 **B**。

**C 不建议做**，除非这个 App 的定位要从"本地音乐播放器"改成"Hi-Fi 播放器"——那是产品定位问题，不是技术选型问题。

与 D1 的关系：两者正交。D1 在 offload 未命中的路径上省 IPC，bit-perfect 在 USB 路径上省 SRC。可以共存，也可以只做其一。
