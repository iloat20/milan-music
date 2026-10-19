# 扫描 / 元数据 / 封面的 IO 与内存：逐条核实

**日期**：2026-10-19
**范围**：只读核实 + 量化，未改动代码
**对象**：`MediaStoreRepository`、`ArtworkRepository`、`ArtworkBitmapLoader`、`MusicWidgetProvider`、`Track`

---

## 一、结论先行

| 论点 | 判定 | 依据 |
|---|---|---|
| jaudiotagger 读真实采样率/位深，绕过 `MediaMetadataRetriever` | ❌ 前提不成立 | MediaStore 自己就有 `samplerate` / `bits_per_sample` 两列；本项目从不读这两项，UI 也不显示 |
| 同一次磁盘读取中写入 Room | ❌ 无 Room | 全项目零 Room、零 retrofit/okhttp；扫描是单游标单次遍历 + JSON 快照 |
| LaunchedEffect key 取消网络请求 + Mutex 防乱序 | ⚠️ 场景不存在 | 无网络、无歌词。现状已有 in-flight 去重 + 自动取消 |
| 封面统一 RGB_565 省一半内存 | ⚠️ 数字对、结论错 | 两个 LRU 上限是按字节固定的（24 MB / 6 MB），换配置后峰值不变 |
| LruCache + 磁盘二级缓存 | ⚠️ 只有一级 | 没有自建磁盘缓存；MediaProvider 的 `loadThumbnail` 本身就是系统级磁盘缓存 |

---

## 二、规格解析：不需要 jaudiotagger

`android.provider.MediaStore$Audio$AudioColumns`（android-37 jar）里有：

```
BITS_PER_SAMPLE = "bits_per_sample"
SAMPLERATE      = "samplerate"
```

也就是说，只要在现有 projection 里加两列，**同一次游标遍历**就能拿到，零额外 IO、零额外依赖、零额外电量。
加一个 jaudiotagger 反而是反方向：它要对每个文件单独 `openFileDescriptor` + 解析标签，
1000 首歌就是 1000 次独立 IO，正好破坏掉现在"一次查询拿全部"的结构。

再看实际需要：`Track` 只有 id / uri / title / artist / album / albumId / duration / size / year /
trackNumber / dateAdded / dateModified——**根本没有位深和采样率字段，界面上也没有任何地方展示它们**。
所以这条优化的收益目前是 0，它服务的是一个还不存在的功能。

顺带纠正一处：「绕过 `MediaMetadataRetriever` 的不准确报告」——本项目里 MMR 只用在
`ArtworkBitmapLoader:118` 取内嵌封面（API 26–28 的老路径），从来没有用它读规格。

### 如果以后真要显示

| 项 | 做法 |
|---|---|
| 读取 | projection 加 `SAMPLERATE` + `BITS_PER_SAMPLE`，还是那条 query |
| 兼容 | 这两列在低版本上取不到值，取不到就隐藏该行，不要回填假数据 |
| 风险 | 值由 MediaProvider 扫描时写入，某些容器/编码可能为空，必须真机抽样验证 |

这属于**新功能（界面新增信息）**，按项目规矩要先走 brainstorming，不是性能优化。

---

## 三、扫描 IO：现在已经是一次遍历

`MediaStoreRepository` 全文只有两处 `query()`：

| 行 | 用途 |
|---|---|
| `:44` | 主查询，一次拿全部曲目 |
| `:103` | 水位线 `COUNT(*)` / `MAX(DATE_MODIFIED)` 聚合，一次拿一行 |

没有任何 per-track 查询。所谓"IO 合并"在这里是既成事实，不是待办项。

---

## 四、取消与防抖：问题不存在，但有一个真实边界

本项目不联网、不做歌词，封面走 `ContentResolver.loadThumbnail`，所以"上一首的网络请求回来覆盖当前封面"
这个失败模式没有发生条件。现状已有的机制：

- `ArtworkRepository.pending` / `pendingPlayer`（`ConcurrentHashMap<Long, CompletableDeferred>`）：
  同一个 albumId 的并发请求合并成一次解码，快速滑动不会重复解码同一张封面。
