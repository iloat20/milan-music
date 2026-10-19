# 批次 II 执行记录：扫描水位线 / 小组件位图缓存 / 位图记账 / 广播门控 + offload 取证

基线：v1.0.46 + 批次 I（M1 / P1 / M8 / P6）。
范围：`2026-10-19-memory-power-audit.md` 的批次 II —— M6、P5（取证）、P4、P3、M7。
**M2 + P2 未动**：两者都是视觉档位（Haze 降采样 / 采样源替换），按项目约定必须先 brainstorming 并做截图对比。

| 编号 | 结论 |
|---|---|
| P3 | ✅ 水位线比对，未变化时跳过全量扫描 |
| P4 | ✅ 无实例时不发广播 |
| M7 | ✅ 小组件封面按 albumId 缓存 |
| M6 | ✅ 记账改为真实分配字节；`getPixels` 前置软件化（不改代码无法验证 HARDWARE 假设，但两条分支现在都正确） |
| P5 | ⚠️ **只取证，不改代码；并推翻审计原文的一条判断**（详见第四节） |

---

## 一、P3 冷启动不再无条件全量扫 MediaStore

### 改动

| 文件 | 改动 |
|---|---|
| `data/model/Track.kt` | 新增 `dateModified`（默认 0，兼容旧缓存） |
| `data/media/MediaStoreRepository.kt` | projection 加 `DATE_MODIFIED`；新增 `fingerprint()` 与 `IndexFingerprint` |
| `data/media/LibraryCacheStore.kt` | `CachedTrack.dateModified` 双向映射 |
| `ui/library/LibraryViewModel.kt` | `ensureLoaded()` 先比水位线，命中则直接返回 |

`fingerprint()` 用一条聚合查询取代十列全表游标：

```sql
SELECT COUNT(*) AS track_count, MAX(date_modified) AS newest_modified
FROM audio WHERE is_music != 0 AND duration >= 60000
```

### 为什么是这两个指标

- **删文件** → 行数变化；
- **增文件 / 改标签**（MediaStore 会更新 `DATE_MODIFIED`）→ 最大值变化；
- 两者都不变 ⇒ 用户不可能看到任何差异。

两个聚合都走索引，成本与曲库规模基本无关，而原路径是「每行十列 + 构造对象」，审计里 200–800 ms 的估计就是这部分。

### 两个必须处理的边界

1. **老缓存没有 `dateModified`**（字段刚加）：反序列化默认 0 → 水位线 `(n, 0)` 与真实值不符 → 触发一次全量扫描。为了保证只付一次，`ensureLoaded()` 在扫描后**无条件** `libraryCache.save(fresh)`（原来只在 id 集合变化时保存）。否则「ids 相同 → 不保存 → 下次仍然扫描」会永久退化。
2. **provider 拒绝聚合投影**（个别 OEM）：`fingerprint()` 外层 `runCatching` 返回 null，`null != watermark` → 回落全量扫描，即原行为，不会误判为「无变化」而卡住曲库。

### 已知退化（可接受）

若某 ROM 的 `DATE_MODIFIED` 恒为 0，则「数量不变、只改标签」的变更不会触发刷新，需要手动刷新。`MediaStore` 标准实现会维护该列，属边缘情况。

---

## 二、P4 没有小组件实例时不再广播

`MusicWidgetProvider.refresh()` 原来每次曲目切换都 `sendBroadcast`，接收方 `onReceive` → `goAsync()` → 新起 `CoroutineScope` → 查到 0 个实例后什么都不做。

改法：`instanceFlag: Boolean?` 静态标志，`null` 表示「本进程还没判断过」：

| 时机 | 动作 |
|---|---|
| `onUpdate` / `onEnabled` | `true` |
| `onDisabled`（最后一个实例被移除） | `false` + `coverCache.evictAll()` |
| 进程重启后首次 `refresh()` | 查一次 `getAppWidgetIds` 并缓存 |

