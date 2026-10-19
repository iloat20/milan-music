# 批次 B 执行记录：运行时开销与状态诚实性

基线：v1.0.43 + 批次 A（`2026-10-19-batch-a-fixes.md`）。
范围：进度轮询、列表封面解码尺寸、首启 loading 态、权限永久拒绝兜底。**未改动任何既有视觉风格**；新增的只有一句加载文案与一个「前往系统设置」按钮（仅在权限被永久拒绝时出现）。

## 变更清单

| # | 文件 | 位置 | 变更 |
|---|---|---|---|
| 1 | `play/PlayerController.kt` | 78 / 313 / 331 | 进度轮询由「连接即启动、永不停止」改为 `EVENT_IS_PLAYING_CHANGED` 驱动：播放时启动、暂停时取消并补一次终值 |
| 2 | `data/media/ArtworkRepository.kt` | 67 / 94 / 202-204 | 列表封面解码 `512px → 192px`，播放页封面独立保留 512px（拆为 `LIST_THUMB_SIZE` / `PLAYER_THUMB_SIZE`） |
| 3 | `ui/library/LibraryLists.kt` | 33-52 | `SongsList` 新增 `isLoading`：空库 + 扫描中显示「正在扫描音乐库」，不再谎报「这里还没有歌曲」 |
| 4 | `ui/library/HomeScreen.kt` | 106-134 / 157 / 530-580 | 永久拒绝检测（`shouldShowRequestPermissionRationale`）+ `ON_RESUME` 回读授权 + 门禁文案与按钮切换为「前往系统设置」 |
| 5 | `play/ArtworkBitmapLoader.kt` | 106 | `Bitmap.createScaledBitmap` → `Bitmap.scale`（core-ktx），消除新增 lint 告警 |

## 量化收益

**列表封面**：52 dp 行在 3.5x 密度下需要 182 px，此前按 512 px 解码 —— 每张 `512²×4 ≈ 1.0 MB`，24 MB 的 LRU 只装得下约 24 张专辑；改为 192 px 后每张 `192²×4 ≈ 144 KB`，同尺寸 LRU 覆盖约 166 张，且 `loadThumbnail` 的解码与磁盘缩略图缓存成本同步下降约 7 倍。播放页仍用 512 px（封面按屏宽满幅渲染），两者互不挤占缓存。

**进度轮询**：原先 `startProgressLoop()` 在 `connect()` 后就 `while(isActive)` 永跑，暂停、后台都一样每 500 ms 唤醒一次；现在只在 `isPlaying` 为真时存在协程，暂停即取消。

**首启观感**：`loading` 字段此前已算出但 UI 从未读取，MediaStore 全量扫描期间列表直接渲染空状态文案；现在文案与真实状态一致。

## 复核后决定不改的项

**`Modifier.blur(48.dp)` 版本分支**（`PlayerScreen.kt:263`）：审查报告列为 P1，实测复核后保留原样。原因有两点：Compose 的 `Modifier.blur` 在 API 31 以下本身就是 no-op，不存在「低版本白跑一次」的开销；在 31+ 上该图层内容只随曲目切换而失效，滑动手势走的是 `graphicsLayer.translationY`（不触发重绘），因此没有逐帧代价。删掉反而会让 96 px 背景图在 31+ 上丢失唯一的平滑处理。这条属于「审计建议本身需实测验证」的典型例子。

## 验证结果

| 项 | 命令 | 结果 |
|---|---|---|
| Debug | `:app:assembleDebug` | ✅ BUILD SUCCESSFUL，`app-debug.apk` 23 MB |
| Release（R8） | `:app:assembleRelease` | ✅ BUILD SUCCESSFUL，`app-release.apk` 2.4 MB |
| Lint | `:app:lintDebug` | ✅ 0 error / **33 warning**，与批次 A 基线完全一致（本次新增的 KTX 提示已就地修掉） |

## 待真机确认

1. 暂停后 `dumpsys gfxinfo` 中 500 ms 周期唤醒应消失；恢复播放时进度条应立即续走（而非等到下一 tick）。
2. 列表快速滚动时封面清晰度（192 px 在 3x 屏 52 dp 下应为 1:1.03，肉眼无可辨差异）。
3. 权限首次拒绝 → 再次点击「授权访问」应切换为「前往系统设置」；从设置页返回后曲库应自动加载，无需重启。
