# 批次 F 执行记录：音频卸载与小组件圆角

基线：1.0.45（未发布）。本轮只做两件事：把音频卸载（offload）打开，把小组件封面圆角在 API 26–30 上修好。

两项都已编译通过、lint 未新增告警，但**都没有真机验证**——当前环境没有连接设备，见第五节的未验证清单。

## 一、总览

| # | 改动 | 文件 | 性质 |
|---|---|---|---|
| 1 | 启用音频卸载 + 强制 gapless | `play/PlaybackService.kt` | 内核配置 |
| 2 | 封面圆角改为进程内预裁 | `widget/MusicWidgetProvider.kt` | 缺陷修复 |

## 二、音频卸载：API 已经不是记忆里的那个

最初按旧版文档写了 `DefaultRenderersFactory.setEnableAudioOffload(true)`，编译直接失败：

```
e: Unresolved reference 'setEnableAudioOffload' on receiver of type 'DefaultRenderersFactory'
```

反编译 `media3-exoplayer-1.11.1.aar` 确认，该类只剩三个音频相关成员：

```
public final DefaultRenderersFactory setEnableAudioFloatOutput(boolean)
protected void buildAudioRenderers(...)
protected AudioSink buildAudioSink(Context, boolean, boolean)
```

`setEnableAudioOffload` 在 1.11 已被移除。offload 现在**只由播放器的 `TrackSelectionParameters` 驱动**，
`androidx.media3.common.TrackSelectionParameters$AudioOffloadPreferences` 提供了三个档位
（`DISABLED` / `ENABLED` / `REQUIRED`）和三个约束开关：

```
public Builder setAudioOffloadMode(int)
public Builder setIsGaplessSupportRequired(boolean)
public Builder setIsSpeedChangeSupportRequired(boolean)
```

最终实现：

```kotlin
player.trackSelectionParameters = player.trackSelectionParameters
    .buildUpon()
    .setAudioOffloadPreferences(
        AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
            .setIsGaplessSupportRequired(true)
            .build(),
    )
    .build()
```

### 为什么用 ENABLED 而不是 REQUIRED

`REQUIRED` 会在设备不支持卸载时拒绝播放该格式，对本地曲库是灾难性的（用户点歌没声音）。
`ENABLED` 是「能卸就走硬件，不能就回软件 float 通路」，与已有的 `setEnableAudioFloatOutput(true)`
自然衔接：卸载路径绕过 float 参数（格式由硬件决定），软件路径保留 24-bit 精度。

### 两个必须记住的约束

1. **gapless 要显式声明**。不写 `setIsGaplessSupportRequired(true)`，硬件可能为了冲刷缓冲在曲间插入
   静音帧——连续专辑（现场、古典）会听见缝。
2. **卸载绕过 ExoPlayer 的音频后处理**。将来若要加均衡器或变速，必须在开启时把模式切回 `DISABLED`，
   否则那两个功能会"看起来开了但没效果"。音量不受影响：`Player.volume` 走 AudioTrack，硬件路径支持。

### 收益的上限在哪里

`PlaybackService` 里同时设置了 `setWakeMode(C.WAKE_MODE_LOCAL)`（是为了绕开部分 OEM 在 doze 时
stall 解码线程的问题）。这个 partial wake lock **不允许 CPU 深睡**，所以 offload 的收益是
"CPU 不再逐帧解码"而不是"CPU 进入低功耗态"。两者同时存在并不冲突，但对外描述功耗时要按前者说，
不能照搬社区博客里"200–300 mA → 50–100 mA"的数字——那是无 wake lock 场景的测量。

## 三、小组件圆角：为什么没有用 Glance 重写

上一轮的技术建议里我把"Glance 1.2 重写小组件"列进了第一梯队，这是错的，本轮修正。

Glance 底层仍然是 `RemoteViews`（Android 16 起才在部分场景换 Remote Compose）。API 26–30 上
`android:clipToOutline` 作为布局属性根本不生效，Glance 的 `cornerRadius` 同样要落到
`setViewOutlinePreferredRadius`（API 31+）这条路上。结论：**重写 150 行换不来 26–30 的圆角**。

