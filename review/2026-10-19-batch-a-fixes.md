# 批次 A 执行记录：两个 P0 + 主线程排序

基线：v1.0.43（versionCode 44），审查报告见 `2026-10-18-app-review.md`。
范围：P0-1 返回键、P0-2 会话封面、P1 主线程 filter+sort。**未触碰任何 UI 视觉与交互语义**，除返回键新增关闭能力外，用户可见行为不变。

## 变更清单

| # | 文件 | 位置 | 变更 |
|---|---|---|---|
| 1 | `ui/player/PlayerScreen.kt` | 166 / 237 / 241 | 新增统一关闭路径 `dismissToHome`，接 `BackHandler`；下滑手势改为复用同一条路径 |
| 2 | `play/ArtworkBitmapLoader.kt` | 新文件 | 自定义 `androidx.media3.common.util.BitmapLoader`：API 29+ 走 `ContentResolver.loadThumbnail`，API 26–28 走 `MediaMetadataRetriever.embeddedPicture`；单线程执行 + 8MB LruCache |
| 3 | `play/PlaybackService.kt` | 24 / 83 / 86 / 106 | `MediaSession.Builder(...).setBitmapLoader(loader)`；`onDestroy` 释放 |
| 4 | `play/PlayerController.kt` | 337 | `setArtworkUri(uri)` 改为音频条目自身的 URI（原为已废弃的 `.../audio/albumart/<albumId>`），并删除 `albumArtUri()` 与两个 import |
| 5 | `ui/library/LibraryViewModel.kt` | 121 / 139 | 排序管线与收藏过滤加 `.flowOn(Dispatchers.Default)` |
| 6 | `AndroidManifest.xml` | 27 | `android:enableOnBackInvokedCallback="true"`（配 `tools:targetApi="tiramisu"` 抑制 UnusedAttribute） |

## 关键设计决定

**为什么需要自定义 BitmapLoader**：Media3 默认的 `DataSourceBitmapLoader` 通过 `DataSource` 打开 artwork URI，无法解出内嵌封面；而 `.../audio/albumart/<id>` provider 自 API 29 起废弃，实际返回空。两者叠加就是「通知栏无封面」的根因。自定义 loader 让会话侧与 UI 侧走同一条 `loadThumbnail` 路径。

**为什么返回键走动画路径**：`AnimatedVisibility` 在该处 `exit = ExitTransition.None`（下滑手势已把面板移出屏幕）。若直接 `playerOpen = false`，播放页会在一帧内消失，形成视觉断裂。因此系统返回与下滑手势共用 `dismissToHome`，先滑出再移除覆盖层。

**为什么新增 `sheetHeightPx` 状态**：滑出距离原先取自 `PointerInputScope.size.height`，该值只在手势作用域内可见；返回键在 `BoxWithConstraints` 之外触发，故由布局通过 `SideEffect` 发布测量高度。

## 验证结果

| 项 | 命令 | 结果 |
|---|---|---|
| Debug 编译打包 | `:app:assembleDebug` | ✅ BUILD SUCCESSFUL（40s），`app-debug.apk` 23 MB |
| Release 编译打包（R8） | `:app:assembleRelease` | ✅ BUILD SUCCESSFUL（2m12s），`app-release.apk` 2.4 MB |
| Lint | `:app:lintDebug` | ✅ 0 error / **33 warning**，与基线数量一致（新增的 UnusedAttribute 已用 `tools:targetApi` 消除） |
| 合并清单 | `aapt2 dump xmltree` | ✅ `enableOnBackInvokedCallback=true`、minSdk 26、targetSdk 37 |
| R8 存活验证 | 反查 `mapping.txt` / dex 字符串 | ✅ `loadThumbnail`、`No embedded artwork for`、`milan-artwork` 均在 `classes.dex` 中；类名被合并/重命名属正常裁剪 |

## 待真机确认（当前无设备连接）

1. 播放页按返回：应下滑出屏后回到列表，且不退出 App；连续快速两次返回不产生重叠动画。
2. 通知栏 / 锁屏封面：Android 12+ 应显示内嵌封面；无封面曲目应回落为默认图标而非崩溃。
3. 大库（≥3000 首）输入搜索时主线程帧：`dumpsys gfxinfo` 对比是否仍有长帧。

## 回滚方式

六个改动彼此独立：清单项删除属性即可；`PlayerController` 恢复 `albumArtUri(albumId)` 并把 `setArtworkUri` 换回即可回到旧路径（此时 `ArtworkBitmapLoader` 的 `loadThumbnail` 分支会失败，需同时移除 `setBitmapLoader`）。
