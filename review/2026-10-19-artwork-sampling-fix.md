# 封面加载优化建议评估与 inSampleSize 缺口修复

**日期**：2026-10-19
**范围**：`data/media/ArtworkRepository.kt`
**结论**：三条建议中 1 条已实现、1 条推迟待实测、1 条不采纳；实际改动为补齐 API 26–28 分支的全尺寸解码缺口。

---

## 一、建议判定

| 建议 | 现状核实 | 判定 |
|---|---|---|
| inSampleSize 降采样 | API 29+ 由 `loadThumbnail` 等效实现；API 26–28 内嵌封面已有动态采样；`ArtworkRepository` 的 API 26–28 分支仍全尺寸解码 | ⚠️ 基本已实现，一处缺口 → 本轮修复 |
| LruCache + DiskLruCache 两级 | 仅三层内存 LRU（24 + 6 + 8 = 38 MB 上限），无磁盘层 | ⏸ 收益未证实，推迟 |
| WeakReference / SoftReference 混合缓存 | 已有 `LruCache` + `onTrimMemory` 分级裁剪 | ❌ 不采纳 |

---

## 二、逐条依据

### 1. inSampleSize

- **API 29+ 主路径**（`ArtworkRepository.kt:166`）：`contentResolver.loadThumbnail(uri, Size(size, size), null)`。MediaProvider 在单次跨进程调用内直接产出目标尺寸位图，不分配中间的全尺寸位图，比手动 `inSampleSize` 更优。
- **API 26–28 内嵌封面**（`ArtworkBitmapLoader.kt:137-153`）：`decodeScaled()` 已含 bounds 预扫描 + `sampleSizeFor()` 动态取 2 的幂。1.0.47 内存专项审计条目 M8 已关闭。
- **缺口**：`ArtworkRepository.decodeBitmap` 的 `else` 分支走 `MediaStore.Images.Media.getBitmap()`，该 API 内部是 `openInputStream` + `decodeStream`，**不带任何采样**，随后才 `scale()`。审计 M8 只覆盖了 `ArtworkBitmapLoader`，遗漏此处。

**关于「inSampleSize 设为 4」的修正**：4 是固定值，不成立。`inSampleSize` 必须是 2 的幂，且应取「源长边 / 目标长边」向下取整的最大 2 的幂：
- 1200 px 源 → 列表目标 192 px：sample = 4（1200/4 = 300，再 scale 到 192）
- 480 px 源 → 列表目标 192 px：sample = 1（若固定 4 只剩 120 px，低于目标，反而更糊）

### 2. 两级缓存（推迟）

本项目为纯本地曲库，封面源为 MediaProvider。`loadThumbnail` 对本地媒体走 provider 内部的缩略图磁盘缓存，因此「避免重复解码」在系统层已经发生。自建 DiskLruCache 的净收益仅是省掉一次 Binder IPC + provider 查询，量级个位数毫秒。

引入成本：磁盘配额维护、写入 IO、**失效正确性**（albumart 无稳定版本号，`date_modified` 变化不保证反映封面更替）。

**决策**：无耗时分布数据前不引入。若后续实测显示冷路径占比高、单次 `loadThumbnail` p90 > 30 ms，正确设计是磁盘只存**已降采样的 192 px WebP（6–12 KB/张）** 作为 L2，而非缓存原始封面（500 张约 5 MB）。

### 3. 弱引用（不采纳）

- `SoftReference` 自 ART 起语义与 JVM 规范不一致，回收时机由 GC 决定，应用不可控。官方自 API 9 起即不建议用于缓存：既无命中率保证，又增加 GC 停顿。
- `WeakReference` 对象随时可被回收，只适合做去重索引或持有监听器，不能作为缓存主存储。
- 现有 `LruCache` + `onTrimMemory` 分级裁剪（`ArtworkRepository.kt:139-156`、`ArtworkBitmapLoader.kt:100-107`，挂载点 `MilanMusicApp.kt:33` / `PlaybackService.kt:173`）是官方推荐路径，可控且可观测。
- 建议中提到的「波形图 / 歌词解析」在本项目不存在（曲库 App，无波形渲染与歌词模块）。未来若引入，正确做法是独立 LRU 实例 + 独立 trim 策略。

---

## 三、本轮改动

`app/src/main/java/com/milan/music/data/media/ArtworkRepository.kt`

| 位置 | 改动 |
|---|---|
| `:164-170` | `decodeBitmap` 的 API 26–28 分支改为调用 `decodeAlbumArt(uri, size)` |
| `:172-211` | 新增 `decodeAlbumArt`：bounds 预扫描 → `inSampleSize` 采样 → 等比缩放到目标尺寸 |
| `:213-228` | 新增 `sampleSizeFor(longest, target)`，与 `ArtworkBitmapLoader` 的同名私有函数同构 |
| import | 删除 `android.provider.MediaStore`（不再使用），新增 `android.graphics.BitmapFactory` |

**编译期内存峰值变化**（API 26–28，1200 px albumart → 192 px 目标）：

- 改前：`decodeStream` 全尺寸 1200×1200 ARGB_8888 = **5.49 MB** 峰值，之后另分配 300×300 缩放位图
- 改后：采样后 300×300 = **0.34 MB** 峰值，再等比缩到 192×192
- 峰值下降约 **94%**

