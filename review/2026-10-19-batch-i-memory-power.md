# 批次 I 执行记录：位图回收出口 / 进度轮询门控 / 内嵌封面解码 / release 日志剥离

基线：v1.0.46（versionCode 47）+ 批次 A–H + `2026-10-19-memory-power-audit.md`。
范围：M1、P1、M8、P6 —— 审计里标注"纯收益、不碰任何用户可见行为"的四项。
**用户可见行为零变更**，未动任何布局、动效、交互与文案。

| 编号 | 问题 | 结论 |
|---|---|---|
| M1 | 三处位图 LRU 没有回收出口 | ✅ 两个挂载点 + 三档策略 |
| P1 | 进度轮询未按订阅者门控 | ✅ `subscriptionCount` 双闸 |
| M8 | API 26–28 内嵌封面全尺寸解码 | ✅ 两趟解码，峰值 36 MB → 2.25 MB |
| P6 | release 未剥离 `Log.d` 热路径 | ✅ R8 规则 + `queueById` 哈希表 |

---

## 一、M1 位图缓存增加回收出口

### 改动

| 文件 | 改动 |
|---|---|
| `data/media/ArtworkRepository.kt` | 新增 `onTrimMemory(level)`、`evictAll()` |
| `play/ArtworkBitmapLoader.kt` | 新增 `onTrimMemory(level)` |
| `MilanMusicApp.kt` | 覆写 `onTrimMemory` / `onLowMemory` → 转发 `container.artwork` |
| `play/PlaybackService.kt` | 覆写 `onTrimMemory` → 转发 `artworkLoader` |

### 为什么需要两个挂载点

`ArtworkBitmapLoader` 由 `PlaybackService` 私有持有（`PlaybackService.kt:108-109`），`AppContainer` 拿不到它；而 `Service` 本身就是 `ComponentCallbacks2` 的实现者，系统会直接回调它的 `onTrimMemory`。两处各管自己那份缓存，不引入全局单例或静态注册表。

### 档位映射

| 信号 | 列表 24 MB | 播放页 6 MB | 通知 8 MB | 理由 |
|---|---|---|---|---|
| `RUNNING_LOW` / `RUNNING_CRITICAL`（仅 API 26–33） | trim → 3 MB | trim → 0.75 MB | trim → 1 MB | 仍在前台，只缩不空，避免滚动中反复解码 |
| `UI_HIDDEN`（20） | trim → 4 MB | `evictAll` | —（服务侧此时收到的是 BACKGROUND） | App 离开前台；播放页不可能可见 |
| `BACKGROUND`（40）及以上 | `evictAll` | `evictAll` | trim → 2 MB | 无可见消费方；通知封面保留当前曲，避免重新解码 |

### 平台弃用是实测的，不是推断

反编译 `platforms/android-37.0/android.jar` 的 `android/content/ComponentCallbacks2.class`：

```
public static final int TRIM_MEMORY_COMPLETE;          Deprecated: true
public static final int TRIM_MEMORY_MODERATE;          Deprecated: true
public static final int TRIM_MEMORY_RUNNING_CRITICAL;  Deprecated: true
public static final int TRIM_MEMORY_RUNNING_LOW;       Deprecated: true
public static final int TRIM_MEMORY_RUNNING_MODERATE;  Deprecated: true
public static final int TRIM_MEMORY_BACKGROUND;        （有效）
public static final int TRIM_MEMORY_UI_HIDDEN;         （有效）
```

即：API 34 起平台**不再下发** `RUNNING_*`，API 35 起 `MODERATE` / `COMPLETE` 同样失效，只剩 `UI_HIDDEN` 与 `BACKGROUND` 两个真实信号。因此：

- 首分支写成 `level >= TRIM_MEMORY_BACKGROUND`（开放区间），老机型仍下发的 60 / 80 也一并落到"全清"，且不必引用已弃用常量；
- `RUNNING_LOW` 分支保留（minSdk 26，26–33 上它是最早的预警），方法加 `@Suppress("DEPRECATION")` 并在 KDoc 写明"仅为 API 26–33 保留"；
- 新增 `evictAll()` 给 `onLowMemory()` 用 —— 那个旧信号没有 level，语义就是"尽量全放"。

