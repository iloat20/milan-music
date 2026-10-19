# 解码缓冲区：池化与按需加载的逐条核实

**日期**：2026-10-19
**范围**：`play/PlaybackService.kt`
**证据来源**：`media3-exoplayer-1.11.1.aar` / `media3-common-1.11.1.aar` 字节码（`javap`）、`android-37.0/android.jar`
**结论**：三条建议中 1 条无落点、1 条已是现状、1 条方案不可取；实际改动只有一处 —— 缓冲池的回收策略。

---

## 一、结论表

| 建议 | 判定 | 一句话 |
|---|---|---|
| 为解码 ByteBuffer 实现对象池 | ❌ 无落点 | codec 缓冲区由 `MediaCodec` 管理；预读缓冲区已有池 |
| —— 但池的回收策略有真实缺陷 | ⚠️ 已修 | `trimOnReset = true` 使每次切歌清空池 |
| 流式解码 + LoadControl 限制预读 | ✅ 已是现状 | 15 s / 30 s / 120 ms / 500 ms，且已从默认 50 s 压下来 |
| PreloadManager 滑动窗口 | ❌ 已否决 | 见 `2026-10-19-preload-and-offload-eval.md` |
| `Debug.getNativeHeapAllocatedSize()` 周期采样 | ⚠️ 方向对，方案不可取 | 统计口径缺 mmap/ashmem；轮询有开销 |

---

## 二、池化：池本来就存在

`DefaultAllocator`（`androidx.media3.exoplayer.upstream`）内部维护 `availableAllocations` 数组，通过 `allocate()` / `release()` / `trim()` 复用 64 KB 段。这是 Java 堆上的 `byte[]`，不是 direct ByteBuffer。

应用层无法池化 `MediaCodec` 的输入/输出缓冲区——它们由 codec 自己通过 `getInputBuffer()` / `getOutputBuffer()` 管理，`MediaCodecAudioRenderer` 只是搬运者。

**字节码核实**（`DefaultLoadControl$Builder.build()`）：

```
28: new           #114  // class androidx/media3/exoplayer/upstream/DefaultAllocator
32: iconst_1            // trimOnReset = true
33: ldc           #118  // int 65536  (C.DEFAULT_BUFFER_SEGMENT_SIZE)
35: invokespecial      // DefaultAllocator.<init>(Z I)V
```

**回收链路**：

```
DefaultAllocator.reset():
   1: getfield trimOnReset:Z
   4: ifeq 12                      // false -> 直接返回
   9: invokevirtual setTargetBufferSize(I)   // 参数为 0

setTargetBufferSize(0):
   0: iload_1                      // 0
   5: if_icmpge 12                 // 0 >= 当前值? 否
  13: istore_2                     // reduced = true
  20: ifeq 27
  24: invokevirtual trim()

trim():
   8: Util.ceilDivide(targetBufferSize, individualAllocationSize)  // = 0
  14: Math.max(0, targetAllocCount - allocatedCount)               // = 0
  28: if_icmplt 32                                                 // 0 < availableCount -> 继续
  ... 逐个置 null 并收缩数组
```

即：**切歌 → `reset()` → 目标归零 → 整个空闲池被释放**。以 30 s FLAC（1411 kbps）计算约 5.3 MB、82 个段，每次跳曲全部丢弃后重新分配。

### 改动

`PlaybackService` 自建 `DefaultAllocator(trimOnReset = false, C.DEFAULT_BUFFER_SEGMENT_SIZE)` 并注入 `DefaultLoadControl.Builder.setAllocator()`，使池跨曲保留。

段大小**必须**保持平台默认：`trim()` 按整段计算，改小或改大会让池的字节账目与 `DefaultLoadControl` 的预期漂移。

### 回收点

`trimOnReset = false` 意味着 `reset()` 变成空操作，因此需要显式回收：

| 时机 | 行为 |
|---|---|
| `onTrimMemory(>= BACKGROUND)` 且未播放 | `setTargetBufferSize(0)`，释放全部空闲段 |
| `onDestroy()`（`player.release()` 之后） | 同上，然后置空引用 |
| 下次 `onTracksSelected` | `DefaultLoadControl` 重新设置真实目标值，池按需重建 |

