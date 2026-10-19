# 真机验收：1.0.47 遗留项 + 未发版三处改动

**日期**：2026-10-19
**设备**：OPPO PLJ110 · Android 17 (API 37) · 1256×2760 · density 560
**被测包**：versionCode 48 / 1.0.47 + 未发版 D1（进程内解码器偏好）+ A（bufferForPlaybackMs 120）+ B（preload 5 s）
**方法**：adb 实测；临时探针构建两次（播放状态迁移 / `AudioManager.getDirectPlaybackSupport`），测毕已还原源码

---

## 一、结论先行

| 项 | 结果 | 证据 |
|---|---|---|
| 冷启动（进程死→首帧） | ✅ 207–228 ms | `am start -W` ×3：209 / 228 / 207 |
| 热启动 | ✅ 45–49 ms | `LaunchState: HOT` |
| D1 进程内解码器 | ✅ **生效** | `audio decoder=c2.android.inproc.aac.decoder initMs=17` |
| P3 水位线指纹 | ❌ **本机失效** | MediaProvider 抛 `Invalid column count(*) as track_count` |
| P5 offload 命中 | ❌ **本机永不命中** | `getDirectPlaybackSupport` 返回 4，media3 要求 bit0 |
| A 缓冲 120 ms | ✅ 无欠载 | 连播 + 连切 5 首无 `underrun`、无 `PlaybackException` |
| B 预加载 5 s | ✅ 无回归 | 切歌 5 次全部正常出声，无卡顿日志 |
| M1 trim 三档 | ✅ 生效 | MODERATE：110.4 → 101.9 MB（-8.6 MB） |
| M6 崩溃路径 | ✅ 未复现 | 播放页 + 5 次切歌（FLAC/MP3 交替）无异常 |
| P4 小组件冷启动 | ✅ 通过 | force-stop 后点播放键 → 进程 13705 拉起 → FGS 启动 |
| M7 小组件圆角 | ⚠️ 单实例通过 | 桌面显示圆角正常；双实例需手工添加第二个组件 |
| 内存 | 前 103 / 播 135 / 后 110 MB | `dumpsys meminfo` PSS |

---

## 二、决定性发现一：P3 在 Android 17 上静默失效

`MediaStoreRepository.fingerprint()`（`data/media/MediaStoreRepository.kt:100`）用聚合投影取水位线：

```kotlin
"COUNT(*) AS track_count",
"MAX(${MediaStore.Audio.Media.DATE_MODIFIED}) AS newest_modified",
```

真机日志：

```
E DatabaseUtils: java.lang.IllegalArgumentException: Invalid column count(*) as track_count
```

`runCatching` 把异常吞掉返回 null，调用方回落全量扫描 —— **功能正确，优化归零**。每次冷启动都会重新扫 1233 行（`queue size=1233`）。

印证了代码注释里"some OEM ROMs do"的保守写法是必要的，但也说明 Android 17 的 MediaProvider 属于这一类。修复方向：改用 `MediaStore.getGeneration(context, volumeName)`（API 30+，官方专门用于判断媒体库是否变化），单次 binder 调用、无需聚合投影。

---

## 三、决定性发现二：D1 在 AAC 上生效，且真机存在 inproc 解码器

```
I PlaybackService: audio decoder=c2.android.inproc.aac.decoder initMs=17
```

- 选取的是 `c2.android.inproc.aac.decoder`，不是默认的 `c2.android.aac.decoder`。默认 `MediaCodecUtil.getDecoderInfos` 按名称排序时 `c2.android.aac.decoder` 会排在前面，因此选中 inproc 只能是我们 selector 重排的结果。
- `initMs=17`，对比同机 MP3 软解 76 ms、FLAC 33–41 ms，初始化开销明显更低。
- **一处推翻**：`dumpsys media.player` 的 "Decoder infos by media types" 段落**不包含** inproc 解码器，据此判断"设备不支持"是错误的。该 dump 不是 MediaCodecList 的全集，只有实际初始化时才能证实。

---