- Compose 侧 `LaunchedEffect(track.id)` 换 key 时协程自动取消，不需要手写取消逻辑。
- 没有 Mutex，也不需要：缓存写入以 albumId 为键、同键只有一次解码，天然不会乱序覆盖。

**真实边界**（值得知道，但不值得改）：`finally` 里 `own.complete(result)` 在协程被取消时
`result` 还是 null，于是同一时刻已经在 await 的那个调用方会拿到 null（显示占位图），
要等下次重组再重来。解码本身不可中断，`BitmapFactory` 跑完才返回。
这个代价是一帧占位图，改它反而要引入引用计数。

---

## 五、RGB_565：数字要对，但"省一半内存"是错的

先算真实字节（`allocationByteCount` = w × h × 4）：

| 用途 | 边长 | ARGB_8888 | RGB_565 |
|---|---|---|---|
| 列表缩略图 `LIST_THUMB_SIZE` | 192 | 147,456 B | 73,728 B |
| 播放页封面 `PLAYER_THUMB_SIZE` | 512 | 1,048,576 B | 524,288 B |
| 播放页背景 `BACKDROP_SIZE` | 96 | 36,864 B | 18,432 B |
| 小组件封面 `COVER_SIZE` | 256 | 262,144 B | 131,072 B |

单张确实是精确的 50%。**但两个 LRU 的上限是按字节写死的**：

```
CACHE_BYTES        = 24 MB    // 列表
PLAYER_CACHE_BYTES =  6 MB    // 播放页
COVER_CACHE_BYTES  =  2 MB    // 小组件
```

换成 RGB_565 之后，24 MB 还是 24 MB——变的是能装多少张：

| 缓存 | ARGB_8888 | RGB_565 |
|---|---|---|
| 列表 24 MB | 约 163 张 | 约 326 张 |
| 播放页 6 MB | 6 张 | 12 张 |
| 小组件 2 MB | 8 张 | 16 张 |

所以真实收益是**命中率翻倍、重复解码减半、快速滚动更稳**，**不是峰值内存下降**。
要降峰值，必须同时把这三个上限调小——那才是"省内存"，但会牺牲命中率。

### 两个实现前提

**① `loadThumbnail` 的 Config 不由我们决定。** API 29+ 走的是
`ContentResolver.loadThumbnail`（`ArtworkRepository:166`），返回的 Config 由 MediaProvider 定
（可能是 ARGB_8888，也可能是 HARDWARE）。要 RGB_565 只能 `bitmap.copy(RGB_565, false)`，
这会**瞬时多分配一份**，旧图回收前峰值反而短暂变高。所以它只适合低频路径（播放页、小组件），
不适合列表滚动路径。

**② 色带是肉眼可见的。** 565 只有 5/6/5 位，专辑封面常见的渐变天空、暗部过渡会出现色带，
而这些封面在本应用里还要叠玻璃层和暗角，色带会更明显。
按项目规矩，这属于观感变更，**必须先看效果再决定**，不能当成纯技术优化直接上。

---

## 六、"磁盘二级缓存"

没有自建磁盘缓存，也确实不需要：`loadThumbnail` 拿到的是 MediaProvider 已经落盘的缩略图，
系统帮你做了二级缓存；`ArtworkBitmapLoader` 的老系统路径才自己解码内嵌图。
再叠一层磁盘缓存只会重复占用空间，且要自己处理失效。**这条不做。**

---

## 七、建议

| 优先级 | 动作 | 说明 |
|---|---|---|
| — | **不做 jaudiotagger** | 增加约 1 MB 依赖 + 每文件一次 IO，而 MediaStore 一行就能给两列 |
| — | **不做磁盘二级缓存** | 与 MediaProvider 重复 |
| P2 | 若要显示规格：projection 加两列 | 零额外 IO；属新功能，走 brainstorming |
| P2 | RGB_565 只用在播放页 + 小组件 | 低频路径，copy 成本可忽略；但先看色带是否可接受 |
| P3 | 列表缩略图 RGB_565 | 收益是命中率不是峰值，需连带下调 `CACHE_BYTES` 才有内存意义 |

一句话：这三条里唯一有真实工程价值的，是「如果要做规格显示，别引库、直接加两列」；
剩下两条要么场景不存在，要么收益被写反了。
