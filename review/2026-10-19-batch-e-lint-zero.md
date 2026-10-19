# 批次 E 执行记录：lint 全量清零

基线：v1.0.43 + 批次 A/B/C/D。目标：把剩下的 11 条 warning 全部消掉。

结果：`:app:lintDebug` 报告首行为 **`No issues found.`**（0 error / 0 warning）。

## 一、总览

| # | 告警 | 处理方式 | 性质 |
|---|---|---|---|
| 1 | `UseKtx` — `Bitmap.createScaledBitmap`（`ArtworkRepository.kt:124`） | 改 `bitmap.scale(w, h)` | 真修 |
| 2 | `UseKtx` — `Bitmap.createScaledBitmap`（`MusicWidgetProvider.kt:103`） | 同上 | 真修 |
| 3 | `UseKtx` — `Uri.parse`（`ArtworkRepository.kt:194`） | 改 `"...".toUri()` | 真修 |
| 4 | `UseKtx` — `Uri.parse`（`LibraryCacheStore.kt:38`） | 改 `uri.toUri()` | 真修 |
| 5 | `ModifierParameter`（`LibraryLists.kt:32`） | 调整参数顺序 | 真修 |
| 6 | `HardcodedText`（`widget_music.xml:54`） | 抽到 `@string/widget_play_pause` | 真修 |
| 7 | `GradleDependency` — core-ktx | 1.17.0 → **1.19.1** | 真修 |
| 8 | `GradleDependency` — activity-compose | 1.12.2 → **1.13.0** | 真修 |
| 9 | `GradleDependency` — kotlinx-serialization-json | 1.10.0 → **1.11.0** | 真修 |
| 10 | `ExportedService`（`PlaybackService`） | `tools:ignore` + 理由注释 | **注解** |
| 11 | `UnusedAttribute`（`clipToOutline`） | `tools:targetApi="s"` + 理由注释 | **注解** |

9 条真修，2 条注解。下面解释这两条为什么只能注解。

## 二、KTX 替换（4 条）

两处 `Bitmap.createScaledBitmap(bmp, w, h, true)` → `bmp.scale(w, h)`。`androidx.core.graphics.scale` 的实现就是
`Bitmap.createScaledBitmap(this, width, height, true)`，`filter` 硬编码为 true，与原调用完全等价。

两处 `Uri.parse(x)` → `x.toUri()`。`androidx.core.net.toUri` 的实现是
`if (this.isEmpty()) Uri.EMPTY else Uri.parse(this)` —— 只多了一个空串短路，非空串行为一致。缓存里的
`uri` 来自 `ContentUris.withAppendedId`，不会是空串。

顺带删掉了 `MusicWidgetProvider` 里已经没人用的 `import android.graphics.Bitmap`。

## 三、ModifierParameter

`SongsList` 原本是 `isLoading: Boolean = false` 在前、`modifier: Modifier = Modifier` 在后。Compose 约定
`modifier` 必须是第一个可选参数，这样调用方可以第一个覆盖布局修饰符。两者调换位置即可；两个调用点
（`HomeScreen.kt:233/246`）全部用命名参数，不受影响。

## 四、文案资源化（1 条）

`widget_music.xml` 的 `android:contentDescription="播放或暂停"` 抽成 `@string/widget_play_pause`。

注意这是**唯一**一条 XML 硬编码：Compose 里的中文（EmptyState 文案、按钮 label 等约 20 处）lint 的
`HardcodedText` 根本不检查，所以「清零」不等于「全应用已资源化」。

## 五、依赖升级（3 条）

`core-ktx 1.17.0 → 1.19.1`、`activity-compose 1.12.2 → 1.13.0`、`kotlinx-serialization-json 1.10.0 → 1.11.0`。

新版本本来就在本地 Gradle 缓存里，所以全程 `--offline` 构建，没有新增外部依赖。解析结果实测：

```
+--- androidx.core:core-ktx:1.19.1
+--- androidx.activity:activity-compose:1.13.0
+--- org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0
```

三者都是向后兼容的小版本，`ComponentActivity`、`enableEdgeToEdge`、`Json { ignoreUnknownKeys }` 的用法均无变化。

## 六、两条只能注解的

### ExportedService

media3 的 `MediaSessionService` **必须** `exported="true"`：系统要靠它从媒体通知、蓝牙按键、Android Auto
把服务拉起来，而这些调用方不持有任何本 App 可以要求的权限。加 `android:permission` 会让通知栏和车机
直接连不上，属于「为了消警告而弄坏功能」。

真正的授权控制点在上一层：`MediaSession` 决定接入的 controller 能发哪些传输命令。所以这里用
`tools:ignore="ExportedService"` 并写明理由。

想再收紧一档，可以覆盖 `MediaSession.Callback.onConnect`，只放行系统媒体按钮与自家包名。没做，是因为
它会影响 Android Auto / Wear 的接入判定，而当前没有设备可以验证——不能只凭代码推理就把控制收窄。

### clipToOutline

`android:clipToOutline` 作为**布局属性**是 API 31 才有的（`View.setClipToOutline()` 方法本身 API 21 就有）。
26–30 上这个属性被 inflater 直接忽略，所以卡片的圆角只对封面内容不生效——这是批次 A 报告里就列出的
P2 缺陷，本批没有改变它的实际行为，只是把「已知且有意」标注出来（`tools:targetApi="s"`）。

要在 26–30 上真正圆角裁剪，只能走 `RemoteViews` 的反射通道（`setBoolean(..., "setClipToOutline", true)`）。
但这行代码是在**桌面进程**里执行的：方法名若不在允许列表内，抛出的 `ActionException` 会让整个小组件
变成「加载失败」而不是降级。当前环境取不到框架源码（本地无 sources、外网 raw 源被拦），无法确认
`setClipToOutline` 是否在白名单里——**拿一个无法验证的猜测去换一个可能整块小组件挂掉的风险，不划算**。
等有设备时用 `adb shell dumpsys appwidget` + 真机 26–30 版本验证后可以再改。

## 七、验证

- `:app:lintDebug` → `No issues found.`（0 error / 0 warning），11 → 0
- `:app:assembleDebug` / `:app:assembleRelease` / `:app:bundleRelease` 全部 `BUILD SUCCESSFUL`
- `:app:dependencies --configuration releaseRuntimeClasspath` 确认三个新版本被真实解析
- 产物：`app-release.apk` 2.4 MB、`app-release.aab` 5.7 MB

## 八、清零之后还剩什么

lint 是静态规则集，清零只说明「没有触发这 200 多条规则」。以下仍然存在，且都不在 lint 的视野里：

1. Android 8–11 上小组件圆角失效（见上一节）
2. Compose 里约 20 处硬编码中文（`HardcodedText` 不检查 Compose）
3. 批次 A/B/D 累计 10 项待真机确认（返回键动画、通知封面、暂停唤醒、首播延迟、服务存活等）
4. Haze 采样源是否真的掉帧，需要 `gfxinfo` 实测
