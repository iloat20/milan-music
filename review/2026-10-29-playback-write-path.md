# 播放写放大与队列元数据（2026-10-29）

基线：MilanMusic 1.0.85（versionCode 86）。本轮只处理静态审计里的 P1-1 与 P1-2：

- 起播前 `onAddMediaItems` 在主线程上逐条解析整个队列；
- 每次切歌仍会整份重写队列文件，`playQueue` 一次最多触发三次 ids 重写。

P2-4（ReplayGain 最多 1 MB 读）、P2-5（通知封面缓存按音频 URI）、P2-6（倒影/背景模糊
GPU 峰值）本轮未动。版本号未改，改动尚未打包。

## 一、结论摘要

| 项 | 改前 | 改后 |
|---|---|---|
| 切歌（队列不变） | 3 次 DataStore edit，其中 1 次重写全部 ids（1 233 首约 12 KB，5 000 首约 50 KB） | 1 次小文件 edit（游标 + 位置，几百字节） |
| `playQueue` | 最多 3 次 ids 重写（timeline / transition / shuffle） | 1 次 ids 重写 + 若干小 edit |
| shuffle / repeat | 整份队列重写 | 只写游标 |
| `onAddMediaItems` | O(n) 跑在应用 looper 上 | O(n) 跑在 `Dispatchers.Default`，looper 立即返回 |
| 起播延迟 | Media3 等 future | 不变（仍等 future），只解除主线程占用 |

## 二、切歌写放大

### 根因

DataStore 的 `edit` 不是“只改一个键”：它把整份 preferences 编码后写临时文件、rename、
fsync。1.0.67 起 `CURSOR` 作用域不再重新编码 ids 字符串，但文件里仍然带着 `track_ids`，
所以每次切歌还是在重写那 12–50 KB。此前一次切歌实际有三个 edit：

1. `saveCursor` 写队列 store（游标字段 + 未变的 ids）；
2. `saveCursor` 写位置 store（位置 + index）；
3. `onMediaItemTransition` 紧接着再调一次 `persistPosition`，又一次位置 store。

### 改动

- `PlaybackSnapshotStore.kt:319` `saveQueue`：ids 与一份“队列游标副本”写队列文件；
  真正游标、shuffle/repeat、位置写位置文件。
- `PlaybackSnapshotStore.kt:376` `saveCursor`：只写位置文件，一次 edit 同时落游标与
  位置。切歌路径不再触碰 `track_ids`。
- `PlaybackService.kt:1009`、`:1013`：shuffle / repeat 改走 `CURSOR`。
- `PlaybackService.kt:967`：transition 不再额外 `persistPosition`；位置已随游标写入。
- `PlaybackService.kt:995` 的 `STATE_ENDED/IDLE` 同理，只保留一次 `persistQueue`。

### 半写判定

游标移到另一个文件后，全量写跨两个文件，不再原子。新增 `queue_revision` /
`cursor_revision` 精确配对（`PlaybackSnapshotStore.kt:131`）：

| 落盘情况 | `load()` 的行为 |
|---|---|
| 队列 edit 成功，游标 edit 成功 | revision 相等，正常恢复 |
| 队列 edit 成功，游标 edit 失败 | revision 不等，回退到队列文件里与 ids 同批写入的游标副本 |
| 队列 edit 失败 | 队列与游标都还是旧的，revision 仍相等 |
| 切歌单次 edit | 游标与位置在同一次 edit 内，天然一致 |

回退时位置是否可用由 `positionMatchesQueue`（`:160`）独立判断：`position_index` 必须
等于当前 index，时间戳不得早于队列写入。游标 revision 失配不再直接丢弃位置——ticker
可能在全量写失败后继续写入，那份位置仍然属于新队列。

升级兼容：旧快照没有 revision，`load()` 按旧布局读取队列文件里的 `current_index` /
`current_track`，首次全量写才生成 revision（`bumpRevision`，`:141`，时钟回拨也保证
严格递增）。`clear()` 同时清掉内存里的 revision（`:505`）。

## 三、起播前元数据解析

`PlaybackService.kt:178` 改为返回一个 `SettableFuture`，解析在
`Dispatchers.Default` 上执行；无论取消还是异常都会完成 future，避免 Media3 永久等待。
完成后记录：

```
queue metadata resolved 5000 items in 12 ms
queue metadata hits=5000/5000
```

第一条是本轮新增的耗时证据，第二条是原有的命中计数。

Media3 按 future 的**完成顺序**把结果 post 回应用线程（已反编译
`MediaSessionStub.handleMediaItemsWhenReady` 确认），并发解析可能让后发的
`addMediaItems` 先落地。新增 FIFO 的 `queueMetadataLock`（`:156`），在 future 完成前
一直持有，恢复原来的命令顺序。

**仍未解决**：future 仍在 `setMediaItems` 的关键路径上，tap 到首帧的延迟不会因为这次
改动下降；本轮只是把 O(n) 从主线程挪走并加了测量。

## 四、`playQueue` 的重复全量写

`setMediaItems` 会连续触发 timeline change 与 transition。transition 原先在**回调时刻**
问 `QueueSnapshotPolicy`；此时 timeline 的全量写还没完成，于是 transition 也判为 FULL。

改为 `persistQueueAtTransition`（`PlaybackService.kt:551`）在写锁内再判作用域：timeline
的全量写持有 FIFO 锁先完成，transition 随后看到确认，只写游标。

`QueueSnapshotPolicy.kt:43` 增加 `structureVersion`：全量写在读播放器**之前**捕获版本，
写成功后只有版本未变才清除 dirty。否则“写 IO 期间又来一次时间轴变化”会被旧写错误地
确认，下一次 transition 会把游标盖到旧 ids 上。对应单测
`a timeline change during the rewrite keeps the next transition full`。

## 五、验证

| 项 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | 通过 |
| `:app:testDebugUnitTest`（282 例） | 全绿，0 失败 |
| `:app:lintDebug` | 通过 |

新增/更新的纯函数单测：

- `PlaybackSnapshotTest`：revision 配对、无 revision 的旧快照、时钟回拨仍递增；
- `QueueSnapshotPolicyTest`：写 IO 期间的时间轴变化不被旧写清除。

没有真机，也没有可用模拟器，以下只能在设备上确认：

1. 5 000 首曲库点“播放全部”，`logcat -s PlaybackService` 里应出现
   `queue metadata resolved 5000 items in ... ms`，且点击到首帧无掉帧/ANR。
2. 覆盖安装 1.0.85（保留数据）后冷启动：应恢复同一首、同一位置，shuffle/repeat 不变。
3. 连续切歌 20 首：除首次全量写外，日志应只有 `snapshot cursor saved index=...`。
4. 反复在起播瞬间强杀进程：冷启动应回到队列起点或最多归零位置，不应恢复到另一首的
   时间戳。精确命中“两个 edit 之间”很难，半写矩阵目前只有纯函数级验证。

## 六、未做

- P2-4：`ReplayGainReader` 对无 ReplayGain 的文件仍可能读满 1 MB。
- P2-5：通知封面缓存仍按音频 URI 而不是专辑 id。
- P2-6：播放页 72dp 全屏模糊、28dp 边缘模糊、4 层倒影的 GPU 峰值未动。
