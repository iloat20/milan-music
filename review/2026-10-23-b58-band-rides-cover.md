# 第 58 批：倒影叠层必须跟着封面横移

日期：2026-10-23 · 版本 1.0.58 (vc 59) → **1.0.59 (vc 60)**
产物：`dist/milan-music-1.0.59-b58-20261023.apk`（2 484 584 B）

## 用户原话

> 现在切歌时，滑动封面，可以清楚的看到一个叠加层，绘制的是封面下半部分，这不对吧

## 诊断

`CoverReflection` 是 `Column` 里封面之后的**兄弟节点**，它按**屏幕坐标**画：内容永远是
当前封面的最后 128dp + 逐行镜像。第 57 批把内容修对了（逐行映射），但**位置**只跟纵向
走，横向一动不动。

于是横滑切歌时：封面被手指拖走，倒影带子原地不动 —— 屏幕上留下一条**和上方封面脱开的**
"封面下半部分"。静止时看不出来（两者重合），一动就露。

第二处叠加：轮播三页共用播放页那层 `heroFadeMask`，当前页**不能**淡底边（第 44 批：带内
覆盖率 `1 − Π(1−w_i) < 1`，封面不透明度是唯一不透底来源，淡了会透出背景色带）。所以隔壁页
滑进来时底边既没淡、也没有倒影兜底 → 镜面线上一条硬边。

## 改法

1. **位移同轴**：`offsetX` 上提到 `PlayerScreen`，轮播和叠层读**同一个** `Animatable`。
   - `CoverCarousel(offsetX = coverOffset, fadeNeighbourBottoms = reflection != null)`
   - `CoverReflection(modifier = Modifier.offset { IntOffset(pageTranslationPx(0, pitchPx, coverOffset.value).roundToInt(), 0) })`
   - 两者都用 `pageTranslationPx(slot, pitchPx, offsetPx) = slot*pitchPx + offsetPx`，slot 0
     没有 pitch 项 —— 所以叠层的横向位置严格等于手指位移，与屏宽、与页缝都无关。
2. **隔壁页自己淡底边**：`pageFadesBottom(slot, neighbourFades) = neighbourFades && slot != 0`，
   作用在 `CoverSlot` 的 `CoverArt` 上（`heroBottomFadeMask`，`HeroFadeOut` → `PhotoBottom`
   的 DstIn）。当前页永不淡；没有倒影时整页交给共用的 `heroFadeMask`（`fadeBottom =
   reflection == null`），两条路径互斥，不会叠两层。

## 曾被自己否掉的方案

把 `CoverLayout.PhotoBottom` 从 1.0 压到 0.88，让封面自己在底边淡出。**错**：倒影带子在
带内的覆盖率不是 1（逐层 over），封面一旦在底边淡成 0，带子里就会透出一条背景色带 ——
第 44 批的老问题，等于用旧 bug 换新 bug。所以只能**逐页**处理。

## 验证

### 单测（82 例全绿，本批 +4）

| 用例 | 守什么 |
|---|---|
| `the reflection rides the cover and not the page pitch` | 3 种屏宽 × 6 个位移，叠层位移 == 手指位移，且与 pitch 无关 |
| `one pitch carries the row exactly one page` | offset = ∓pitch 时隔壁页正好落正中 |
| `only the neighbour bottoms fade, never the current page` | slot 0 永不淡；无倒影时谁也不淡 |
| `the outgoing reflection clears the window before the next cover lands` | 退场位移 > 页宽（整页连同叠层一起出窗） |

红态验证：把 `pageTranslationPx` 改成 `offsetPx * 0.9f`（模拟"少走一截"）、把
`pageFadesBottom` 改成 `neighbourFades`（当前页也淡），**4 例全红且无连带**；还原后全绿。

### 离屏合成（`review/swipe-preview.py`）

按生产公式合成 4 个拖动位（0 / −30% / −70% / −100% pitch）：
`review/swipe-frames.png` —— 每一帧带子的左右边界与封面边界严丝合缝。
`review/swipe-before-after.png` —— 同一帧（−50% pitch）修前/修后并排：修前带子留在屏幕
原点，红领与「SWING」和上方封错开、还多一条竖缝；修后对齐。

### 门槛

- `lintRelease` → `No issues found.`
- 编译零 `w:`
- 包：`com.milan.music` / vc **60** / 1.0.59 / v2 ✅ v3 ✅ / `CN=Milan Music` / zipalign OK
- SHA-256：`f9b58740d90a138db8ffa905da7c7fd92a2fffdf5ad1b5ac024e448a4c210437`
- 进包取证：`mapping.txt` 里 `PlayerScreen$lambda$24$3$1$0(...):452:452 -> b` 返回
  `IntOffset`（就是叠层的 offset lambda，行号与源码逐字对应）；`pageTranslationPx(...):594`；
  `heroBottomFadeMask$lambda$1` 内联帧 575/578。`pageFadesBottom` 被 R8 内联（一行的
  boolean 函数，3 个调用点），mapping 里无独立行 —— 属预期。

## 遗留（真机再评）

叠层现在会跟着封面走到屏幕外，于是它有了**竖直的左右边界**（之前恒在屏幕两侧，看不见）。
`Modifier.blur(edgeTreatment = Rectangle)` 在图层边缘会把 alpha 收到 0，所以带子的右边界
在那 12dp 里会软一点，而它正上方的封面是硬切。只出现在拖动过程中、只在那一条窄带上。
若真机看着碍眼，下一批的解法是给阶梯层加横向 padding（画宽一点，再裁掉），不是改包络。
