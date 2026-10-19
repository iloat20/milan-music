# 米兰音乐 1.0.44 合并前代码审查

| 项 | 值 |
|---|---|
| 审查对象 | **v1.0.44 发布产物**（`app/build/outputs/apk/release/app-release.apk`、`bundle/release/app-release.aab`，构建于 2026-10-19 09:12，versionCode 45）及其对应源码树 |
| 审查方式 | 产物核验 + 逐文件静态审查 + 跨生产者/消费者交叉对账 |
| 产物可信度 | 源码文件最新 mtime 为 09:00（`MusicWidgetProvider.kt` 等 4 个），无任何源码晚于 09:12 的产物 → 产物由本报告审查的这份源码构建 |
| 基线 | **无版本控制**，仓库根目录无 `.git`，无法做 SHA 级 diff。v1.0.43 的差异只能依据同目录五份批次执行记录重建 |
| 未复跑 | `:app:lintDebug`。文档声称 `No issues found.`，本报告将其视为**未经独立复现的声明**；构建结果已由产物本身间接证明 |
| 测试 | `find app/src -maxdepth 2 -type d` 仅返回 `main`。**工程无 `src/test`、无 `src/androidTest`，零自动化测试** |

## 产物核验（本报告独立复现部分）

| 项 | 命令 | 结果 |
|---|---|---|
| APK 版本 | `aapt2 dump badging` | `com.milan.music` / **versionCode='45'** / **versionName='1.0.44'** / minSdk 26 / targetSdk 37 |
| APK 签名 | `apksigner verify -v` | `Verifies`；v2 ✅ v3 ✅；v1 ❌（minSdk 26 预期）；证书 `CN=Milan Music, O=Milan, L=Shanghai` |
| v4 签名 | 目录内 `app-release.apk.idsig` | 26,137 B 存在；`apksigner` 对 APK 本体报 v4=false 属预期，**未验证 idsig 内容有效性** |
| AAB | `unzip -l` | 203 条目、5.9 MB、含 `BundleConfig.pb`、清单内含 `1.0.44` |
| 合并清单 | `aapt2 dump xmltree` | `allowBackup=false`、`fullBackupContent=false`、`dataExtractionRules=@0x7f0c0000`、`enableOnBackInvokedCallback=true` 均在位 |
| 代码是否进包 | `mapping.txt` | 1047 处 `PlayerScreen`/`PlayerController` 符号 → 批次 A–E 代码确在产物内 |
| **分发缺口** | `ls dist/` | `dist/` 只有 1.0.38–**1.0.43**，**1.0.44 未归档**；发布物仅存在于 `build/outputs/`（`clean` 即消失）→ 见 P2-7 |

---

## 一、合并结论

**审查结论：v1.0.44 原始产物不建议发布；两个 P0 已于 2026-10-19 10:06 修复并重新出包，修复后产物见文末"修复复核"。**

产物本身完整、签名有效、清单与资源齐备（见上表），问题全部在行为层，且集中在同一批改动里：

1. 播放页返回键在 240ms 动画窗口内二次按下会退出应用 —— 直接击穿本版宣称的 P0 修复。
2. `connect()` 与 `release()` 的竞态可让 `controller` 永久指向一个已释放实例，播放功能在本进程内不可恢复。

两者的共同特征是：构建通过、lint 为 0、R8 产物正常、签名校验通过，**所有可自动化的发布信号都无法暴露它们**。本版五批改动同时触碰播放器生命周期、返回栈、线程调度三条最敏感的路径，而工程没有任何测试可以兜住其中任何一条。

---

## 二、阻断问题（P0）

### P0-1 返回键二次按下退出应用

- **位置**：`ui/player/PlayerScreen.kt:241`

```kotlin
BackHandler(enabled = !isSettling.value, onBack = dismissToHome)
```

