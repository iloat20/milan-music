# 修复：P3 扫描水位线改用 MediaStore generation

**日期**：2026-10-19
**起因**：真机验收发现 `COUNT(*) AS track_count` 被 Android 17 的 MediaProvider 拒绝，`runCatching` 吞异常后每次冷启动都全量重扫（参见 `2026-10-19-device-verification.md`）。
**状态**：已实施，真机闭环验证通过。未发版（versionCode 仍 48 / 1.0.47）。

---

## 一、根因

```kotlin
// MediaStoreRepository.fingerprint()
"COUNT(*) AS track_count",
"MAX(${MediaStore.Audio.Media.DATE_MODIFIED}) AS newest_modified",
```

真机日志：

```
E DatabaseUtils: java.lang.IllegalArgumentException: Invalid column count(*) as track_count
```

MediaProvider 对 projection 做列白名单校验，聚合表达式不在白名单内。异常被 `runCatching` 捕获 → 返回 null → 调用方回落全量扫描。**没有崩溃，只是优化彻底失效**，且日志级别为 E，每次启动都在刷。

关键教训：这类"静默退化"无法靠读代码发现，`runCatching` 恰恰提供了"看起来正常"的假象。凡是不能靠类型或单测保证的 provider 契约，必须真机验证。

## 二、方案

API 30 起提供 `MediaStore.getGeneration(Context, volumeName)` —— MediaProvider 在每次 insert/update/delete 时自增的计数器，正是"索引是否变化"的官方答案。相比聚合投影：

| | 聚合投影 | generation |
|---|---|---|
| 调用成本 | 一次全表聚合 | 一次 binder 调用 |
| 重打 tag（行数不变） | MAX(DATE_MODIFIED) 能发现 | 能发现 |
| 跨 ROM 兼容 | ❌ Android 17 拒绝 | ✅ 标准 API |
| 返回类型 | 两列 | `long` |

保留聚合路径**仅作为 API 26–29 的兜底**（那些年份的 MediaProvider 不校验投影）。两条路径的印章不可混比，否则会把不同序列拿来比较。

### 关键实现约束

1. `getGeneration` 返回 **`long`**（不是 int），写缓存要用 `longPreferencesKey`。
2. 卷名顺序必须固定：先 `VOLUME_EXTERNAL`（与查询用的 `EXTERNAL_CONTENT_URI` 同范围），失败再 `VOLUME_EXTERNAL_PRIMARY`。混用会让两次读数来自不同计数器。
3. tracks 与印章必须**同一次 commit 写入**，否则可能出现"新行 + 旧印章"，导致下一次启动跳过本该执行的扫描。
4. 印章缺失（升级前的旧缓存）→ `loadStamp()` 返回 null → 比较失败 → 全量扫一次后自愈。

## 三、改动

| 文件 | 变更 |
|---|---|
| `data/media/MediaStoreRepository.kt` | `fingerprint(): IndexFingerprint?` → `stamp(): IndexStamp`；新增 `generationOrNull()`；`IndexFingerprint` → `IndexStamp`（含 `sameAs` / `describe`） |
| `data/media/LibraryCacheStore.kt` | `save(tracks)` → `save(tracks, stamp)`，tracks 与印章一次 edit 写入；新增 `loadStamp()`；抽出 `preferences()` 复用异常处理 |
| `ui/library/LibraryViewModel.kt` | `ensureLoaded()` 改用 `stamp().sameAs(loadStamp())`，命中时打印 `library unchanged (...), rescan skipped`；`scan()` 同步写入印章 |

比较规则（`IndexStamp.sameAs`）：

- 任一方有 generation → 必须双方都有且相等；
- 双方都没有 generation → 比较 `count` 与 `newestModified`，且 `count >= 0`（未知印章永不匹配）。

## 四、真机验证（OPPO PLJ110 / Android 17）

| 场景 | 期望 | 实测 |
|---|---|---|
| 升级后首次启动 | 无 skip，全量扫一次并写入印章 | ✅ 无日志 |
| 第二次冷启动 | skip | ✅ `library unchanged (generation=249793), rescan skipped` |
| 第三次冷启动（跨进程） | 同一值 | ✅ `generation=249793` |
| 新增一首（cp + media scan） | 检测到变化，重扫 | ✅ 无 skip 日志 |
| 新增后下一次启动 | skip，且为新值 | ✅ `generation=249797` |
| 删除该文件 | 检测到变化，重扫 | ✅ 无 skip 日志 |
| 删除后下一次启动 | skip，新值 | ✅ `generation=249801` |
| 索引确认 | — | `content query` 命中 `_id=30236, title=天府乐` |

结论：generation 跨进程重启稳定；增、删两个方向都能被检测；误跳过（新歌不显示）的风险已排除。测试文件 `__wb_probe__.m4a` 已删除，`ls` 确认不存在。

## 五、构建

- `compileDebugKotlin` ✅ / `assembleRelease` + `lintVitalRelease` ✅ / `lintDebug` ✅ / `testDebugUnitTest` NO-SOURCE（本项目无单元测试）
- 版本号未动：**versionCode 48 / 1.0.47**，设备上当前安装的是本修复的 release 包（与 1.0.47 同名、内容不同）。发版需另行 bump。

## 六、遗留

- API 26–29 的聚合兜底路径未在真机验证（手头只有 Android 17 设备）；逻辑上仅在 `getGeneration` 不可达时进入。
- `offload-keep-or-disable.md` 的 gapless 归因仍需按 `2026-10-19-device-verification.md` 第四节更正。
