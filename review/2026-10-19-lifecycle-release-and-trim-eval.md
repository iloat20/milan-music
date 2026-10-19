# 生命周期释放与 onTrimMemory 分级：逐条核实

- 日期：2026-10-19
- 版本基线：1.0.47（含本日改动）
- 结论：**释放链路已完整且入口正确；`onTrimMemory` 已有 5 级处理，只缺 `RUNNING_MODERATE`。**

---

## 0. 结论表

| 提议 | 判定 | 依据 |
|---|---|---|
| 主动调 `MediaCodec.release()` / `AudioTrack.release()` | ❌ **不能直接调** | 两者归 media3 所有，直接释放是 use-after-free |
| 在 `onStop()` 释放播放器 | ❌ **会杀掉后台播放** | 服务侧无 `onStop()`；Activity 侧已有正确动作 |
| 播放完全停止后释放 | ✅ **已实现** | `PlaybackService.kt:242-253` 完整链路 |
| 实现 `ComponentCallbacks2` | ✅ **已实现** | `MilanMusicApp.kt:33`（`Application` 本身就是回调方）+ `PlaybackService.kt:212` |
| 响应 `RUNNING_MODERATE` 或更高级 | ⚠️ **差这一级** | 当前从 `RUNNING_LOW` 才开始裁剪 |
| 清理磁盘缓存的索引 | ❌ **方向相反** | `LibraryCacheStore` 是磁盘快照，清掉等于下次全量扫盘 |
| 清理预加载的歌词 | ❌ **无此模块** | 项目没有歌词功能 |

---

## 1. 释放：入口不能是 `MediaCodec.release()`

`MediaCodec` 与 `AudioTrack` 的实例由 media3 的 `MediaCodecRenderer` / `DefaultAudioSink` 创建和持有。应用层直接持有引用并释放，会在渲染线程下一次 `queueInputBuffer` / `write` 时踩到已释放的 native 句柄。

**正确入口是 `player.release()`**，它沿渲染器往下传：

```
ExoPlayer.release()
  └─ MediaCodecAudioRenderer.onDisabled()        // 字节码偏移 1206
       ├─ AudioSink.release()                     // → DefaultAudioSink.release()
       │    └─ releaseAudioTrack()                // AudioTrack 在这里释放
       └─ MediaCodecRenderer.onDisabled()         // → releaseCodec()
            └─ MediaCodec.release()               // 解码器在这里释放
```

本项目已经这样做了（`PlaybackService.kt:242-253`）：

```kotlin
override fun onDestroy() {
    mediaSession?.let { session ->
        session.player.release()   // 解码器 + 音轨 + 渲染器，一次到位
        session.release()
    }
    mediaSession = null
    releaseBufferPool()            // 缓冲池归零
    bufferAllocator = null
    artworkLoader?.release()       // 线程池 + 位图缓存
    artworkLoader = null
    super.onDestroy()
}
```

### 1.1 为什么不该放到 `onStop()`

**服务侧**：`PlaybackService` 继承 `Service` / `MediaSessionService`，没有 `onStop()` 回调。生命周期只有 `onCreate` / `onStartCommand` / `onDestroy`。

**Activity 侧**：`MainActivity.onStop()` 已经有动作，而且是对的（`MainActivity.kt:24-30`）：

```kotlin
override fun onStop() {
    super.onStop()
    if (!isChangingConfigurations) container.player.onUiStopped()
}
```

`onUiStopped()`（`PlayerController.kt:219-221`）：

```kotlin
fun onUiStopped() {
    if (_state.value.queueSize == 0) release()
}
```

**只在队列为空时释放** — 也就是"打开 App 逛了一圈没播就走"这种没有东西可丢的场景。有队列时故意保留绑定，因为会话状态在服务里，释放客户端会连暂停中的队列和通知的恢复路径一起丢掉。

如果按建议在 `onStop()` 无条件 `player.release()`，结果是**切到后台播放立刻停止**——与整个 `MediaSessionService` 架构冲突。

### 1.2 暂停时 AudioTrack 不释放是有意的

`DefaultAudioSink` 把 `pause()` 和 `release()` 做成两个独立方法（字节码里两个 public 方法并存）。暂停走 `pause()`，AudioTrack 保留。

原因是重建 AudioTrack 要重新协商 buffer size、重新走一遍 `AudioTrack.Builder`，过程中还可能产生一次 click。暂停三秒又点继续是最常见的操作，为省几十 KB native 内存付这个代价不划算。

---

## 2. `onTrimMemory`：五级已覆盖，缺 `RUNNING_MODERATE`

### 2.1 现状

两个实现点：

| 位置 | 覆盖范围 |
|---|---|
| `MilanMusicApp.kt:33-36` → `ArtworkRepository.onTrimMemory` | 列表 / 播放页 / 背景三层位图缓存 |
| `PlaybackService.kt:212-222` → `ArtworkBitmapLoader.onTrimMemory` | 通知栏位图缓存 |
| `MilanMusicApp.kt:39-42` → `onLowMemory()` | 全清兜底（旧 API 信号） |

`ArtworkRepository.onTrimMemory` 的分支（`:180-199`）：

