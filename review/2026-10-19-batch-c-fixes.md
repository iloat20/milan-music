# 批次 C 执行记录：发布规范、图标与死代码

基线：v1.0.43 + 批次 A/B。范围：死代码清理、依赖裁剪、单色图标层、备份规则、签名方案 v3/v4、AAB 产物。**用户可见行为零变更**（唯一视觉差异是 Android 13+ 的主题图标，且仅在用户开启「主题图标」时可见）。

## 一、死代码清理（约 95 行）

每一项删除前都用全工程 `grep` 复核过引用数，结果如下：

| 符号 | 文件 | 复核结果 |
|---|---|---|
| `LocalArtworkRepository` | `ui/LocalProviders.kt`（整文件删除） | 只有 `MilanApp.kt` 的 `provides`，零 `current` 消费者 |
| `MilanApp` 的 `CompositionLocalProvider` 包裹层 | `ui/MilanApp.kt` | 同上，随之去掉一层缩进 |
| `LibrarySection` | `ui/library/LibrarySection.kt`（整文件删除） | 定义即全部引用 |
| `Track.folder` + `folderNameOf()` + `MediaStore.Audio.Media.DATA` 列 | `Track.kt` / `MediaStoreRepository.kt` / `LibraryCacheStore.kt` | 字段只写不读；顺带移除了报告里 P2 的**弃用列查询** |
| `ArtworkRepository.invalidate()` | `ArtworkRepository.kt` | 零调用（缓存随进程存活，无需手动失效） |
| `PlayerController.play()` / `pause()` | `PlayerController.kt` | 零调用（对外只有 `togglePlayPause`） |
| `PlayerController.toggleShuffle()` / `cyclePlayMode()` / `cycleRepeatMode()` | `PlayerController.kt` | 零调用；播放页自述「模式按钮已从 UI 隐藏」，这三条是隐藏后的残留 |
| `PlayerUiState.shuffleEnabled` / `repeatMode` | `PlayerController.kt` | 上一条删除后成为纯死字段，`refresh()` 同步简化 |
| `LibraryUiState.isEmptyLibrary` | `LibraryViewModel.kt` | 零读取（UI 用的是 `tracks.isEmpty()` + 新增的 `loading`） |

`LibraryCacheStore.CachedTrack` 的 `folder` 字段删除是安全的：`Json { ignoreUnknownKeys = true }` 已开启，旧缓存里的多余键会被忽略，缺失键有默认值。

## 二、依赖裁剪

移除 `androidx.navigation:navigation-compose`（源码零 `NavHost`/`navigation` 引用，R8 早已整体裁掉，留着只是拖慢构建图）与 `dev.chrisbanes.haze:haze-blur-materials`（零 `HazeMaterials` 引用）。`haze` 与 `haze-blur` 保留：`GlassSurface` 实际使用 `dev.chrisbanes.haze.blur.hazeBlur`。

## 三、图标

- 新增 `drawable/ic_launcher_monochrome.xml`：黑胶环形 + 中心点的剪影，用 `fillType="evenOdd"` 造出环与点之间的透明带（不是靠背景色伪装）。
- 两个自适应图标加入 `<monochrome>` 层，Android 13+ 主题图标不再由系统从彩色前景自动推导（那样三层实心路径会被压成一个实心圆）。
- `mipmap-anydpi-v26/` 合并进 `mipmap-anydpi/`：minSdk 已是 26，`-v26` 限定符多余（lint `ObsoleteSdkInt`）。
- 删除 `drawable/ic_launcher_foreground.xml` 与 `values/colors.xml`：前者被 mipmap 下的同名 PNG 遮蔽从未生效，后者两个颜色（`ic_launcher_background`、`glass_scrim`）全工程零引用。

## 四、备份规则

`allowBackup="false"` 保留，另加：
- `android:dataExtractionRules="@xml/data_extraction_rules"`：Android 12+ 的云备份与设备迁移全部按域排除（曲库是 MediaStore 的派生缓存，换机无意义；偏好只有排序模式和收藏 id）。
- `android:fullBackupContent="false"`：覆盖 API 26–30，同时消除 lint 关于「低版本仍需声明」的提示。

## 五、签名与产物

`enableV3Signing` / `enableV4Signing` 开启。实测 `apksigner verify -v`：v2 ✅、v3 ✅、v1 ❌（minSdk 26 无需）、v4 在 APK 本体上显示 false 属正常 —— v4 签名写在同一目录的 `app-release.apk.idsig`（26 KB），供增量安装使用。

`bundleRelease` 已跑通：`app/build/outputs/bundle/release/app-release.aab`，5.7 MB / 212 个条目，含 `BundleConfig.pb`。这是 Play 要求的发布物，此前仓库里只有 APK。

## 六、验证

| 项 | 结果 |
|---|---|
| `:app:assembleDebug` | ✅ BUILD SUCCESSFUL |
| `:app:assembleRelease` | ✅ 2.4 MB |
| `:app:bundleRelease` | ✅ 5.7 MB AAB |
| `:app:lintDebug` | ✅ 0 error / **26 warning**（批次 A/B 基线 33，本批净减 7） |
| 合并清单（aapt2） | ✅ `dataExtractionRules` / `fullBackupContent=false` / `enableOnBackInvokedCallback=true` 齐备 |
| 资源打包（aapt2） | ✅ `drawable/ic_launcher_monochrome 0x7f040007` 已进 APK |
| 残留引用 | ✅ `LocalArtworkRepository` / `LibrarySection` / `.folder` 全工程零命中 |

净减的 7 条 lint：`MonochromeLauncherIcon` ×2、`UnusedResources` ×3、`ObsoleteSdkInt` ×1、`DataExtractionRules` ×1。

## 七、本批明确不做（附理由）

1. **Haze 采样源移出 LazyColumn**：当前 `AmbientBackdrop` 与 `LazyColumn` 同时是 `hazeSource`，玻璃面板因此能模糊「滚动中的列表内容」—— 这是设计意图而非疏漏。改成只采样固定背景层会改变玻璃质感，且是否真的掉帧需要真机 `gfxinfo` 数据。无设备时改它属于用性能名义做视觉变更，故搁置。
2. **文案资源化**：约 20 处硬编码中文集中在 Compose 里，而 lint 的 `HardcodedText` 只作用于 XML（当前仅 widget 布局 1 处命中）。在无多语言需求的前提下，这是一次跨 8 个文件、零行为收益的纯改动，风险收益比不成立。
3. **删除旧版 PNG 图标**：`mipmap-*dpi/ic_launcher.png` 与 `ic_launcher_round.png` 被 anydpi 的 adaptive icon 完全遮蔽，删掉可再消 15 条 lint 告警。但**仓库没有版本控制**，二进制美术资源删除不可逆，需你确认后再动。

## 八、遗留观察（本批未改，需真机确认）

`PlayerController.release()` 在全工程无调用者：`AppContainer` 持有的 `MediaController` 属于进程级单例，从头到尾不解绑。若播放停止后 `MediaSessionService` 仍因这个绑定而不释放，会在后台留一个空转的服务。这需要真机观察 `dumpsys activity services com.milan.music` 才能定性，因此本批保留 `release()` 不动，避免在没有证据的情况下改生命周期。
