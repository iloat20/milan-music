# 曲库扫描 IO 路径核实：合并遍历是否成立

- 日期：2026-10-19
- 版本基线：1.0.47
- 结论：**「合并为一次 IO 遍历」已是现状**——但流程里有两处无效 IO 和一处会导致漏歌的时序缺陷。

---

## 0. 结论表

| 提议 | 判定 | 依据 |
|---|---|---|
| 元数据读取与写入合并为一次遍历 | ✅ **已经是这样** | 一次 `query()` 遍历 + 一次 `save()`，无 per-track IO |
| 用 jaudiotagger 读元数据 | ❌ **不适用** | 项目不读音频文件，元数据全部来自 MediaStore 索引列 |
| 避免重复扫描磁盘 | ✅ **已实现** | `IndexStamp` generation 比对，命中时整次扫描被跳过 |
| —— 但 `stamp()` 被重复调用 | ⚠️ **真实无效 IPC** | `LibraryViewModel.kt:163` 与 `:189` 各一次 |
| —— 但 `save()` 是全量重写 | ⚠️ O(N) 写，无增量 | `LibraryCacheStore.kt:117-130` |
| —— 但 stamp 取值时机会漏歌 | 🐛 **真实缺陷** | 见 §3 |

---

## 1. 为什么「合并遍历」没有落点

本项目**不扫描文件，也不解析标签**。`app/build.gradle.kts` 的依赖里没有 jaudiotagger，没有 taglib，也没有 Room / SQLite —— 全部 106 行依赖中与存储相关的只有 `androidx.datastore.preferences` 和 `kotlinx.serialization`。

元数据来源是 `MediaStoreRepository.loadTracks()` 的一次游标遍历（`MediaStoreRepository.kt:41-83`）：

```kotlin
context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
    while (cursor.moveToNext()) {
        tracks += Track(id = ..., title = ..., artist = ..., album = ..., ...)
    }
}
```

**11 个投影列在这一次 Binder 调用里全部返回**：`_ID / TITLE / ARTIST / ALBUM / ALBUM_ID / DURATION / SIZE / YEAR / TRACK / DATE_ADDED / DATE_MODIFIED`。MediaProvider 早已在系统索引进程里完成了「读文件 → 解标签 → 写数据库」，重复的部分不在应用侧。

所以整条链路是：

| 步骤 | IO 次数 | 位置 |
|---|---|---|
| 读索引水位 | 1 次 Binder（`getGeneration`） | `MediaStoreRepository.kt:135` |
| 读全库元数据 | **1 次 cursor 遍历** | `MediaStoreRepository.kt:45-76` |
| 写缓存 | **1 次 DataStore commit** | `LibraryCacheStore.kt:119-129` |

**1 读 + 1 写，已经是一次遍历的理论下限。** 引入 jaudiotagger 反而会把它变成 N 次文件随机读（每首要 `open` + `seek` + 解析 ID3/Vorbis comment），是这条链路上最贵的做法。

---

## 2. 真实的无效 IO：重复的 `stamp()`

`LibraryViewModel.ensureLoaded()`（`:151-193`）：

```kotlin
val stored = libraryCache.loadStamp()
val current = mediaStore.stamp()          // ← 第 1 次：判断是否需要扫描
if (current.sameAs(stored)) return        //   命中则整次扫描跳过

val fresh = mediaStore.loadTracks()       //   1 次 query
...
libraryCache.save(fresh, mediaStore.stamp())   // ← 第 2 次：又调一遍
```

`:189` 的第二次 `stamp()` 是多余的——`:163` 已经有 `current` 在手，可以直接传进去。

成本：

- API 30+：`generationOrNull()` 最多试 2 个卷名（`VOLUME_EXTERNAL` → `VOLUME_EXTERNAL_PRIMARY`），每次一个 Binder 往返
- API 26–29：退化成聚合 query（`COUNT(*)` + `MAX(DATE_MODIFIED)`），**一次全表聚合**，比读全库还贵

也就是说低版本设备上，一次扫描会跑两次全表聚合。

---

## 3. 顺带发现的缺陷：stamp 取值时机会漏歌

同一个调用顺序里还有个时序问题，它不是性能问题，是正确性问题。

时间轴：

```
t0   current = stamp()            → 记录 generation = G1
t0..t1  loadTracks() 遍历中         ← 用户此时新增一首歌，索引变成 G2
t1   save(fresh, stamp())         → 记录 generation = G2
```

`fresh` 是在 `[t0, t1]` 期间某一点读到的快照，**可能不含新增的那首**。但存进缓存的 stamp 是 `t1` 时刻的 `G2`。

下次启动：`current` 仍是 `G2`，与 `stored` 相同 → **跳过扫描**。结果那首新歌在用户下次手动刷新前都不会出现。

概率不高（要求索引变更恰好落在查询窗口内），但代价是"用户以为加了歌，App 里没有"。

**修法：用扫描前取到的 stamp 保存。**

```kotlin
libraryCache.save(fresh, current)   // t0 的值
```