首轮实现直接用 `TRIM_MEMORY_COMPLETE` 常量时，`compileReleaseKotlin` 报了 3 条 deprecation warning（`MilanMusicApp.kt:42`、`ArtworkRepository.kt:144`、`ArtworkBitmapLoader.kt:99`）。改成上面的写法后 warning 归零。

### 预期收益

- 后台（队列存在、切走或息屏）PSS −25～30 MB；
- 前台首屏不变：所有封面都从 MediaProvider 本地重解码，不涉网络。

---

## 二、P1 进度轮询按订阅者门控

### 改动（`play/PlayerController.kt`）

```kotlin
private var progressObserved = false

init {
    scope.launch {
        _progress.subscriptionCount.collect { count ->
            progressObserved = count > 0
            if (progressObserved && controller?.isPlaying == true) {
                startProgressLoop()
            } else {
                stopProgressLoop()
            }
        }
    }
}

private fun startProgressLoop() {
    if (!progressObserved) return   // 新增：无人订阅时不起表
    if (progressJob?.isActive == true) return
    ...
}
```

`init` 刻意放在 `_progress` 声明之后：`Dispatchers.Main.immediate` 下 `launch` 会同步执行到第一个挂起点，若放在属性之前会读到未初始化的 `_progress`。

### 为什么 `subscriptionCount` 是对的信号

- `progress` 的唯一消费者是 `PlayerScreen.kt:99` 的 `collectAsStateWithLifecycle()`（全局 grep 确认）；
- `MilanApp.kt:38-52` 用 `AnimatedVisibility` 承载播放页，sheet 关闭即离开组合树（`exit = ExitTransition.None`，无残留）；
- `collectAsStateWithLifecycle` 在 `STOPPED` 时退订。

于是"有人看进度" ⇔ "屏幕亮且播放页打开"，正好是轮询唯一有意义的状态。

### 行为核对

| 场景 | 结果 |
|---|---|
| sheet 打开 + 播放中 | 订阅 1 → 起表，500 ms 刷新（与原来一致） |
| sheet 打开 + 暂停 | 暂停即停表；按播放键由 `EVENT_IS_PLAYING_CHANGED` 起表 |
| sheet 关闭 + 播放中 | 订阅 0 → 停表；迷你播放器不显示进度，无可见差异 |
| 息屏 + 播放中 | `repeatOnLifecycle` 退订 → 停表；息屏唤醒 7200 次/h → 0 |
| 息屏期间暂停（通知栏/耳机键） | `onEvents` 触发 `stopProgressLoop()`，写入最终位置 |
| 重新打开 sheet | 首个 tick 在 `delay` 之前同步执行，进度立即回位 |

`stopProgressLoop()` 在退订时仍会写一次 `_progress`：位置在暂停后不再变化，这次写入保证重新订阅时拿到的是最终值而不是旧值。

---

## 三、M8 API 26–28 内嵌封面两趟解码

`ArtworkBitmapLoader` 新增 `decodeScaled(bytes)` + `sampleSizeFor(longest)`：

```kotlin
val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
val longest = max(bounds.outWidth, bounds.outHeight)
if (longest <= 0) return null
val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(longest) }
return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let(::scaleDown)
```

`sampleSizeFor` 取"最长边除以它仍 ≥ 512"的最大 2 的幂：

| 原始最长边 | inSampleSize | 解码后 | 峰值 |
|---|---|---|---|
| 512 | 1 | 512 | 1 MB |
| 1024 | 2 | 512 | 1 MB |
| 2048 | 4 | 512 | 1 MB |
| 3000 | 4 | 750 | 2.25 MB（原 36 MB） |

`resolve()` 里原来的 `embeddedPicture(uri)?.let(::scaleDown)` 改为直接 `embeddedPicture(uri)`，缩放已在内部完成，不会二次缩放。