**附带修正**：原实现 `bitmap.scale(size, size)` 会把非正方形封面强制拉伸为正方形。新实现保持宽高比，配合 `CoverArt` 的 `ContentScale.Crop` 表现为裁切而非变形。

**防御性处理**：`sampleSizeFor` 对 `target <= 0` 做 `coerceAtLeast(1)`，避免循环永真导致除数溢出为 0（当前三个调用点均传正常常量，此处理仅防御）。

---

## 四、验证

| 项目 | 结果 |
|---|---|
| `./gradlew :app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `./gradlew :app:lintDebug` | 0 errors / 6 warnings，全部为既有告警（`MediaStoreRepository` InlinedApi ×2、Gradle 9.8.1 可升级 ×1、Kotlin 2.4.21 可升级 ×3），`ArtworkRepository.kt` 零新增告警 |
| 真机验证 | **未执行** —— 无设备连接，且该分支仅在 API 26–28 生效，现有测试设备无法覆盖 |

**待验证项**：需 API 26–28 设备或模拟器，导入带大尺寸 albumart 的曲目，用 `dumpsys meminfo` 对比 PSS 峰值。

---

## 五、遗留问题

1. **缓存预算硬编码**：三层 LRU 合计 38 MB，低端机 heap 192 MB 时占比 19.8%，高于「内存 1/8」惯例线，且全部为常量、无一处基于 `ActivityManager.memoryClass` 动态计算。
2. **`loadBackdrop` 无缓存**（`ArtworkRepository.kt:117-119`）：每切歌一次 IPC + 96 px 解码，播放页进出重来。
3. **`MusicWidgetProvider` 每曲 2 × 256 KB 分配无复用**（内存审计已记录，未处理）。

---

## 六、遗留项处理（同日追加）

用户确认后一并处理上述三项。核实后发现第 3 项的一半已在 1.0.47 修掉，实际改动范围如下。

### 6.1 缓存预算改为按堆动态计算

新增 `data/media/BitmapBudget.kt`：总预算 = `memoryClass / 8`，钳制在 16–48 MB。四个 LruCache 按 `10/16 : 2/16 : 1/16 : 3/16` 切分，合计恰好用完总预算。

| `ActivityManager.memoryClass` | 总预算 | 列表 | 播放页 | 背景 | 通知 |
|---|---|---|---|---|---|
| 192 MB（老设备） | 24 MB | 15.0 | 3.0 | 1.5 | 4.5 |
| 256 MB（主流） | 32 MB | 20.0 | 4.0 | 2.0 | 6.0 |
| 384 MB 及以上 | 48 MB（封顶） | 30.0 | 6.0 | 3.0 | 9.0 |

改前固定为 24 + 6 + 8 = **38 MB**。对比：

- 192 MB 堆：38 → 24 MB（**−37%**，占比 19.8% → 12.5%）
- 256 MB 堆：38 → 32 MB（−16%）
- 512 MB 堆：38 → 48 MB（+26%，但占比仅 9.4%，仍在 1/8 线内）

**取舍说明**：192 MB 设备上列表缓存由 24 MB 降到 15 MB（约 163 张 → 100 张 192 px 封面）。miss 后走 `loadThumbnail`（本地 MediaProvider，5–15 ms，异步），代价是滚动时偶见的占位渐变，而非主线程卡顿。以内存优先换取这一项是刻意的。

**未采用 heap 直接除以 8 的单层方案**：`ArtworkBitmapLoader` 与 `ArtworkRepository` 是两个独立对象，各自调用 `BitmapBudget.totalBytes()` 得到相同值后切各自的片，避免引入跨模块的单例记账器。

### 6.2 `loadBackdrop` 增加缓存

新增 `backdropCache`（`1/16` 预算，96 px 位图约 36 KB/张，可存 40+ 张），并补齐与另外两条路径一致的 `CompletableDeferred` 并发去重。

改前每次进入播放页或切歌都要一次 `ContentResolver` 往返 + 解码。主导色扫描仍在每次进入时重算（属于 UI 层职责，不在本次范围）。

`onTrimMemory` 与 `evictAll` 同步覆盖了新缓存：`UI_HIDDEN` 全清，`RUNNING_LOW` 缩到 1/8。

### 6.3 Widget 封面合并分配

核实发现 `coverCache`（2 MB，按 `(albumId, radiusBucket)` 键）在 1.0.47 已存在，遗留描述中"无复用"的部分不再成立。剩下的问题是**首次渲染**仍分配两张 256×256 位图：`scale()` 一张，`withRoundedCorners()` 再一张。

改为 `roundedCover()`：创建一张输出位图，用 `FILTER_BITMAP_FLAG` 直接把源位图缩放绘制进圆角裁剪区（`SRC_IN`），单次分配完成。每曲瞬时分配 512 KB → **256 KB**。

### 验证

| 项目 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `:app:lintDebug` | 0 errors / 6 warnings，与改动前逐条一致，无新增 |
| 真机 | 未验证（无设备连接） |

**待验证项**：

1. `dumpsys meminfo` 对比改前后台 PSS（预期 −10 ~ −14 MB @ 192 MB heap）
2. 列表长距离滚动时的占位闪烁频次（列表缓存缩小后的实际观感）
3. Widget 圆角在 API 26–30 上的渲染是否正确（`SRC_IN` 路径改造后需目视确认）