- **事实**：`dismissToHome`（166-179）第一件事就是 `isSettling.value = true`，随后 `offsetY.animateTo(target, tween(240))` 才调用 `onBack()`。`isSettling` 变 true 的同一帧，`BackHandler` 的 `enabled` 转 false，回调从 `OnBackPressedDispatcher` 注销。
- **后果**：`ComponentActivity` 的 dispatcher 在没有启用回调时走 Activity 默认行为（`finish()`）。因此在 240ms 窗口内的第二次返回不再被"吞掉"，而是直接结束 Activity。这与发布说明第 21 行"返回键不再退出应用"的结论相反，也与同文件 239-240 行注释自述的不变量（"the user can never be stranded"）相反。
- **可达性**：双击间隔典型 150–250ms，与动画时长同量级。`adb shell input keyevent 4 && input keyevent 4` 即可稳定触发。
- **改动建议**：让 handler 全程保持注册，把幂等判断放进回调内部。

```kotlin
BackHandler { if (!isSettling.value) dismissToHome() }
```

- **验证方式**：`adb shell input keyevent KEYCODE_BACK` 两次，间隔 100ms；期望停留首页且播放页已移除，`dumpsys activity activities | grep mResumedActivity` 仍为 `com.milan.music/.MainActivity`。补一条 Compose UI 测试（`createAndroidComposeRule`）断言二次 press 后 `playerOpen` 仍为 false。
- **状态**：**已修复**（`PlayerScreen.kt:248`，`BackHandler` 不再绑定 `isSettling`，守卫移入回调）。构建与 lint 已通过；真机双击返回仍未验证（无 adb 设备）。

### P0-2 `release()` 与在途 `connect()` 竞态，导致 controller 永久失联

- **位置**：`play/PlayerController.kt:92-108`（回调）、`:118-135`（release）

```kotlin
future.addListener({
    runCatching { future.get() }
        .onSuccess { mediaController ->
            controller = mediaController          // ← 无条件覆盖，且不在 runCatching 内
            mediaController.addListener(listener)
            refresh()
            ...
        }
        .onFailure { connection = null }
}, ContextCompat.getMainExecutor(context))
```

- **事实**：
  1. `buildAsync()` 在主线程投递创建任务 A；A 内 `set()` 完成 future，随后把 listener 作为任务 B 投递到同一主线程 executor。
  2. A 与 B 之间存在消息队列间隙，一个已排队的生命周期消息（`Activity.onStop`，见 `MainActivity.kt:24-30`）可以插进来执行 `release()`。
  3. `release()` 把 `connection` 置 null 并对已完成 future 调 `MediaController.releaseFuture()` —— 该 API 对 `isDone()` 的 future 会执行 `get().release()`，controller 已被释放。
  4. 之后 B 才执行：`future.get()` 成功返回**同一个已释放的实例**，赋给 `controller`。
- **后果（与版本无关的那一半）**：`connect()` 首行的 `if (controller != null || connection != null) return` 从此永久短路 —— 用户再点播放不会重连，`togglePlayPause()` / `playQueue()` 会在这个已释放实例上调用 media3 方法。**播放能力在进程剩余生命周期内不可恢复**，只能杀进程。
- **推测部分（需实测确认）**：已释放的 `MediaController` 上调用 `addListener`/`refresh` 是否抛 `IllegalStateException` 取决于 media3 1.11.1 的内部守卫；若抛，则表现为主线程 executor 里的未捕获异常（`onSuccess` 体不在 `runCatching` 内）→ 崩溃；若不抛，则是上面那种静默失联。
- **可达性**：冷启动后立刻按 Home（<300ms，恰好在 bind 窗口内）；或点歌播放后立即退到后台 —— 后者还会叠加 P1-1。
- **改动建议**：引入连接代次，回调必须校验自己仍是最新一次连接。

```kotlin
private var connectEpoch = 0

fun connect() {
    if (controller != null || connection != null) return
    val epoch = ++connectEpoch
    ...
    future.addListener({
        if (epoch != connectEpoch) {                 // 已被 release 作废
            runCatching { future.get().release() }
            return@addListener
        }
        runCatching {
            future.get().also { mediaController ->
                controller = mediaController
                connection = null                    // 成功后不再持有 future
                mediaController.addListener(listener)
                refresh()
                if (mediaController.isPlaying) startProgressLoop()
                pendingAction?.invoke()
                pendingAction = null
            }
        }.onFailure { connection = null }
    }, ContextCompat.getMainExecutor(context))
}

fun release() {
    connectEpoch++
    ...
}
```

