# M2：Haze 模糊输入降采样（含审计数字更正）

日期：2026-10-19
范围：`ui/theme/Theme.kt` 单处注入。未改 `GlassSurface`、未改任何调用点、未动 `Modifier.blur`。

---

## 1. 结论先行

审计里 M2 的三个数字（"未设参数 = 全屏分辨率"、"`Fixed(0.5f)` = 像素 ÷4"、"采样层 ÷4 省 7 MB"）**全部不成立**。按 Haze 2.0.1 官方源码重算后，这一项的真实上限是 **0.94 MB**，不是 7 MB。

决定仍然做了，但性质变了：它不再是"捡回 7 MB"，而是"在零观感风险的前提下，把这块本来就不大的开销压到最低"。

## 2. 更正依据（源码，非反编译推测）

源码来自 `repo1.maven.org` 的 `haze-android-2.0.1-sources.jar` 与 `haze-blur-android-2.0.1-sources.jar`，与项目锁定版本一致。

```kotlin
// HazePerformanceMode.kt:20-29
val Balanced = Fixed(0.5f)
val Default  = Balanced
val Quality  = Fixed(1f)
val Performance = Fixed(0f)

// BlurInputScalePolicy.kt:16-19
scale = sqrt(MIN_FIXED_PIXEL_FRACTION + (MAX_FIXED_PIXEL_FRACTION - MIN_FIXED_PIXEL_FRACTION) * qualityFraction)
      = sqrt(0.25 + 0.75 * qualityFraction)

// BlurVisualEffect.kt:96
val resolvedPerformanceMode = style.performanceMode ?: scope.currentValueOf(LocalHazePerformanceMode)
// BlurVisualEffect.kt:232 —— 节点默认值
internal var performanceMode: HazePerformanceMode = HazePerformanceMode.Default
```

| 审计原文 | 实际 |
|---|---|
| `GlassSurface.kt` 未传第 4 参 → 走全分辨率 | 未传 = `null` → 继承 `LocalHazePerformanceMode` → `Default` = `Balanced` = `Fixed(0.5f)` → **0.79 线性 / 62.5% 像素** |
| `Fixed(0.5f)` 让像素量 ÷4 | `Fixed(0.5f)` **就是当前默认值**，收益为 0。÷4 像素对应 `Fixed(0f)`（= `Performance`），线性 0.5 |
| 采样层是全屏 ~10 MB，降档后 −7 MB | 捕获层按**每个效果自身尺寸**建：`BlurHelpers.kt:124` `scaledLayerSize = layerSize * scaleFactor`，`layerSize` 来自 `context.layerSize`（效果节点）。四个面板合计 0.63 Mpx，不是一整屏 2.59 Mpx |

补充一个审计没提、但对判断有利的事实：**半径随缩放同步缩**（`BlurVisualEffectUtils.kt:105-106`，`resolveBlurRadiusPx = (blurRadius * scale).toPx()`）。所以降档不改变观感半径，只减少"模糊前的细节"。

## 3. 真实成本结构

### 3.1 输入层（M2 作用的对象）—— 小

按 1080×2400 / 2.75x 计，四个面板的实际像素面积：

| 面板 | dp | px | 面积 |
|---|---|---|---|
| 搜索框 | 312×46 | 858×127 | 0.11 Mpx |
| 迷你播放器 | 364×62 | 1001×171 | 0.17 Mpx |
| 底部栏 | 364×53 | 1001×146 | 0.15 Mpx |
| 排序菜单 | 156×172 | 429×473 | 0.20 Mpx |
| **合计** | | | **0.63 Mpx** |

| 档位 | 线性 | 像素占比 | 合计显存 |
|---|---|---|---|
| `Default` / `Balanced` | 0.79 | 62.5% | 1.57 MB |
| `Fixed(0.35f)` | 0.72 | 51.3% | 1.29 MB |
| `Performance` | 0.50 | 25.0% | 0.63 MB |

`Balanced → Performance` 的差额是 **0.94 MB**，外加四个小区域的填充率降 2.5 倍。这就是本项的全部收益。