真正的解法是把圆角做在数据上，而不是做在视图上：在 app 进程内把封面位图裁成圆角，
再通过 Binder 传给桌面进程。这样不依赖任何 RemoteViews 能力，全版本行为一致。

半径不能写死像素。封面 `match_parent` 铺满卡片，所以视觉半径与位图宽度的比例是
`radiusDp / cardWidthDp`；卡片实际宽度用 `AppWidgetManager.getAppWidgetOptions()` 读，
读不到时退回 `music_widget_info.xml` 里声明的 110dp：

```kotlin
private fun cardCornerRadiusPx(manager: AppWidgetManager, widgetId: Int): Float {
    val widthDp = manager.getAppWidgetOptions(widgetId)
        .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
    val referenceWidthDp = (widthDp.takeIf { it > 0 } ?: FALLBACK_CARD_WIDTH_DP).toFloat()
    return (CARD_RADIUS_DP / referenceWidthDp * COVER_SIZE).coerceIn(0f, COVER_SIZE / 2f)
}
```

常量 `CARD_RADIUS_DP = 26f` 必须与 `drawable/widget_card_bg.xml` 的 `corners radius` 保持一致，
`FALLBACK_CARD_WIDTH_DP = 110` 必须与 `xml/music_widget_info.xml` 的 `minWidth` 保持一致——两处都写了注释。

代价：每次切歌多分配一个 256×256 ARGB 位图（约 256 KB），在 IO 协程内完成，不阻塞主线程。

## 四、验证

- `:app:compileDebugKotlin` → 通过
- `:app:assembleDebug` → `BUILD SUCCESSFUL`
- `:app:lintDebug` → 5 条，全部是既有的版本提示（Gradle wrapper、AGP、Kotlin 三件套），
  本轮新增的 `UseKtx`（`Bitmap.createBitmap`）已改用 `androidx.core.graphics.createBitmap` 清零
- 产物：`app/build/outputs/apk/debug/app-debug.apk` 23 MB

## 五、未验证清单

以下全部需要真机，代码推理不能替代：

1. **offload 是否真的生效**。`AUDIO_OFFLOAD_MODE_ENABLED` 只是"允许"，最终由
   `AudioTrack` 的 direct/offload 支持决定。验证方式：
   ```
   adb shell dumpsys media.audio_flinger | grep -i offload
   adb logcat -s ExoPlayerImplInternal AudioSink
   ```
   期望在播放 FLAC/MP3 且息屏时看到 offload 线程，而不是 `AudioTrack` 软件混音。
2. **息屏功耗实际下降多少**。社区数据（200–300 mA → 50–100 mA）来自第三方博客且是无 wake lock 场景，
   本应用持有 partial wake lock，预期降幅明显小于该数字，必须自己测量后再写进任何说明。
3. **offload 下的 seek 延迟**。硬件缓冲通常让 seek 慢 100–200 ms，音乐场景可接受，但要在 UI 上确认
   拖动进度条没有"回弹"错觉。
4. **gapless 是否真的无缝**。需要用一张连续轨道的专辑（现场/古典）实测曲间是否有缝；
   软件路径与卸载路径要分别验。
5. **小组件圆角比例**。`OPTION_APPWIDGET_MIN_WIDTH` 在部分启动器上返回的是 cell 尺寸而非实际卡片宽度，
   比例会有几 dp 偏差。因为角落露出的是卡片背景（半透明黑）而非白边，预计不可见，但要在
   不同启动器（Pixel Launcher / 小米 / 三星）上各看一眼。
6. **API 26–30 的圆角**。本次改动的目标版本，当前没有对应设备。

## 六、顺带说明

工作区里还有两处与本批次无关的未提交改动，本轮没有触碰：

- `app/build.gradle.kts`：`versionCode 45→46`、`versionName 1.0.44→1.0.45`
- `ui/library/HomeScreen.kt`：搜索框移除了空态提示文案、图标尺寸与无障碍描述调整

这两处是否要一起发布，需要单独确认。