- **验证方式**：把连接状态机抽到可注入的接口后面做 JVM 单测：用可控 executor 让"future 完成"与"release"交错，断言最终 `controller == null` 且后续 `togglePlayPause()` 会重新发起 connect。设备侧复现：冷启动 → 100ms 内按 Home → 回前台点歌，音频必须能播。
- **状态**：**已修复**（`PlayerController.kt:64-72` 新增 `connectEpoch`；`:95` 取代次；`:107-110` 代次不符则丢弃；`:113-127` 整个回调体进 `runCatching`；`:118` 成功后清 `connection`；`:144` `release()` 递增代次）。构建、lint、R8 已通过，`mapping.txt` 确认 `int connectEpoch -> i` 进入产物；**JVM 交错单测尚未补**，设备侧复现未验证。

---

## 三、改进建议（P1）

### P1-1 `onStop` 吞掉用户刚发出的播放请求

- **位置**：`play/PlayerController.kt:123`（`pendingAction = null`）配合 `:159-166`、`:182-190`
- **事实**：`playQueue`/`togglePlayPause` 在未连接时把动作放进 `pendingAction` 再 `connect()`。此时 `_state.queueSize` 仍为 0，若紧接着触发 `onStop` → `onUiStopped()` → `release()` → `pendingAction = null`，请求被丢弃且无任何反馈。
- **后果**：冷启动点歌后立即切后台，音乐不响；回来后 mini player 空白，用户会认为"点了没反应"。
- **改动建议**：`release()` 前判断 `pendingAction != null` 则不释放；或把 pending 动作提升到 `AppContainer` 级别（UI 释放不应丢弃用户意图）。
- **验证方式**：冷启动 → 点第一首歌 → 150ms 内按 Home → 返回，断言 `PlayerUiState.queueSize > 0`。

### P1-2 自动播放使 `onUiStopped` 的释放分支几乎永不执行

- **位置**：`ui/library/HomeScreen.kt:184-193` 与 `play/PlayerController.kt:155-157`
- **事实**：曲库一就绪就 `playQueue(随机曲目)`，`queueSize` 随即 >0。而 `onUiStopped()` 只在 `queueSize == 0` 时释放。因此**只要设备上有一首歌**，退出 UI 时绑定必定保留，`PlaybackService` 与其 ExoPlayer 依然活到进程结束。
- **后果**：批次 D 的核心收益（"服务可被正常销毁"、发布说明第 30 行）仅对空曲库成立；同批删除的 700ms 预连接，被 300ms 后的自动播放重新补上。批次 D 的待验证项 4（`dumpsys activity services` 应为空）按当前代码必然失败。
- **改动决策点（需产品确认）**：三选一 —— ①取消冷启动自动播放；②释放条件改为"UI 已停止 + 非播放态"，接受暂停队列丢失；③保持现状，但把发布说明里的结论改正。本次只能标注，不擅自改。
- **验证方式**：真机 `adb shell dumpsys activity services com.milan.music | grep PlaybackService` 在"启动→不操作→返回桌面"后执行，按当前代码预期仍有记录。

### P1-3 API 26–28 内嵌封面全尺寸解码，OOM 风险

- **位置**：`play/ArtworkBitmapLoader.kt:91-100`
- **事实**：`embeddedPicture()` 直接 `BitmapFactory.decodeByteArray` 原始字节，之后才 `scaleDown()`。一张 3000×3000 的专辑内嵌 PNG 解码后约 36MB（ARGB_8888），`scaleDown` 再用 `bitmap.scale` 分配第二份。缓存上限虽为 8MB，但峰值不受 `LruCache` 约束。minSdk = 26，26–28 正是这条分支。
- **后果**：低内存机型在锁屏/通知刷新时 OOM；`runCatching` 只包住 `resolve()`，`OutOfMemoryError` 是 `Error` 不会被其捕获。
- **改动建议**：先 `inJustDecodeBounds = true` 取尺寸，按目标 512 计算 `inSampleSize`，再解码；`scaleDown` 后 `if (scaled !== bitmap) bitmap.recycle()`（此处独占所有权，安全）。
- **验证方式**：构造/寻找一张 ≥3000px 内嵌封面的音频文件，API 28 模拟器播放该曲目，`adb shell dumpsys meminfo com.milan.music` 观察峰值；或在 `embeddedPicture` 加断言记录解码尺寸。

---

## 四、改进建议（P2）