`isPlaybackActive()` = `playWhenReady || isPlaying`。播放中的后台场景（熄屏听歌）不释放，因为池正在被使用。

---

## 三、流式解码与 LoadControl：已是现状

`PlaybackService.kt:54-63` 已配置：

| 参数 | 值 | 默认 |
|---|---|---|
| `minBufferMs` | 15 000 | 50 000 |
| `maxBufferMs` | 30 000 | 50 000 |
| `bufferForPlaybackMs` | 120 | 2 500 |
| `bufferForPlaybackAfterRebufferMs` | 500 | 5 000 |

`ExoPlayer` 对 progressive 媒体始终是流式解码，不存在"整曲解压进内存"的路径。

`setPreloadConfiguration(5_000_000L)`（`:86`）预读 5 秒压缩样本、不建解码器。

`PreloadMediaSource` + `DefaultPreloadManager` 在既有评估中已否决：需要第二个 `MediaPeriod`、独立 Looper、第二套 Renderer 配置，服务对象是列表试听/滚动预览，与本项目（单曲播放 + 手动切歌）场景错配，且与内存专项方向相反。

---

## 四、Native 内存监控：口径问题大于 API 问题

`Debug.getNativeHeapAllocatedSize()` 返回 **malloc 统计**，不包含 mmap、ashmem、graphics 分配。

本项目使用 in-process codec（`c2.android.inproc.aac.decoder`，见 `2026-10-19-inprocess-codec-introduce.md`），其解码缓冲多为 mmap 或 codec 私有分配，恰好落在该 API 的统计盲区。用它做趋势判断会得出"native 内存平稳"的错误结论。

**正确口径**：

- 运行时：`Debug.getMemoryInfo(MemoryInfo)` → `nativePss`（含 malloc + mmap 的 PSS 份额）
- 离线：`adb shell dumpsys meminfo <pkg>`、Perfetto `heapprofd`、Android Studio Memory Profiler

**周期采样不可取**：内存统计读取有毫秒级开销，放在播放路径上会引入新问题。诊断应是一次性、可开关的，而不是常驻轮询。本轮未加任何埋点。

---

## 五、验证与风险

| 项目 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `:app:lintDebug` | 0 errors / 6 warnings（全部为既有项，无新增） |
| `:app:assembleDebug` | BUILD SUCCESSFUL |
| 真机 | 未验证（无设备连接） |

### `@UnstableApi` 接入的一个坑

`DefaultAllocator` 是 `@UnstableApi` 类型。成员级 opt-in 试了两种写法都不行：

- `@OptIn(UnstableApi::class)` 直接标在 `private var` 属性上 → Kotlin 编译通过，但 **lint 仍报 `UnsafeOptInUsageError`**（lint 的检查不认属性级 opt-in）
- `@field:OptIn(...)` → Kotlin 编译直接失败：`This annotation is not applicable to target 'backing field'`

最终改为**类级** `@OptIn(UnstableApi::class)`。该类存在的目的就是配置 ExoPlayer 内部（`setPreloadConfiguration`、`DefaultAllocator`、renderers factory），全类放开是合理的。

**本改动属无实测支撑的调参，必须验证**：

1. 连续切歌 20 次的 `artwork`/GC 日志对比 —— 预期 `GC_FOR_ALLOC` 与 `GC_CONCURRENT` 次数下降
2. 熄屏播放 30 min 的 PSS —— 预期与改前持平（池被保留而非泄漏）
3. 暂停 → 后台 → `onTrimMemory` 之后的 PSS —— 预期回落 1–5 MB
4. 从 FLAC 切到 AAC 后再切回 —— 确认 `setTargetBufferSize` 下降时池正确收缩

**回退方式**：删除 `.setAllocator(allocator)` 一行即可回到默认 `trimOnReset = true`；`releaseBufferPool()` 对默认实例同样安全（只是冗余）。
