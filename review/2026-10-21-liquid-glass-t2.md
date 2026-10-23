# 液态玻璃 T1+T2：材质层与边缘光学

日期：2026-10-21
范围：`ui/glass/GlassSurface.kt`、`ui/theme/Theme.kt`、新增 `GlassEdgeTest`
作用面：MiniPlayer（26dp 圆角）、首页底部玻璃栏（28dp）、搜索栏（20dp）、排序浮层（20dp）——四处都是既有 `GlassSurface` 调用点，版式未动。

## 目标

对齐 iOS 27（WWDC26 Platforms State of the Union）对 Liquid Glass 的三项基础调整：

1. 更有效地漫射下层复杂内容；
2. 引入**加深的边缘**，同时**提高镜面高光亮度**，靠明暗对比建立层次；
3. 透明度可调（ultra clear ↔ fully tinted），并跟随"降低透明度/增加对比度"无障碍设置。

## 落地映射

| 光学层 | 代码 | 参数 |
|---|---|---|
| L1 漫射 | `GlassBlurRadius` | 26 → 30dp（可选 26 / 30 / 32） |
| L2 鲜艳度 | `GlassVibrancy`（`HazeColorEffect.colorFilter` + `ColorMatrix.setToSaturation`） | 1.18 |
| L3 边缘暗化 | `innerRingStops()`，`InnerRingStrokeWidth = 5dp`（可见 2.5dp） | 底部 22% 起坡，峰值 `0.65 × edgeShadow` |
| L4 镜面高光 | `rimStops()`，`RimStrokeWidth = 2dp`（可见 1dp） | 顶部满值，40% 处归零，72% 起转暗边 |

`GlassColors` 里原本的 `borderTop` / `borderBottom` 两个字段从未被任何代码读取（grep 确认），
本次改名为 `edgeHighlight` / `edgeShadow` 并真正接上；`fillTop` / `fillBottom` / `sheen` /
`hairline` 仍是死字段，未动（不在本批次范围）。

## 为什么不是"一圈白边"

2026-10 早期版本画过 1dp 均匀白描边，被判定"读成发亮的边"。原因不是白边本身，而是**只有亮边、
没有暗边**：在浅金调色板上，一圈恒定 alpha 的白线没有方向，视觉上等于给面板加了个发光框。
iOS 27 的解法是亮边与暗边成套出现——上缘提亮给受光方向，下缘压暗给厚度。

因此现在的实现是沿周长连续变化的垂直渐变描边，中段必须回到全透明并保持一段。
`GlassEdgeTest.rimGoesTransparentThroughTheMiddleSoItNeverReadsAsAUniformBorder` 就是这条的守卫。

**红态记录**：第一版 `rimStops` 写成 `0.58f → shadow × 0.30`，测试立刻失败——中段 0.58 处已有
4.5% 暗边，等于暗边从半高就开始渗。修正为 `0.40f → 0`、`0.72f → shadow × 0.25` 后转绿。
这就是"先红后绿"的一次真实用例，不是补写的断言。

## 离线复刻的证据

无设备可用（`adb devices` 为空），用 `review/glass-t2-preview.py` 按同一套数值复刻材质，
底图取真实截图 `screenshots/verify-01-home.png`，产出 `review/2026-10-21-glass-t2-preview.png`：

- 26dp vs 30dp：在文字行上都是完全漫射，30dp 的边际收益小，但图像更"匀"，符合 iOS 27 的漫射方向；
- 边缘 ×1.0 vs ×1.6：×1.6 明显过曝、读成白色描边，反证 ×1.0 接近合理；
- 内环 3.5dp vs 2.5dp：3.5dp 时底部读成一整片压暗幕（vignette），改成 2.5dp 后才像"边"。
  这一档调整是复刻图直接促成的，不是拍脑袋。

复刻的局限：不含 Haze 的降采样（`HazePerformanceMode.Performance` = `Fixed(0.5f)`）、噪点、
elevation 阴影，也不含真实背景色场；面板是压在列表行上而非真实的底栏位置。

## 门禁

- `assembleDebug` / `testDebugUnitTest`：23 个用例全绿（其中 `GlassEdgeTest` 5 个）
- `lintDebug`：0 error，仅 `AndroidGradlePluginVersion` + `NewerVersionAvailable` 两条上游版本通知
- `w:` 编译告警：**0**（`compileDebugKotlin --rerun-tasks` 全量重编后统计）

## 未做 / 待确认

1. **L5 透镜弯折（AGSL 边缘位移采样）未做**。它的位移带约 8–14dp，MiniPlayer 只有 46dp 高，
   上下各一条会吃掉 28dp，读成"上下两条糊带"而不是透镜；只有在 ≥120dp 的面板上才成立。
2. **真机未复验**。深浅两套主题下的观感、以及 30dp 模糊对底栏上文字的吞没程度，都需要设备截图确认。
3. `GlassColors.fillTop` / `fillBottom` / `sheen` / `hairline` 仍无读者，属既有死字段。
4. 透明度滑块（iOS 27 的第 3 项）未接：Android 没有系统级"降低透明度"开关，若要落，
   应挂到 `data/prefs/SettingsStore` 自己的设置项上。

---

## 2026-10-22 撤回：上缘高光已删除

上文 L4（镜面高光）与"亮暗成套才是答案"的论证**已被实机否决**。用户看到包后要求「不显示亮边」。

- 删除：`rimStops()`、`RimStrokeWidth`、`GlassColors.edgeHighlight`（及三处构造赋值）、`drawEdgeOptics()` 里的第一条描边。
- 保留：L1 漫射 30dp、L2 鲜艳度 1.18、`innerRingStops()` 下缘暗环（上半个周长严格全透明）。
- 新结论：**边缘只压暗、不点高**。1dp 均匀白边与 2dp 垂直渐变高光被否的原因是同一个——只要上缘有亮线，这个浅金调色板就读成发光框，与是否均匀、是否成套无关。
- 产物与取证见 `.workbuddy/memory/2026-10-22.md`；对比图 `review/2026-10-22-glass-no-highlight.png`。