这样若索引在读取期间变化，下次启动 `current(G2) != stored(G1)` → 自动重扫一次 → 自愈。同时顺带消掉 §2 的重复调用。

`scan()`（`:200-206`）是同一模式，但它没有前置 stamp，需要在 `loadTracks()` **之前**取：

```kotlin
val stamp = mediaStore.stamp()
val tracks = mediaStore.loadTracks()
libraryCache.save(tracks, stamp)
```

---

## 4. 全量重写：评估后不建议动

`LibraryCacheStore.save()`（`:117-130`）每次都把**整个库**序列化成一段 JSON 写进 Preferences：

```kotlin
val payload = json.encodeToString(serializer, tracks.map { it.toCached() })
context.libraryCacheStore.edit { preferences -> preferences[tracksKey] = payload ... }
```

任何一首变化都会重写全量。5000 首约 500 KB–1 MB JSON。

要消除它只有两条路：

| 方案 | 代价 |
|---|---|
| 引入 Room，按 `id` 增量 upsert | 新增依赖 + 建表 + 迁移；多出的类约 10 个 |
| 自行维护分片文件 | 缓存一致性、原子性、版本管理都要自己实现 |

而收益：`ensureLoaded()` 走到 `:189` 的前提是 `:164` 的 stamp 比对**已经失配**，即确实发生了变更——这次重写不是无效的。真正"什么都没变还重写"的只有 `scan()`（用户手动下拉刷新），一次约 5–15 ms。

**结论：不值得。** 保持 DataStore + JSON blob 的单文件形态。

---

## 5. 建议动作

| 选项 | 内容 | 规模 |
|---|---|---|
| A（推荐） | 修 §3 的时序缺陷 + 消掉 §2 的重复 `stamp()`，两处同一个改动 | 2 个函数，约 6 行 |
| B | A 之上给 `scan()` 也加"内容未变则跳过写入"的判断 | +8 行 |
| C | 引入 Room 做增量写 | 不建议，见 §4 |

`load()` 与 `loadStamp()` 各自 `.first()` 一次 `DataStore.data` 不算重复 IO——DataStore 首次读取后在内存持有解码结果，第二次是内存重放，不落盘。

---

## 6. 回退

A / B 都只改调用顺序，回退即还原两个函数的语句顺序。

---

## 7. 执行记录（选项 A，2026-10-19 20:10）

改动文件：`app/src/main/java/com/milan/music/ui/library/LibraryViewModel.kt`，两个函数，净增 15 行注释、净减 1 次 `stamp()` 调用。

### 7.1 `ensureLoaded()`

```kotlin
val cached = libraryCache.load()
val stamp = mediaStore.stamp()        // 一次取，读完复用
if (cached.isNotEmpty()) {
    allTracks.value = cached
    loading.value = false
    val stored = libraryCache.loadStamp()
    if (stamp.sameAs(stored)) { ... return@launch }
}
val fresh = mediaStore.loadTracks()
...
libraryCache.save(fresh, stamp)       // 原来是 mediaStore.stamp()
```

`stamp` 提升到 `if` 之外，因此缓存为空的分支也会在读取前取一次 stamp——原来那条路径是读完才取，等价于把 `scan()` 的缺陷也一起修了。总调用次数不变（每轮扫描 1 次），只去掉了一次重复。

### 7.2 `scan()`

```kotlin
val stamp = mediaStore.stamp()        // 读到 tracks 之前
val tracks = mediaStore.loadTracks()
allTracks.value = tracks
libraryCache.save(tracks, stamp)
```

### 7.3 行为差异

| 场景 | 改前 | 改后 |
|---|---|---|
| 缓存命中且 stamp 相同 | 1 次 `stamp()` + 0 次 query | 不变 |
| 缓存命中但 stamp 不同 | 2 次 `stamp()` + 1 次 query | **1 次 `stamp()`** + 1 次 query |
| 无缓存冷启动 | 1 次 query + 1 次 `stamp()`（读后） | 1 次 `stamp()`（读前）+ 1 次 query |
| 扫描期间索引变化 | 存新 stamp → 下次跳过 → **漏歌** | 存旧 stamp → 下次重扫 → 自愈 |

API 26–29 上第二行省下的是一次 `COUNT(*)` + `MAX(DATE_MODIFIED)` 全表聚合。

### 7.4 验证

| 项目 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL（28s） |
| `:app:lintDebug` | **0 errors / 6 warnings**，与改动前逐条一致 |
| `:app:assembleDebug` | BUILD SUCCESSFUL（1m 24s），`app-debug.apk` 23 MB |
| 单元测试 | 项目无 `src/test` 目录，无既有测试可跑 |
| 真机 | 未验证 |

`git diff --stat` 显示 35 insertions / 17 deletions，其中包含本次之前就存在的未提交改动（`LibraryViewModel.kt` 在工作区一直是 modified 状态），不是本轮新增量。

### 7.5 未做

选项 B（`scan()` 内容未变则跳过写入）：当前 `refresh()` 是用户主动下拉，重写 0.5–1 MB 约 5–15 ms，收益不足。`scan()` 本身已经是最小改动。