### 3.2 源层（真正的大头）—— 大，但用户已决定保留

```kotlin
// HazeSourceNode.kt:306-322
val contentLayer = area.contentLayer ?: graphicsContext.createGraphicsLayer()
contentLayer.record { this@draw.drawContentSafely() }   // 有 demand 时每帧无条件重录
```

`hazeSource` 给**每个源节点**建一张全尺寸离屏层，且每次 draw 都重录（没有 dirty 判断）。当前屏幕有两个源：

| 源 | 位置 | 尺寸 |
|---|---|---|
| `AmbientBackdrop` | `HomeScreen.kt:199` | 1080×2400 |
| `LazyColumn` | `LibraryLists.kt:56` | 1080×2400 |

- 常驻显存：2 × 10.4 MB ≈ **20.7 MB**
- 滚动时：每帧多画 2 个全屏内容 ≈ 5.2 Mpx 的额外离屏绘制

这就是 P2。本轮**不动**，理由见第 5 节。

## 4. 实施

`ui/theme/Theme.kt` 已有的 `CompositionLocalProvider` 追加一项：

```kotlin
CompositionLocalProvider(
    LocalGlassColors provides glass,
    LocalHazePerformanceMode provides HazePerformanceMode.Performance,
) { MaterialTheme(...) }
```

- 选 `LocalHazePerformanceMode` 而不是给 `GlassSurface` 加参数：面板层代码零改动，`hazeBlur` 第 4 参保持 `null`（"inherit from LocalHazePerformanceMode" 本就是它的文档语义），将来要单面板回退不必改签名。
- 只作用于 `hazeBlur`。播放页背板的 `Modifier.blur(48.dp)`（M3）不受影响。
- `noiseFactor(0.03f)` 保留。

**已知视觉风险**：噪声在缩放后的层上生成，2× 上采样会让颗粒尺寸翻倍。0.03 幅度下大概率不可辨，但这是唯一可能被眼睛抓到的地方。

**回退阶梯**：`Performance` → `Fixed(0.35f)`（0.72 线性，仍省 0.28 MB）→ `Default`。改一个常量。

## 5. P2 为什么不做（这次不是"没时间"）

用户的取舍是"保留玻璃质感"。在这个前提下，P2 的三条路全部被堵死：

1. **去掉 `LazyColumn` 源**：迷你播放器、底部栏、排序菜单背后不再透出列表内容，只剩磨砂渐变。质感的卖点消失。
2. **去掉 `AmbientBackdrop` 源**：列表行是透明的，面板会露出透明洞，直接不可用。
3. **换成静态背板**：与 (1) 等价——只留渐变源。

而且 (1)/(3) 在观感上会收敛到"干脆别用 Haze"：模糊一片平滑渐变，和直接画半透明渐变几乎没有区别。也就是说 P2 不存在"省一半质感"的中间档，只有"付"或"不付"。

结论：**P2 是一个产品决策，不是一个性能优化项**，记录在案，等有实机数据（滚动帧时对比）再谈。搜索框不受影响——它在布局上位于列表上方（`Column { Row(搜索框); Box(weight=1){ 列表 } }`），本来就没压在列表上。

## 6. 验收

| 项 | 手段 | 结果 |
|---|---|---|
| 编译 | `assembleDebug` | BUILD SUCCESSFUL |
| R8 | `assembleRelease` | BUILD SUCCESSFUL |
| 静态检查 | `lintDebug` | 0 error（4 warning 为既有上游版本提示） |
| 观感 | 人眼扫迷你播放器 / 底部栏 / 排序菜单的颗粒感 | 待用户确认 |
| 显存 | GPU 侧，`dumpsys gfxinfo` 只能看帧时，看不到层分配 | 以源码计算为准（0.94 MB） |

## 7. 与审计原文的关系

`2026-10-19-memory-power-audit.md` 的 M2 行与 M2 详述段已加更正标注，**原文保留**作为对照。这是该文档第二处被源码推翻的判断（第一处是 P5 的 float 输出）。