代价从「每曲一次广播 + 协程 + 一次 IPC」降到「一次布尔读」。

---

## 三、M7 / M6 位图

### M7 小组件封面缓存

`render()` 每次刷新都 `scale(256)`，再在 `withRoundedCorners()` 里 `createBitmap` 第二张 —— 每次刷新 2 × 256 KB，而封面只有换曲才变。

新增 `coverCache`（`LruCache<Long, Bitmap>`，上限 2 MB ≈ 8 张），键为

```
key = albumId * 128 + radiusBucket     // radiusBucket = radiusPx.toInt() ∈ [0,127]
```

半径进键是因为它由启动器单元格宽度推出（`cardCornerRadiusPx`），不同实例可能不同；`albumId * 128` 保证低 7 位只归半径用，不会跨专辑碰撞。最后一个实例移除时清空缓存。

### M6 记账与 `getPixels`

**改动**

| 位置 | 之前 | 现在 |
|---|---|---|
| `ArtworkRepository` 两个 LRU | `ImageBitmap`，`w*h*4` | `Bitmap`，`allocationByteCount` |
| `ArtworkBitmapLoader` LRU | `Bitmap`，`byteCount` | `Bitmap`，`allocationByteCount` |
| `MusicWidgetProvider.coverCache` | — | `allocationByteCount` |
| `trimLightMargins` | 直接 `getPixels` | 先 `softwareCopy()` 再读 |
| `PlayerScreen.dominantColorOf` | 直接 `getPixels` | 同上，失败回落 `FallbackTint` |

`byteCount` 在 API 19+ 已等价于 `allocationByteCount`（都是行跨距对齐后的实际字节），这次统一用后者是为了与 HARDWARE 位图的语义一致。

**关于「必须先取证」**：没有设备，无法证明本机 `loadThumbnail` 是否返回 HARDWARE。但两条分支现在都正确，所以这项**不再依赖取证结果**：

- 是 HARDWARE → 记账准确（之前 `w*h*4` 可能与图形内存实际占用不符），且 `getPixels` 不会抛；
- 是 ARGB_8888 → `softwareCopy()` 是 no-op（`config` 判断后才复制），零成本。

真正被修掉的是一条**崩溃路径**：`trimLightMargins` 在 `withContext(IO)` 里调用，外层只有 `decodeBitmap` 的 `runCatching`，一旦 `getPixels` 抛 `IllegalStateException` 就会穿透到 `LaunchedEffect` 的协程。取证的价值现在只剩「这条路径在目标机型上是否会触发」，不再是「要不要修」。

---

## 四、P5：float 输出与 offload 的关系（推翻审计原文）

审计里写了「float 输出不是免费的…… 对 16-bit/44.1k 的主流曲库它是纯开销」。**这句是错的。** 证据来自 media3 1.11.1 官方 sources jar（`dl.google.com/dl/android/maven2/.../media3-exoplayer-1.11.1-sources.jar`，与项目依赖版本一致）：

### 证据 1：float 只对高分辨率 PCM 生效

```java
// DefaultAudioSink.java:1755-1757
private boolean shouldUseFloatOutput(@C.PcmEncoding int pcmEncoding) {
    return enableFloatOutput && Util.isEncodingHighResolutionPcm(pcmEncoding);
}
```

```java
// media3-common Util.java:2645-2651
public static boolean isEncodingHighResolutionPcm(@C.PcmEncoding int encoding) {
    return encoding == C.ENCODING_PCM_24BIT || ... _24BIT_BIG_ENDIAN
        || encoding == C.ENCODING_PCM_32BIT || ... _32BIT_BIG_ENDIAN
        || encoding == C.ENCODING_PCM_FLOAT || ... _FLOAT_BIG_ENDIAN;
}
```

16-bit 源（MP3 解码输出、绝大多数 16/44.1 FLAC）走的是 `toInt16PcmAudioProcessor` 分支（`DefaultAudioSink.java:755-759`），**float 标志完全不参与**。所以「两倍搬运」在主流曲库上并不存在。