| # | 位置 | 问题（事实） | 改法 | 验证 |
|---|---|---|---|---|
| P2-1 | `widget/MusicWidgetProvider.kt:101-103`；`data/media/ArtworkRepository.kt:202` | 批次 B 把 `LIST_THUMB_SIZE` 降为 192，但桌面小组件仍走 `artwork.load(track)` 再 `scale(256, 256)` —— 192px 源被放大约 1.33 倍，属本批引入的画质回归。widget 2×2 单元在 3x 屏约 330–480px，原本 512px 源已经偏小，现在更软 | 给 `load()` 增加尺寸参数，widget 走独立的 256/384 档；或复用 `playerCache`（512px + 裁剪） | 截屏对比 1.0.43 / 1.0.44 小组件封面；断言 `render` 取到的 bitmap 边长 ≥ 256 |
| P2-2 | `AndroidManifest.xml:67-77`；`MusicWidgetProvider.kt:47-50` | `MusicWidgetProvider` 为 `exported="true"` 且未声明 `android:permission`，任何已安装应用可发显式广播 `com.milan.music.widget.TOGGLE`，在后台静默开始/暂停播放（`handleToggle` 在队列为空时还会用缓存曲库起播） | 把 `ACTION_TOGGLE` 拆到独立的 `exported="false"` receiver，`PendingIntent` 只指向它（PendingIntent 以本应用身份投递，不受 exported 限制）；Provider 本体仅保留 `APPWIDGET_UPDATE` | `adb shell am broadcast -n com.milan.music/.widget.MusicWidgetProvider -a com.milan.music.widget.TOGGLE` 应被拒绝（`SecurityException`），而桌面按钮仍可用 |
| P2-3 | `ui/library/LibraryViewModel.kt:145-174` | `ensureLoaded()` 无 `try/finally`；`MediaStoreRepository.loadTracks()` 只捕获 `SecurityException`/`IllegalArgumentException`，其余异常（provider 崩溃、`SQLiteException` 等）会让协程异常终止，`loading` 永久为 true。批次 B 新增的"正在扫描音乐库"文案把这个隐性失败变成**永久且无重试入口**的界面（`cacheLoaded` 已置 true，无法再触发） | `try { ... } finally { loading.value = false }`，并暴露 error 状态给 UI 提供重试 | 注入一个抛 `RuntimeException` 的 fake `MediaStoreRepository`，断言 `loading` 最终为 false 且 UI 落到可重试态 |
| P2-4 | `ui/library/LibraryViewModel.kt:164-170` | 缓存对账只比较 **id 集合**（`cachedIds != freshIds`）。用户用外部工具改标题/艺人/专辑/时长后，id 不变 → `allTracks` 与磁盘缓存都不会更新，界面永远显示旧元数据；"重新扫描"也修不好 | 直接比较 `cached != fresh`（`Track` 是 data class，含 `Uri`，值相等可用）；必要时只比对可变字段以避开 `Uri` 差异 | 手动改一个音频标签 → 长按"曲库"重扫 → 标题应更新；补一条纯 JVM 单测比较逻辑 |
| P2-5 | `ui/library/HomeScreen.kt:106`、`:126-134` | `permanentlyDenied` 初值恒为 `false`。用户曾永久拒绝后重启 App，首屏仍显示"授权访问"，点击后系统不再弹窗、回调立刻返回 false，再切到"前往系统设置" —— 一次无效点击 | 用 DataStore 记一个 `permissionAsked` 标记，启动时 `!shouldShowRationale && permissionAsked` 即判定为永久拒绝；或在首屏直接并列提供"前往系统设置" | 拒绝两次 → 杀进程 → 重启，首屏应直接给设置入口 |
| P2-6 | `ui/library/HomeScreen.kt:126-134` + `:140-153` | 从设置页返回时 `viewModel.refresh()` 与 `LaunchedEffect(hasAudioPermission)` 里的 `ensureLoaded()` 同时触发，两次全量 MediaStore 查询 + 两次缓存写入并发，`allTracks` 后写覆盖先写 | 统一入口：让 `ensureLoaded` 支持 `force = true`，ON_RESUME 只调一次 | 打点统计 `loadTracks()` 调用次数，授权往返应为 1 次 |
| P2-7 | `dist/`（发布归档） | 1.0.44 的 APK/AAB 只存在于 `app/build/outputs/`，`dist/` 停在 `米兰音乐-1.0.43.apk`。该目录是 Gradle 构建输出，`clean` 或换机构建即丢；按 `dist/` 分发的渠道拿到的仍是 1.0.43 | 发布动作补一步：APK/AAB 复制到 `dist/米兰音乐-1.0.44.apk` 与 `dist/米兰音乐-1.0.44.aab`，并记录 SHA-256 | `ls dist/ \| grep 1.0.44`；`sha256sum` 与 `build/outputs` 一致 |