顺带把 `decodeBitmap(data: ByteArray)` 也接同一条路径：当前 `Track.toMediaItem()` 只设置 `artworkUri`、从不设置 `artworkData`，这条是安全网，但不该是全工程唯一一个还会全尺寸分配的入口。

---

## 四、P6 release 日志剥离 + `refresh()` 热路径

### R8 规则（`app/proguard-rules.pro`）

```
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
```

只处理 `v` / `d`：`Log.e`（`PlaybackService.kt` 的播放错误上报）必须保留。文件里没有任何 `-dontoptimize`，否则整条规则会被静默禁用。

### `refresh()` 的 O(n) → O(1)

`queue.firstOrNull { it.id.toString() == id }` 每次事件都做一次全表扫描，且每个候选都 `toString()` 分配一次字符串。改为 `queueById: HashMap<String, Track>`，在 `playQueue()` 替换队列时同步重建、在 `release()` 清空。`refresh()` 现在是一次哈希查找。

### 验证（决定性证据，非推断）

| 探针 | debug APK | release APK |
|---|---|---|
| `refresh: mediaId=` | 1 处（`classes8.dex`） | **0** |
| `release: detaching` | 1 处 | **0** |
| `playback error at pos=`（Log.e，应保留） | — | **1** |

debug 侧有命中证明探针本身有效（不是空断言），release 侧归零证明字符串与 `Log.d` 调用都被 R8 移除，`Log.e` 未受影响。

---

## 五、构建与检查

| 检查 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | 通过 |
| `:app:assembleDebug` | BUILD SUCCESSFUL |
| `:app:assembleRelease`（R8 + shrinkResources） | BUILD SUCCESSFUL |
| `:app:lintDebug` | **0 error / 4 warning**，4 条全部是既有的"新版本可用"（Gradle 9.3.1→9.8.1、Kotlin 2.4.20→2.4.21 ×3），无新增 |
| 编译警告 | 0（首轮有 3 条 `TRIM_MEMORY_*` 弃用，已按第一节方案消除） |

工程无单测目录（`app/src/test` 不存在），本次验证走编译 + 构建 + dex 字符串探针 + lint。

---

## 六、真机待验证（需要设备，我这侧无 adb）

```bash
# 1) 基线
adb shell dumpsys meminfo com.milan.music | grep -E "TOTAL PSS|Native Heap"

# 2) 浏览大曲库（滚动 300 首）→ Home 键 → 再测一次
#    期望 TOTAL PSS 下降 25~30 MB

# 3) 手动触发档位，确认缓存真的缩了
adb shell am send-trim-memory com.milan.music RUNNING_LOW
adb shell am send-trim-memory com.milan.music UI_HIDDEN
adb shell am send-trim-memory com.milan.music BACKGROUND

# 4) 息屏耗电前后对比（P1）
adb shell dumpsys batterystats --reset
# 播放后息屏 30 min
adb shell dumpsys batterystats --charged com.milan.music | grep -E "CPU:|wakeups|Estimated power"

# 5) 复核播放页进度条仍按 500 ms 刷新、暂停/继续无滞后
```

第 5 项是本次唯一有回归风险的地方（P1 改了起表条件），务必在新版本安装后先看进度条拖动与暂停后的位置是否跳变。

---

## 七、遗留（按审计原排序，未在本批处理）

- **M2 + P2**（Haze `performanceMode` 0.5 档）与 **M3**（播放页 `blur(48.dp)`）：视觉档位，动之前必须先 brainstorming + 截图对比。
- **M6 / P5**：需先取证（`bitmap.config` 是否 HARDWARE、offload 线程是否真在跑），不能推理定论。
- **M4**：曲库 3000+ 时 `setMediaItems` 是否撞 Binder 1 MB 上限，需实测。
- **P3 / P4 / M5 / P7 / P8**：按审计排期，P8 属产品决策。

版本号未动（仍 1.0.46 / 47），待发版时统一 bump 并出 `2026-10-19-release-*.md`。
