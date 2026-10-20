# 批次 K：动效专项（迷你播放器 / 列表 / 播放页）

基线：1.0.50 之后、封面轮播改动尚未提交。

## 一、参考对象与取舍

看了几类开源播放器的动效做法，只取和当前"水墨玻璃 + 暖白/淡金"气质相容的部分：

| 项目 | 可借鉴的点 |
|---|---|
| ViMusic | 迷你播放器与全屏播放页的分层关系；切歌时标题/封面的短距离交接 |
| InnerTune | Material 3 的按压反馈、播放/暂停图标的形变式切换 |
| Retro Music | 列表排序/过滤时的 item 位移动画，当前曲目的颜色过渡 |
| Auxio | 克制：没有常驻的装饰性动画，动效只服务状态变化 |
| Lune | Material 3 Expressive 的节奏感；共享元素只在其进场体系内成立 |

没有引入的：

- **旋转黑胶 / 频谱可视化**：需要常驻动画或音频 FFT，和本项目已有的功耗审计结论冲突。
- **共享元素过渡**：与"整页上滑 + 手势下滑关闭"的现有进场冲突，批次 G 已论证，仍维持不做。
- **迷你播放器进度线**：会让 `PlayerController.progress` 在首页常驻一个订阅者，打破"只有播放页订阅才启动 500 ms ticker"的省电约束，本批不动。

## 二、动效节奏

新增 `ui/theme/Motion.kt`，把时长和曲线集中：

| Token | 值 | 用途 |
|---|---|---|
| `FastMs` | 140 ms | 按压、图标切换、淡出 |
| `MediumMs` | 240 ms | 列表/文字/标签切换 |
| `SlowMs` | 420 ms | 预留 |
| `AmbientMs` | 650 ms | 播放页主色与背景图 |
| `Press` | 弹簧，damping 0.72 | 按压缩放 |
| `Settle` | 弹簧，damping 0.85 | 手势回弹（预留） |

每帧只改 `graphicsLayer` 的值，不触发布局；颜色/透明度动画只放在最小的 composable 里。

## 三、落地内容

| 文件 | 动效 |
|---|---|
| `ui/components/AnimatedPlayPauseIcon.kt` | 播放/暂停图标：旧图标缩小淡出、新图标回弹放大淡入，尺寸不变 |
| `ui/util/Interactions.kt` | `noRippleClickable` / `noRippleCombinedClickable` 增加可选 `pressScale`；按压只缩放图层，命中区域不变 |
| `ui/components/MiniPlayer.kt` | 整卡按压缩放；标题/艺人切歌时上下交接；播放/下一首按压缩放；封面换图淡入 |
| `ui/library/HomeScreen.kt` | 曲库/喜欢切换时搜索栏收放 + 列表淡入/短滑；迷你播放器出现/消失；排序菜单从右上角展开；搜索清除按钮展开/收起；底部 Tab 的图标缩放、颜色过渡与柔和胶囊底 |
| `ui/components/TrackRow.kt` | 当前曲目背景与标题色渐变；收藏心形缩放弹入 |
| `ui/library/LibraryLists.kt` | `Modifier.animateItem()`：排序变化时行位移，搜索过滤时行淡入淡出 |
| `ui/components/CoverArt.kt` | 占位图→真实封面淡入；轮播跨曲目由调用方 `key(track.id)` 直接换图，保证落位同帧 |
| `ui/player/PlayerScreen.kt` | 标题/艺人切歌交接；收藏心形弹入；播放控制按压反馈；进度条拖动圆点收放；封面进场 0.94→1；背景主色 650 ms 渐变；背景图 Crossfade 且只做一次模糊 |

## 四、几个关键实现

### 1. 封面轮播不能做 Crossfade

轮播落位依赖"手指下的那张图在同一帧变成当前页"。如果 `CoverArt` 在 `track.id` 变化时做淡入，slot 0 会从旧封面淡到新封面，破坏像素连续性。

处理：`CoverSlot` 用 `key(track.id)` 重建 `CoverArt`，同曲目内的"占位→封面"仍然淡入，跨曲目直接以缓存图起画。`ArtworkRepository.loadPlayerArtwork` 的缓存注释（相邻页预解码）继续成立。

### 2. 播放页背景保留旧图

`backdrop` 与 `dominant` 不再按 `track.id` 重置。切歌时旧背景继续显示，新 96 px 缩略图解码完成后由 `Crossfade` 接管；主色则在 `PlayerBackdrop` 内部做 650 ms 颜色动画。

模糊从每张 `Image` 移到 `Crossfade` 外层：过渡期间只做一次全屏模糊，而不是新旧两张各一次。

### 3. 进度 ticker 没有被扩大

`PlayerController` 的注释明确记录了：`progress` 只在播放页被订阅，首页常驻订阅会让 500 ms ticker 在亮屏期间一直跑。本次没有给迷你播放器加进度线，保持这个约束。

### 4. Tab 切换与 Haze

`AnimatedContent` 在过渡期间会同时持有两个 `SongsList`，每个都挂 `hazeSource`。项目里 `AmbientBackdrop` 与 `SongsList` 本来就同时挂同一个 `HazeState`，多 source 是 Haze 支持的；但真机上仍需确认过渡期间玻璃面板没有闪动。

搜索栏只在曲库页出现，若直接 `if` 掉，列表会在过渡开始前先向上跳一行。这里同样用 `AnimatedVisibility` 收放高度，让列表位移由父布局连续完成，而不是瞬移。

## 五、验证

- `:app:compileDebugKotlin` → BUILD SUCCESSFUL
- `:app:assembleDebug` → BUILD SUCCESSFUL
- `:app:lintDebug` → 0 errors，7 条既有 warning（2 条 `InlinedApi`、4 条版本提示、1 条 ChromeOS ABI）；本次新增代码无 lint 告警

## 六、未验证 / 待真机

1. 迷你播放器整卡按压与内层播放/下一首按钮的嵌套点击是否互不干扰。
2. 快速连续滑动封面时，`key(track.id)` 重建与 `coverOffsetX.snapTo(0)` 是否仍然无闪帧。
3. Tab 切换 240 ms 内两个 `LazyColumn` 同时作为 Haze source 是否稳定。
4. 播放页背景双图 Crossfade + 一次模糊在中低端机型上的掉帧情况。
5. 列表 `animateItem` 在 1000+ 首、快速排序切换时的流畅度。
6. 系统"减少动画"（Remove animations）开启时的表现：Compose 的 Transition/Animatable 一般会通过 `MotionDurationScale` 感知系统时长缩放，但 `PlayingBars` 这类无限动画与背景 Crossfade 的实际降级效果仍需真机确认；若需严格适配，可在应用层读取 `ANIMATOR_DURATION_SCALE` 做统一降级。

## 七、后续可选

- 迷你播放器底部 1.5 dp 进度线：需要把 `progress` 订阅扩大到首页，或单独做一个低频（2 s）的轻量 ticker；前者违背现有省电约束。
- 播放页返回时的共享元素：只有把进场改成"背景淡入 + 封面飞行"才成立，属于视觉体系重做。
- 歌词页逐行高亮/滚动：当前没有歌词功能，先有数据再接动画。