---

## 五、改进建议（P3）

- `play/ArtworkBitmapLoader.kt:78-81`：`release()` 只做 `shutdown()`，若 media3 在销毁过程中再次调用 `loadBitmap`，`executor.submit` 会同步抛 `RejectedExecutionException`（`BitmapLoader` 契约要求返回 failed future，而非抛出）。加 `if (executor.isShutdown) return Futures.immediateFailedFuture(...)`。
- `play/PlayerController.kt:103-105`：连接失败只清 `connection`，`pendingAction` 保留；该动作会在**下一次任意成功连接**时被重放（可能是很久以后由 widget 触发），造成"莫名其妙开始播放"。失败分支应一并清空。
- `play/PlayerController.kt:118-135`：`controller?.release()` 与 `releaseFuture(connection)` 对已完成 future 会重复释放同一实例。media3 内部有 `released` 守卫，实测未发现问题，但应在成功的回调里把 `connection` 置空（见 P0-2 改法），语义才干净。
- `widget/MusicWidgetProvider.kt:36-45`、`:70-87`：每次 `onUpdate` 新建 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 且从不取消；`goAsync()` 有 10s 上限，`render` 里的 `loadThumbnail` 在慢设备可能逼近该预算。改为类级单一 scope，并对 `render` 加超时。
- `app/build.gradle.kts:8-11`：release keystore 口令明文存放在 `local.properties`。当前无 VCS 尚可接受，但一旦 `git init` 就是事故；`storeFile` 还是 `user.home` 绝对路径，无法在另一台机器复现签名。改用 `~/.gradle/gradle.properties` 或环境变量 + 相对路径。
- `app/src/main/res/xml/data_extraction_rules.xml`：只列了 `root/file/database/sharedpref/external`，缺少 `device_*` 系列域。因 `allowBackup="false"` 与 `fullBackupContent="false"` 已生效，无实际影响，仅为完整性。

---

## 六、核实通过的项（无需改动）

| 项 | 证据 |
|---|---|
| `Bitmap.createScaledBitmap` → `bmp.scale(w,h)` 等价 | `androidx.core.graphics.scale` 的 `filter` 默认 true，与 `createScaledBitmap(..., true)` 一致 |
| `Uri.parse(x)` → `x.toUri()` 等价 | 仅多空串短路；两处来源分别是 `ContentUris.withAppendedId` 与缓存字符串，均非空 |
| `CachedTrack.folder` 删除不破坏旧缓存 | `LibraryCacheStore.kt:77` 的 `Json { ignoreUnknownKeys = true }`，且 `folder` 已无消费方 |
| 图标删除安全 | `mipmap-anydpi/ic_launcher.xml` 仅引用 `ic_launcher_background/foreground/monochrome`；5 个密度的两张 PNG 均在位；10 个旧文件已备份到 `backup/mipmap-legacy-icons-2026-10-19/` |
| `SongsList` 参数顺序调整无调用方受影响 | `HomeScreen.kt:233-244/246-257` 全部使用命名参数 |
| `flowOn(Dispatchers.Default)` 生效 | `combine(...).flowOn(...).stateIn(viewModelScope, ...)`，重活确实离开主线程 |
| 进度轮询改为播放态驱动 | `PlayerController.kt:76-78` + `:289-319`，暂停即 cancel，另有一次终值补写 |
| `dataExtractionRules` / `fullBackupContent` 声明齐备 | `AndroidManifest.xml:26-29` + `res/xml/data_extraction_rules.xml` |
| `enableOnBackInvokedCallback` 与 activity-compose 1.13.0 的 `BackHandler` 兼容 | 属性声明正确；问题在 P0-1 的 `enabled` 语义，不在回调机制 |

