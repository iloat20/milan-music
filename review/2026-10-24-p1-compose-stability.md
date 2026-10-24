# P1-5：Compose 稳定性报告与结论（2026-10-24）

对应 `2026-10-24-latest-tech-adaptation.md` 的 P1-5。本轮只做"打开报告 + 读报告 +
决定要不要加 `stabilityConfigurationFile`"，没有改任何 Composable 的签名或语义。

## 结论

- 已在 `app/build.gradle.kts` 打开编译器报告（`composeCompiler { reportsDestination /
  metricsDestination }`），产物在 `app/build/compose_compiler/`。
- **没有添加 `stabilityConfigurationFile`。**
- 报告里 21 个 unstable 类，其中 20 个是"本来就该可变"的持有者：Repository、Controller、
  ViewModel、Service、`AppContainer`、`MainActivity`/`Application`（持有 `container`）。
  给这些类标 stable 会让 Compose 跳过真正的状态变化，是 P1-5 明确禁止的方向。
- 唯一"确实不可变、编译器看不出来"的是 `data.model.Track`：12 个字段里 11 个 stable，
  只有 `uri: android.net.Uri` 不稳定 —— `Uri` 是框架类，没有 Compose 稳定性注解，但
  AOSP 文档明确它是 immutable URI reference。
- 即便如此，把 `Track` 标 stable 也换不来可测量的收益（见下节），而 P1-5 要求"确认配置
  有收益"再保留，所以本轮不加，留到真机帧率对比之后再决定。

## 为什么 `Track` 不值得单独加配置

接收 `Track` 的 Composable 没有一个只收 `Track`：

| Composable | 除 `Track` 之外的不稳定参数 |
| --- | --- |
| `TrackRow(track, …)` | `ArtworkRepository`（LruCache，可变） |
| `SongsList(tracks: List<Track>, …)` | `ArtworkRepository`、`Set<Long>`（`List`/`Set` 本身也不稳定） |
| `PlayerTransport(track, …)` | `StateFlow<PlaybackProgress>` |
| `MiniPlayer(state: PlayerUiState, …)` | `ArtworkRepository`、`HazeState` |
| `PlayerScreen(viewModel, player, artwork, …)` | `PlayerViewModel`、`PlayerController`、`ArtworkRepository` |

只要同一行里还有一个不稳定参数，函数就仍然无法按值跳过；把 `Track` 标 stable 只是把
`===` 换成 `equals`。而项目已经启用 strong skipping（Kotlin 2.x Compose 编译器默认），
不稳定参数本来就用 `===` 比较：曲库列表在两次重组之间是同一批 `Track` 实例，只有重扫
才会换对象。换句话说，当前路径上的跳过行为已经是对的，配置只会增加一个需要长期维护的
"编译器请相信我"清单。

## 报告内容摘要

`app-classes.txt` 里的 unstable 类（21 个）：

```
MainActivity, MilanMusicApp, ArtworkRepository, LibraryCacheStore, LibraryRevision,
MediaStoreRepository, MemoCache, Track, SettingsStore, UserDataStore, AppContainer,
ArtworkBitmapLoader, ByteCappedLoadControl, LibraryTree, PlaybackService,
PlaybackSnapshotStore, PlaybackTrackIndex, PlayerController, LibraryViewModel,
PlayerViewModel, MusicWidgetProvider
```

`Track` 的字段明细：

```
stable   id, title, artist, album, albumId, duration, sizeBytes,
         year, trackNumber, dateAdded, dateModified, albumArtist
unstable uri: Uri
```

稳定的值类型（可直接用于"哪些类不用管"的对照）：`BitmapBudget`、`IndexStamp`、
`PlaybackProgress`、`PlayerPalette`、`BlurStop`。

`PlayerUiState` 目前是 `Runtime(Track)`，`Track` 变 stable 后它会跟着变 stable；但它的
消费方 `MiniPlayer` 还被 `ArtworkRepository` / `HazeState` 挡住，所以这条链上没有收益。

## 复现

```powershell
cd milan-music
.\gradlew.bat :app:compileDebugKotlin --rerun
```

产物：

- `app/build/compose_compiler/app-classes.txt`：每个类的字段稳定性与判定原因
- `app/build/compose_compiler/app-composables.txt`：每个 Composable 是否 restartable /
  skippable，以及每个参数的稳定性
- `app/build/compose_compiler/app-composables.csv`：同上，便于脚本统计

报告只在构建目录，不进 APK，也不参与版本门禁。

## 真机轮要补的验证

1. `adb shell dumpsys gfxinfo com.milan.music` 清空后滚动 5000 首歌曲列表，记录 janky
   frames / 90th percentile，作为基线。
2. 若要试 `stabilityConfigurationFile`，只加 `com.milan.music.data.model.Track` 一项，
   重跑同一段滚动，对比帧数据；没有改善就删掉配置。
3. 不要给 `ArtworkRepository`、`PlayerController`、`*ViewModel`、`AppContainer` 加任何
   稳定性标注：它们持有缓存、协程作用域或 `MutableState`，跳过重组就是丢更新。
