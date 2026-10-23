# 第 57 批：倒影按像素显式映射（修"封面出现两次"）

2026-10-23 · versionName 1.0.58 / versionCode 59

## 用户报告

> 你没看到有重复的bug吗，封面出现了两次

真机截图 873×1920：封面下方出现**倒着的面具、倒着的 SWING 字样、倒着的脸**。

## 取证：先把专辑原图取出来

`review/sim-cover.png` = 从 `screenshots/library.jpg` 里"1984 / Swing"那一行的方形
缩略图裁出（167×167），按封面同一套取景（铺满 873×960、纵向 5%、横向居中裁）放到
873×960。这张图的构图：面具头 + 人脸在 12%~48%，SWING logo 在 78%~86%，其余是衬衫、
红围巾、灰背景。**底部四分之一没有任何脸。**

把原图 690..930 行与真机同 y 区段并排（`review/cmp-band-art.png`）：

| | 690..930 行 |
|---|---|
| 原图 | 红围巾 + 倒置的 SWING + 白衬衫 |
| 真机 | 面具头（带红点）+ 倒置的 SWING + 人脸 |

真机那一段不是原图对应行 —— 它是原图**中段**（12%~48%）的镜像。结论：

1. 倒影（不是封面本体）把内容搬下来了；
2. 搬下来的不是"封面最底一截"，而是"封面的中段"，所以脸和面具原样再来一次；
3. 因此这不是"倒影太长"能解释完的，**内容定位本身就是滑的**。

## 根因

旧的画法把定位交给 `Image(contentScale = HeroContentScale, alignment = BottomCenter)`。
`CoverLayout.HeroMirrorAlignment` 的注释自己写明了那条数学的前提：

> `R(y) = M(y − (框高 − H'))`，要求缩放后高度 `H'` 与框高相等。

倒影节点的高度是 `maxHeight − heroHeight`（873×1920 上是 436dp），封面框高是 429dp，
两者不等；一旦 `computeScaleFactor` 走 `widthRatio` 分支（竖版源图必然走），
`BottomCenter` 就会把整体下移/裁掉整截 —— 镜像整体滑动，接缝处取到的自然不是封面
最后一行。真机截图里"倒着的脸"，就是滑动量累积到把中段搬到缝下的结果。

## 修法：把定位从"隐式对齐"换成"逐行映射"

`CoverReflection.kt`

```kotlin
internal fun coverRowAt(nodeY: Float, band: Float, heroHeight: Float): Float =
    if (nodeY <= band) heroHeight - band + nodeY      // 带子里：封面倒序最后一段
    else heroHeight - (nodeY - band)                  // 镜下：一行一行退回
```

- `RampLayerContent` 不再用 `Image`，改 `Canvas` + `drawImage(srcOffset, srcSize,
  dstOffset, dstSize)`：源矩形由 `coverFit()`（`computeScaleFactor` 的同源换算 +
  TopCenter 偏移）算出，目标矩形就是节点矩形，中间没有任何"对齐"自由度。
- 翻折用画布变换 `translate(0, 2·band) · scale(1, −1)`，绕镜面线做，不经过 layout 节点。
- 镜像只翻折 `min(height, heroHeight)` 那么长：再多就是"镜像的镜像"，封面顶会第二次正着出现。
- 可见长度 `FadeEnd` 150dp → **104dp**（约封面高度四分之一），`FadeHold` 56 → 24dp：
  翻下去的只剩衬衫下摆与背景。

## 门禁

- `ReflectionLayersTest` 25 例（全套 78 例）全绿，新增 6 例：
  - 缝上那一行必须映射到封面最后一行；
  - 带子里是封面倒序最后一段；
  - **镜下每下一行封面恰好退一行（步长恒为 1，420 行逐行验）**；
  - 越界不许取到负行号；
  - `coverFit` 纵向偏移恒为 0、横向各裁 2.3%；
  - 可见倒影 ≤ 封面高度 26%。
- 红态验过：把 `coverRowAt` 的镜下分支改成 `− 300f` → 2 例失败（"walks back exactly
  one row per row"、"bottoms out at the cover's top"）。
- `lintRelease`：No issues found。
- APK：`com.milan.music` / vc 59 / `CN=Milan Music` / v2+v3 / zipalign OK /
  2 484 580 B / SHA-256 `f92d6f90…`。
- 进包取证：`mapping.txt` 里有 `CoverFit`、`drawCoverRows(...):492`（源码行号对应新文件）。

## 预览脚本

`review/reflection-new-preview.py` —— 按 `coverRowAt` + 阶梯 + 衰减逐像素渲染，
与真机同屏对照（`review/cmp-new-vs-device.png`）。

## 下一步的旋钮

| 现象 | 调 |
|---|---|
| 还看到第二张脸 | `FadeEnd` 104 → 72dp |
| 一上来就没了、看不出倒影 | `FadeEnd` 104 → 130dp |
| 过渡带糊得太狠 | `BlurRamp` 镜面那一级 12dp 往下调 |