---

## 七、跨批次过程性风险

1. **零自动化测试**：五批改动共触及播放器生命周期、返回栈、线程调度、权限状态机、缓存对账，全部靠 `assemble` + `lint` 验证。这两个信号都只能证明"能编译、无静态告警"，与上述 2 个 P0 的失效路径完全正交。建议合并前至少补三类测试：连接状态机（JVM，可注入 executor）、权限/加载状态机（JVM + fake repository）、返回键（Compose UI test）。
2. **无版本控制**：批次 C 为此把二进制删除降级为"需确认"，批次 D 全靠 md5 手工比对。这是当前流程最大的结构性风险 —— 无法回滚、无法 diff、无法二分定位回归。
3. **文档结论应视作待验证**：发布说明把"服务可被正常销毁"写成已修复，但 P1-2 表明该结论在常规路径下不成立；"返回键不再退出应用"被 P0-1 直接推翻。批次 E 里对"注解而非修复"的取舍（ExportedService、clipToOutline）论述是扎实的，但同一份文档对行为修复的结论缺少对应强度的验证。
4. **"能打出包"被当成了"可以发版"**：本版 1.0.44 的产物核验全部通过（版本号、签名、清单、资源、R8 映射），但这两个 P0 恰好落在产物核验的盲区 —— 它们只在真机交互与生命周期时序里出现。发布门槛中应把"至少一次真机冒烟（返回键 ×2、冷启动即退、点歌即退）"与构建/lint 并列，而不是列在"待确认"栏里。

---

## 八、修复复核（2026-10-19 10:06 重新出包）

修复仅两处，未改版本号（`versionCode 45 / versionName 1.0.44` 保持不变；因 1.0.44 此前未进入 `dist/`、未上传 Play，复用版本号不会造成歧义。**若该版本已侧载或上传，必须改为 1.0.45 / versionCode 46 再出包**）。

| 项 | 文件:行 | 变更 | 静态复核 |
|---|---|---|---|
| P0-1 | `ui/player/PlayerScreen.kt:248` | `BackHandler(enabled = !isSettling.value, onBack = dismissToHome)` → `BackHandler { if (!isSettling.value) dismissToHome() }` | 注册期覆盖整段 240ms 动画；二次返回被回调内守卫吞掉，不再落到 `finish()` |
| P0-2 | `play/PlayerController.kt:64-72, 95, 107-110, 113-127, 144` | 新增 `connectEpoch` 代次令牌；回调首行校验代次，不符则丢弃；回调体整体入 `runCatching`；成功后 `connection = null`；`release()` 递增代次 | `connect()` 不再可能装配已释放实例，`controller != null` 短路失效路径被切断 |

构建与校验（本次实测）：

| 项 | 命令 | 结果 |
|---|---|---|
| Debug | `:app:assembleDebug` | BUILD SUCCESSFUL |
| Lint | `:app:lintDebug` | **`No issues found.`**（0 error / 0 warning） |
| Release | `:app:assembleRelease` | BUILD SUCCESSFUL，2.4 MB |
| AAB | `:app:bundleRelease` | BUILD SUCCESSFUL，5,905,511 B（较修复前 +563 B，证明代码确实变更） |
| R8 校验 | `mapping.txt` | `int connectEpoch -> i` 存在 → 新代码进入产物 |
| 版本 | `aapt2 dump badging` | versionCode 45 / versionName 1.0.44 |
| 签名 | `apksigner verify -v` | `Verifies`，v2 ✅ v3 ✅ |
| 归档（顺带关闭 P2-7） | `dist/` | `米兰音乐-1.0.44.apk` SHA-256 `220cd7bd…212b27`；`米兰音乐-1.0.44.aab` SHA-256 `203f8e27…fc31fd` |

**仍未验证（必须真机，构建信号无法替代）**：

1. 返回键连按两次（间隔 100ms）不退出应用。
2. 冷启动 100ms 内按 Home → 回前台点歌能正常播放（P0-2 修复的有效性）。
3. P1-2 未修：自动播放使 `onUiStopped` 的释放分支在常规路径下仍不执行，`dumpsys activity services` 仍非空。
4. P1-1/P1-3/P2-1～P2-6/P3 均未动，处置待定。