### 证据 2：offload 根本不经过 PCM 管线

```java
// DefaultAudioSink.java:794-799
} else {
  // Audio processing is not supported in offload or passthrough mode.
  audioProcessingPipeline = new AudioProcessingPipeline(ImmutableList.of());
  ...
}
```

链路：`TrackSelectionParameters.audioOffloadPreferences` → `DefaultTrackSelector:3517-3527` 生成 `RendererConfiguration(OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED)` → `MediaCodecAudioRenderer.shouldUseBypass()` 在「offload 首选 + 格式支持 + gapless 支持（或 encoderDelay/Padding 为 0）」时返回 true = 编码直通。

结论：**float 与 offload 不在同一条路径上，不存在「float 抵消 offload」**。审计那条「若 offload 未生效，再评估去掉 float」的建议建立在错误前提上，撤回。

### 证据 3：gapless 是收紧项，不是错误配置

`setIsGaplessSupportRequired(true)` 会让 bypass 多一个条件：文件带 `encoderDelay/Padding` 且设备不支持 gapless offload 时，回落到解码通路。这是「格式属性 × 设备能力」的结果，不是配置 bug —— 与 1.0.46 发布说明里「gapless 必须是硬要求，否则硬件缓冲会在曲间插静音帧」的取舍一致。

### 仍然必须实测的部分

「本机 offload 线程是否真的被选中」仍需设备：

```bash
adb shell dumpsys media.audio_flinger | grep -iE "offload|direct|deep"
```

这一项无法从源码推出，也无法从 APK 静态分析得出。

---

## 五、构建与静态检查

| 检查 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | 通过 |
| `:app:assembleDebug` / `:app:assembleRelease`（R8） | BUILD SUCCESSFUL |
| `:app:lintDebug` | **0 error / 4 warning**（4 条均为既有的上游版本提示） |
| 编译警告 | **0** |

顺带修掉一条既有警告：`HomeScreen.kt:223` 的 `Icons.Rounded.Sort` 在 `Track` 增加字段触发重编译后暴露为 deprecation，改用 `Icons.AutoMirrored.Rounded.Sort`（同一字形，LTR 下像素一致，仅 RTL 镜像）。

静态复查：

```
getPixels 调用点 2 处        → 均前置 softwareCopy / config 判断
sizeOf 定义 4 处             → 全部 allocationByteCount
BYTES_PER_PIXEL 残留          → 0
```

工程仍无 `app/src/test`，本批验证 = 编译 + 构建 + lint + 静态复查。

---

## 六、真机待验证

```bash
# P3：冷启动首帧与扫描耗时（第二次冷启动应与第一次的 CPU 突发明显不同）
adb logcat -b events -s am_displayed
adb shell dumpsys batterystats --charged com.milan.music | grep -i "cpu"

# P4：无小组件时切歌，确认 onReceive 不再被唤醒
adb shell am broadcast -a com.milan.music.widget.REFRESH   # 手动路径仍应正常

# M7：放两个不同宽度的 2x2 实例，确认圆角与封面都正确
# M6：导出 bitmap.config 需临时加日志；本轮用「不崩溃 + 内存曲线正常」间接验证

# P5：offload 是否真的在跑（唯一必须上机的一项）
adb shell dumpsys media.audio_flinger | grep -iE "offload|direct|deep"
```

---

## 七、遗留

- **M2 + P2**：Haze `HazePerformanceMode.Fixed(0.5f)` 与采样源调整 —— 视觉档位，先 brainstorming + 截图对比。
- **M3**：播放页 `blur(48.dp)`，状态不变（批次 B 已决定保留，需截图对比才动）。
- **M4**：`setMediaItems` 整库跨 Binder 是否撞 1 MB 上限，需 3000+ 首实测。
- **P1 的功耗收益**、**M1 的 PSS 收益**：批次 I 的实现已完成，数字仍待设备测量。
- 版本号未动（1.0.46 / code 47）。