| 级别 | 常量值 | 行为 |
|---|---|---|
| `TRIM_MEMORY_COMPLETE` | 80 | |
| `TRIM_MEMORY_MODERATE` | 60 | `evictAll()` — 全清 |
| `TRIM_MEMORY_BACKGROUND` | 40 | |
| `TRIM_MEMORY_UI_HIDDEN` | 20 | 列表缩到 1/6，播放页 + 背景缓存清空 |
| `TRIM_MEMORY_RUNNING_CRITICAL` | 15 | |
| `TRIM_MEMORY_RUNNING_LOW` | 10 | 三层各缩到 1/8 |
| **`TRIM_MEMORY_RUNNING_MODERATE`** | **5** | **无处理（落空）** |

`when` 的分支顺序是正确的：`UI_HIDDEN(20)` 不满足 `>= 40`，会落到 `== 20` 分支；`RUNNING_CRITICAL(15)` 只满足 `>= 10`。

### 2.2 缺的这一级要不要补

`RUNNING_MODERATE` 的官方语义是"系统开始吃紧，但进程不可杀，建议开始释放非必需资源"。当前实现直接跳到 `RUNNING_LOW`（"很吃紧"）才动手，等于放弃了第一个提示。

补法很轻：在 `RUNNING_LOW` 分支之前插一档减半。

```kotlin
level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
    cache.trimToSize(cache.maxSize() / 2)
    playerCache.trimToSize(playerCache.maxSize() / 2)
    backdropCache.trimToSize(backdropCache.maxSize() / 2)
}
```

**代价要说清楚**：`RUNNING_MODERATE` 会在**前台运行时**触发（用户正在滚动列表）。把列表缓存砍半，冷区封面会重新走 `loadThumbnail`，滚动时可能看到更多占位渐变。

在 256 MB `memoryClass` 的设备上，列表预算 20 MB → 砍到 10 MB，约从 135 张降到 68 张 192 px 封面。

### 2.3 「清理磁盘缓存的索引」方向相反

`LibraryCacheStore` 存的是曲库快照 JSON（`milan_library_cache`），在磁盘上。它不受内存压力影响，也不该在内存压力下被清：

- 清掉它 → 下次启动 `loadStamp()` 返回 null → 全量扫盘
- 内存压力下做一件让下次启动更慢的事，与目标相反

同理"预加载的歌词"——项目没有歌词模块（`grep` 无 `lyric` / `lrc` 命中），无对象。

---

## 3. 建议动作

| 选项 | 内容 | 规模 |
|---|---|---|
| A（推荐） | 补 `RUNNING_MODERATE` 减半分支 | 三行，`ArtworkRepository.kt:193` 前 |
| B | 不补，保持从 `RUNNING_LOW` 开始 | 0 |
| C | 补 A，同时给 `ArtworkBitmapLoader` 也加一级 | 六行 |

---

## 4. 回退

A / C 都是在 `when` 里加一个分支，回退即删除该分支。

---

## 5. 执行记录（选项 A，2026-10-19 20:20）

改动文件：`app/src/main/java/com/milan/music/data/media/ArtworkRepository.kt`，`onTrimMemory` 新增一个分支。

### 5.1 改后完整分级

| 级别 | 常量 | 行为 |
|---|---|---|
| `COMPLETE` / `MODERATE` / `BACKGROUND` | 80 / 60 / 40 | `evictAll()` |
| `UI_HIDDEN` | 20 | 列表 1/6，播放页 + 背景清空 |
| `RUNNING_LOW` / `RUNNING_CRITICAL` | 10 / 15 | 三层各 1/8 |
| **`RUNNING_MODERATE`** | **5** | **三层各 1/2（新增）** |

### 5.2 实现过程中发现的顺序陷阱

第一版把 `RUNNING_MODERATE` 分支放在 `RUNNING_LOW` **之前**，这是错的：

```
RUNNING_LOW      = 10
RUNNING_MODERATE =  5
```

两个分支都用 `>=`，`10 >= 5` 成立，于是 `RUNNING_LOW` 与 `RUNNING_CRITICAL` 事件会先命中更宽的 `>= 5` 分支，**1/8 的强裁剪永远不会执行**，整条响应被悄悄削到 1/2 档。

已改为按常量值降序排列：40 → 20 → 10 → 5，并在分支上方写了注释说明为什么这两条的顺序不能调换。

### 5.3 为什么减半不会造成可见闪烁

`LruCache.trimToSize()` 淘汰的是**最久未使用**的条目。用户当前屏幕上的行与播放页封面，恰恰是最近刚用过的那批，处于 LRU 尾部，不会被淘汰。真正被丢弃的是离开视口的冷区封面——它们本来也要等用户滚回去才需要，那时重新解码一次（`loadThumbnail`，5–15 ms）即可。

### 5.4 验证

| 项目 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL（33s） |
| `:app:lintDebug` | 0 errors / 6 warnings，与改动前一致 |
| `:app:assembleDebug` | BUILD SUCCESSFUL |
| 真机 | 未验证 |

### 5.5 未做

`ArtworkBitmapLoader.onTrimMemory`（`ArtworkBitmapLoader.kt:108-115`）仍从 `RUNNING_LOW` 开始。它只有 `>= BACKGROUND` 与 `>= RUNNING_LOW` 两个分支，不存在顺序陷阱，只是少了 MODERATE 这一档。通知栏位图预算约 3 MB（`3/16`），补上收益有限，保持现状。