## 四、决定性发现三：offload 在本机永不命中，且与 gapless 无关

设备音频策略明确支持：

```
"compress_offload"; 0x20031 (DIRECT|COMPRESS_OFFLOAD|NON_BLOCKING|GAPLESS_OFFLOAD)
  AUDIO_FORMAT_MP3   44100/48000  立体声
  AUDIO_FORMAT_AAC_LC 44100/48000 立体声
```

但应用侧探测（临时探针，USAGE_MEDIA + CONTENT_TYPE_MUSIC）：

```
PROBE direct=mp3/44100  support=4
PROBE direct=mp3/48000  support=4
PROBE direct=aac/44100  support=4
PROBE direct=aac/48000  support=4
```

`4 = DIRECT_PLAYBACK_BITSTREAM_SUPPORTED`（`javap -constants` 核实：`OFFLOAD_SUPPORTED=1` / `GAPLESS=3` / `BITSTREAM=4`）。

media3 1.11.1 `DefaultAudioOffloadSupportProvider.getOffloadedPlaybackSupportV33` 字节码：

```
invokestatic AudioManager.getDirectPlaybackSupport
istore_3
iload_3; iconst_1; iand; ifne 16
  → getstatic AudioOffloadSupport.DEFAULT_UNSUPPORTED; areturn
```

即 **`(support & 1) == 0` 直接返回不支持**。本机 `4 & 1 == 0` → `getAudioOffloadSupport()` 返回 0 → `DefaultTrackSelector.rendererSupportsOffload` 第一步 `ifne` 失败 → offload 不参与判定。

因此：

1. **1.0.46 的 offload 优化在本机为零收益**，实际走的是 `MediaCodec` 软解 + `MIXER` 输出线程（`AudioOut_15`, `1 Tracks of which 1 are active`）。
2. **`setIsGaplessSupportRequired(true)` 不是阻碍**，之前 `offload-keep-or-disable.md` 把命中率归因于 gapless 的结论需要更正：在 bit0 缺失的设备上，放宽 gapless 同样不会命中。
3. `rendererSupportsOffload` 的真实逻辑（字节码，非推断）是：
   - `getAudioOffloadSupport() == 0` → false
   - 若要求变速支持，需 `support & 0x800`
   - 若要求 gapless，**仅当 format 带 `encoderDelay`/`encoderPadding`** 时需 `support & 0x400`，否则放行

---

## 五、其余实测数据

- **内存**：列表页 103.1 MB / 播放页 135.2 MB（PSS，含 Haze 源层）/ 退后台 110.8 MB。后台 Native Heap 从 52.1 → 39.0 MB，说明播放页图形资源已释放。
- **trim**：RUNNING_LOW 无变化（110.6 MB），MODERATE 触发 `evictAll` → 101.9 MB，COMPLETE 后持平。与 `ArtworkRepository.onTrimMemory` 的分档一致。
- **小组件**：单实例 id=25，`views_bitmap_memory=262144`（256 KB）；播放中封面与 `dumpsys media_session` 的 `无言感激 / 谭咏麟` 一致，同步正常。
- **无崩溃**：全程 `AndroidRuntime` 无 FATAL；`PlaybackService` 无 `playback error`。
- **既有噪声（与本轮无关）**：SystemUI 侧 `ImageLoader: DecodeException ... 'unimplemented'` 大量出现（pid 3828），来自通知封面解码，不影响应用进程。

---

## 六、待办

1. ~~**P3 修复**~~ → 已实施并真机闭环验证，见 `2026-10-19-p3-generation-stamp-fix.md`。
2. **M7 双实例**：需手工在桌面添加第二个小组件后复测圆角与 `coverCache` 键正确性。
3. **A/B 量化**：首播延迟与切歌等待需要播放状态迁移探针（已在 `/tmp/probe.patch` 保存），本轮未做定量对比。
4. **文档更正**：`offload-keep-or-disable.md` 的 gapless 归因需按本文第四节修订。
5. 息屏功耗、后台长时间驻留未测（需长时观察）。
