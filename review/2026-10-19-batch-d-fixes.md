# 批次 D 执行记录：旧版图标清理与服务生命周期

基线：v1.0.43 + 批次 A/B/C。范围：删除被自适应图标遮蔽的旧版启动图标、修复「服务绑定永不释放」。**用户可见行为零变更**，图标视觉完全不变。

## 一、旧版启动图标清理

### 先核对引用，避免误删

`mipmap-anydpi/ic_launcher.xml` 的内容是：

```xml
<background android:drawable="@mipmap/ic_launcher_background" />
<foreground android:drawable="@mipmap/ic_launcher_foreground" />
<monochrome android:drawable="@drawable/ic_launcher_monochrome" />
```

`ic_launcher_background` 与 `ic_launcher_foreground` **只以 PNG 形式存在**（5 个密度各一对），删掉会直接导致资源链接失败。真正零引用的是 `ic_launcher.png` 与 `ic_launcher_round.png`：minSdk 已为 26，这两个名字被 `mipmap-anydpi` 下的自适应图标完全遮蔽，永远解析不到。

### 实际删除

10 个文件（5 个密度 × 2 个名字）。仓库无版本控制，删除前先备份到 `backup/mipmap-legacy-icons-2026-10-19/`，并逐文件比对 md5，全部一致后才删除。

保留的 12 个：

| 路径 | 作用 |
|---|---|
| `mipmap-anydpi/ic_launcher.xml` | 自适应图标（Manifest `android:icon`） |
| `mipmap-anydpi/ic_launcher_round.xml` | 圆形图标（Manifest `android:roundIcon`） |
| `mipmap-{m,h,x,xx,xxx}dpi/ic_launcher_background.png` | 背景图层 |
| `mipmap-{m,h,x,xx,xxx}dpi/ic_launcher_foreground.png` | 前景图层 |

### 收益

lint 26 → **11**。消失的 15 条全部是这两个文件产生的：

- `IconLauncherShape` ×10：方形图标填满整块区域（5 条）、圆形图标不是圆形（5 条）
- `IconDuplicates` ×5：`ic_launcher.png` 与 `ic_launcher_round.png` 内容完全相同

## 二、服务生命周期

### 根因（字节码证据，不是推测）

对 `media3-session-1.11.1.aar` 反编译确认：

- `MediaSessionService.pauseAllPlayersAndStopSelf()` 的实现就是 `stopSelf()`；`onTaskRemoved()` 在该服务「不在播放」时调用的也是它。
- Android 的服务语义：一个既被 `startService` 启动、又被客户端绑定的服务，`stopSelf()` 只撤销「启动」状态，**必须等所有绑定解除后才会真正销毁**。
- `MediaController` 正是一个绑定客户端。而 `PlayerController.release()` 全工程零调用（批次 C 已记录），绑定从建立到进程结束都不会解除。
- 同时 `MilanMusicApp.onCreate` 在启动 700ms 后**无条件** `connect()`。

四个条件叠加的实际后果：

1. 每次冷启动都会拉起 `PlaybackService` → 构建 `ExoPlayer` + `MediaSession` + `ArtworkBitmapLoader`，哪怕用户只是打开曲库看一眼。
2. 此后所有 `stopSelf()`（包括我们自己在 `PlaybackService.onTaskRemoved` 里写的那句）都失效，ExoPlayer 与它的渲染/解码线程活到进程结束。

### 改动

| # | 文件 | 变更 |
|---|---|---|
| 1 | `MilanMusicApp.kt` | 删除延迟 connect 与 `FIRST_FRAME_GRACE_MS`，`onCreate` 只建容器 |
| 2 | `play/PlayerController.kt:155` | 新增 `onUiStopped()`：`queueSize == 0` 时 `release()` |
| 3 | `play/PlayerController.kt:118` | `release()` 加空转早退 + 日志，并清理 `queue` / `lastWidgetSnapshot` / `_state` / `_progress` |
| 4 | `MainActivity.kt:24` | `onStop` 中 `!isChangingConfigurations` 时调用 `onUiStopped()` |

连接本身没有新增机制：`playQueue` 与 `togglePlayPause` 在 controller 为空时本来就走 `pendingAction + connect()`，widget 的播放按钮也走这两条路径，所以「按需连接」不需要额外代码。

### 为什么只在「没有队列」时释放

session 和队列都活在服务里。释放客户端会让暂停中的队列、以及通知栏的恢复入口一并消失。所以：

- **有队列 → 保持绑定**：暂停后可恢复、通知栏可续播，这是产品预期；
- **无队列 → 释放**：即「打开 → 浏览 → 离开，从未播放」这一种状态。此时没有任何东西可丢，释放绑定后只剩绑定的服务被销毁，ExoPlayer 随之回收。

`onUiStopped` 加 `isChangingConfigurations` 判断，是为了让旋转、深浅色切换这类重建不影响绑定。

### 代价

首次点播放会多一次 service bind（含 ExoPlayer 构建，约 100–300ms 量级）。这是用**一次性的首播延迟**换掉**每次冷启动都付的常驻开销**——原来那 700ms 的预连接，本质是把这笔成本提前到每次启动。

## 三、验证

- `:app:assembleDebug` / `:app:assembleRelease` / `:app:bundleRelease` / `:app:lintDebug` 全部 `BUILD SUCCESSFUL`
- lint：**0 error / 11 warning**（26 → 11，减少的 15 条全部来自删除的图标）
- `app-release.apk` 2.4 MB，`app-release.aab` 5.7 MB
- debug dex 反查含 `onUiStopped`；release 的 `mapping.txt` 显示它被 R8 内联进 `MainActivity.onStop`；`FIRST_FRAME_GRACE_MS` 已从产物中完全消失
- 资源表 (`aapt2 dump resources`)：`mipmap/ic_launcher` 与 `ic_launcher_round` 只剩 `(anydpi) XML`，`ic_launcher_background` / `ic_launcher_foreground` 的四个密度 PNG 完好

## 四、仍需真机确认（当前无 adb 设备）

1. 冷启动后不播放直接退出，`dumpsys activity services com.milan.music` 应为空
2. 播放 → 暂停 → 回桌面 → 重新进入，迷你播放器应仍显示该曲目（绑定未被释放）
3. 首次点播放的启动延迟是否可接受

## 五、遗留（有意的取舍，不是遗漏）

**暂停态下从最近任务划掉卡片，服务仍会存活。** 因为此时有队列，绑定被主动保留。要让「划掉即清理」生效，只能连队列一起丢——重开 App 后曲目清空。这属于产品取舍，需要确认后再改，不在本批擅自改动。

## 六、剩余 lint 清单（11 条，均为已知项）

| 类别 | 条目 |
|---|---|
| 依赖版本提示 ×3 | `core-ktx` 1.17→1.19.1、`activity-compose` 1.12.2→1.13.0、`kotlinx-serialization-json` 1.10→1.11 |
| KTX 建议 ×4 | `Bitmap.scale` ×2、`String.toUri` ×2 |
| 清单 | `ExportedService`：MediaSessionService 未加权限保护 |
| 布局 | `clipToOutline`（API 31+，Android 8–11 圆角失效）、`HardcodedText`（widget 一处） |
| Compose | `ModifierParameter`：`SongsList` 的 `modifier` 不是第一个可选参数 |
