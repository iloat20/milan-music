# 前台服务降级与扫描/预加载调度：逐条核实

- 日期：2026-10-19
- 版本基线：1.0.47（含本日 IO 修复）
- 结论：**前台服务类型与空闲降级均已由 manifest + media3 实现，默认超时 10 分钟；WorkManager 调度扫描的方向与产品行为冲突，不建议。**

---

## 0. 结论表

| 提议 | 判定 | 依据 |
|---|---|---|
| `mediaPlayback` 类型前台服务 | ✅ **已声明** | `AndroidManifest.xml:59` + 权限 `:13-14` |
| Doze 下不被冻结 | ✅ **成立**，且本应用更彻底 | 纯本地播放，`INTERNET` 权限被显式移除（`manifest:17-19`） |
| 停止/长时间暂停后退出前台、移除通知 | ✅ **media3 内置**，默认 **10 分钟** | `MediaNotificationManager` 构造器 `userEngagedTimeoutMs = 600000` |
| 「降级为普通服务」 | ❌ **方向错误** | Android 8+ 不允许长存后台服务，正确动作是退出前台后 `stopSelf()` |
| 曲库扫描走 WorkManager 约束调度 | ❌ **与产品行为冲突** | 扫描是主路径数据加载，延后会让新加的歌看不见 |
| 封面缓存预热走 WorkManager | ❌ **无此任务** | 封面按需解码 + 双层 LRU，没有预热路径 |
| 避免活跃使用时抢 CPU | ✅ **已满足** | 缓存命中时整次扫描被跳过，只剩 1 次 Binder |
| PreloadManager 过度预加载的警告 | ✅ **已避开** | 用的是 `setPreloadConfiguration`，`PlaybackService.kt:117-125` 明确说明不用 `PreloadManager` |

**不需要改代码。** 唯一可选的是一个旋钮：把 10 分钟调短（见 §1.4）。

---

## 1. 前台服务

### 1.1 类型与权限：已声明

`app/src/main/AndroidManifest.xml`：

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
...
<service
    android:name=".play.PlaybackService"
    android:exported="true"
    android:foregroundServiceType="mediaPlayback"
    tools:ignore="ExportedService">
```

`PlaybackService` 继承 `MediaSessionService`，前台类型由 media3 在 `startForeground()` 时带上 `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`。API 34+ 缺少这两条权限会抛 `SecurityException`，本项目已齐全。

### 1.2 Doze：本地播放不受影响

Doze 限制的是网络访问、`JobScheduler` / `AlarmManager` 唤醒、以及普通 wakelock。前台服务本身**不在 Doze 的冻结范围内**。

本项目还多一层保险：`AndroidManifest.xml:17-19` 用 `tools:node="remove"` 移除了 `INTERNET` 权限——纯本地曲库，Doze 的网络限制根本没有作用对象。

**一处需要澄清**：`setWakeMode(C.WAKE_MODE_LOCAL)`（`PlaybackService.kt:113`）持有的 partial wake lock **只在播放时存在**。media3 的判定在 `ExoPlayerImpl.updateWakeAndWifiLock()` 字节码里：

```
tableswitch playbackState { 1: false, 2: goto, 3: goto, 4: false }
...
setStayAwake(getPlayWhenReady() && !isSleepingForOffload())
```

`playbackState == READY(3)` 且 `playWhenReady == false`（暂停）→ `setStayAwake(false)`。所以「暂停后一直持锁耗电」这条担心不成立。

### 1.3 空闲降级：media3 已经做了，默认 10 分钟

`MediaNotificationManager` 构造器字节码：

```
121: ldc2_w 600000        // userEngagedTimeoutMs = 600000
124: putfield userEngagedTimeoutMs:J
128: iconst_3
129: putfield showNotificationForIdlePlayerMode:I   // AFTER_STOP_OR_ERROR
117: iconst_1
118: putfield isUserEngagedTimeoutEnabled:Z         // 超时机制默认开启
```

`shouldRunInForeground()` 的逻辑：

```
engaged = isAnySessionUserEngaged(...)
timeoutEnabled = isUserEngagedTimeoutEnabled && userEngagedTimeoutMs > 0

if (isUserEngaged && !engaged && timeoutEnabled)
    mainHandler.sendEmptyMessageDelayed(MSG_USER_ENGAGED_TIMEOUT, userEngagedTimeoutMs)
else if (engaged)
    mainHandler.removeMessages(MSG_USER_ENGAGED_TIMEOUT)
```

超时触发后（`handleMessage`，`what == 1`）：

```
for (session in mediaSessionService.getSessions())
    onUpdateNotificationInternal(session, /* startInForegroundRequired = */ false)
```

该路径调用 `Util.stopForeground(service, ...)` → **退出前台 + 移除通知**。

即：暂停满 10 分钟 → 服务自动退出前台、通知消失。**这就是建议里描述的行为，只是窗口是 10 分钟而非"立即"。**

### 1.4 为什么不该「降级为普通服务」

Android 8+ 的后台服务限制下，一个退出前台的服务不能长期存活：进程进入 cached 状态后随时被 LMK 回收，且不能重启。所以正确的终点只有两个——要么留在前台，要么 `stopSelf()`。

media3 走的是后者：退出前台后，`onTaskRemoved` / 无会话时由 `MediaSessionService` 调 `stopSelfSafely()`。本项目 `PlaybackService.kt:197-202` 也覆盖了划掉任务的场景：

```kotlin
override fun onTaskRemoved(rootIntent: Intent?) {
    val player = mediaSession?.player
    if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
        stopSelf()
    }
}
```

**可选的旋钮**：`setForegroundServiceTimeoutMs(long)`，内部 `Util.constrainValue(value, 0, 600000)`——只能调短，最长就是默认的 10 分钟。

| 取值 | 效果 | 代价 |
|---|---|---|
| 600000（默认） | 暂停 10 分钟后退出前台 | 无 |
| 300000 | 5 分钟 | 通知提前消失，用户无法从通知恢复播放 |
| 0 | 暂停即退出前台 | 接个电话回来通知就没了，对音乐 App 是明显的 UX 退化 |

**建议保持默认。** 10 分钟对"暂停时间较长"是个合理定义，再短就开始伤可用性。

---

## 2. 扫描与预加载调度

### 2.1 WorkManager 调度扫描：与产品行为直接冲突

曲库扫描不是后台维护任务，它是**主路径的数据加载**：用户打开 App 就是为了看曲库。

当前的实际开销（`LibraryViewModel.ensureLoaded()`）：

| 场景 | 开销 |
|---|---|
| 缓存命中且索引未变 | 1 次 `MediaStore.getGeneration` Binder 调用，**0 次 query** |
| 索引变化（用户刚加了歌） | 1 次 `stamp()` + 1 次游标遍历 + 1 次 DataStore commit |

如果把它挪到 WorkManager 的 `requiresDeviceIdle(true)` 或 `requiresCharging(true)` 约束下：

- 用户从电脑拖了 20 首歌进手机，打开 App —— **看不到新歌**，要等设备空闲/充电
- 「空闲」的触发可能延迟几小时到几天
- 用户唯一的补救是手动下拉刷新，等于把自动化退化成手动操作

同时 WorkManager 硬成本：新增 `androidx.work:work-runtime-ktx` 依赖（约 300–500 KB APK）、一个 JobScheduler 调度栈、一套 `Worker` 生命周期。**为零个可调度的后台任务付这个代价。**

### 2.2 封面预热：不存在这个任务

封面走的是按需解码 + 双层 LRU：

- `ArtworkRepository.cache`（`BitmapBudget` 分配的列表预算）+ `playerCache` + `backdropCache`
- 全部在 `CoverArt` 首次组合时异步解码，命中即返回

没有"启动时批量预解码整库封面"的路径，也就没有可挪到 WorkManager 的预热任务。反过来说，**真去做全库预热才是过度预加载**——用户可能只播 30 首，却为 5000 首解码封面。

### 2.3 PreloadManager：已经明确避开

`PlaybackService.kt:117-125`：

```kotlin
// This is deliberately not PreloadMediaSource: that one keeps a second
// MediaPeriod plus its own looper per preloaded item and exists for list
// preview / audition, which this app does not do.
player.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(5_000_000L))
```

只预热**下一首的 5 秒压缩样本**（约几百 KB），不建解码器。这已经是"不过度预加载"的形态：预加载对象只有 1 个，范围是队列里紧邻的一项，而不是滑动窗口。

---

## 3. 建议动作

| 选项 | 内容 |
|---|---|
| A（推荐） | 不写代码。本文件入档 |
| B | 把 `setForegroundServiceTimeoutMs` 调到 5 分钟（一行），接受通知提前消失 |
| C | 引入 WorkManager 调度扫描（不建议，理由见 §2.1） |

---

## 4. 回退

不涉及代码改动，无回退项。
