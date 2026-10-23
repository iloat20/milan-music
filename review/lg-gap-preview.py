"""液态玻璃：把「材质差距」和「背景差距」分离开量。

做的事情：同一套材质参数，铺在同一块底图的不同位置上渲染，出 2×3 对比表。

  - 底图 A：参考图里 macOS 控制中心背后的真实壁纸（高对比、高彩度）
  - 底图 B：MilanMusic 播放页的专辑封面（有彩度、有纹理）
  - 底图 C：MilanMusic 播放页的色场（几乎平坦）

只依赖 PIL + numpy，不启动模拟器。参数与 Compose 侧一一对应，改这里等于改
GlassSurface.kt 之前的肉眼预演。
"""

from __future__ import annotations

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

REF = r"C:\Users\Administrator\.workbuddy\clipboard-images\clipboard-2026-10-21T23-43-30-669Z-741909f4.jpg"
HOME = "screenshots/verify-01-home.png"
PLAYER = "screenshots/verify-04-player.png"
OUT = "review/2026-10-22-apple-curve-applied.png"

CELL_W, CELL_H = 760, 292
PANEL_W, PANEL_H = 640, 110
PX_PER_DP = PANEL_H / 48.0  # 面板按 48dp 高渲染


# ---------------------------------------------------------------- 底图

def mac_wallpaper() -> Image.Image:
    """壁纸最左缘的一条干净竖带（x<220 没有控件），横向拉伸成整块底。

    不做镜像拼接：接缝会正好落在面板下面，读起来像玻璃自己有条竖边。
    拉伸 3.5 倍后再叠 26dp 模糊，结构上分辨不出来。
    """
    im = Image.open(REF).convert("RGB")
    strip = im.crop((0, 300, 220, 1010))
    flip = strip.transpose(Image.FLIP_LEFT_RIGHT)
    wide = Image.new("RGB", (strip.width * 8, strip.height))
    for k in range(4):
        wide.paste(strip if k % 2 == 0 else flip, (strip.width * 2 * k, 0))
        wide.paste(flip if k % 2 == 0 else strip, (strip.width * (2 * k + 1), 0))
    return wide


def ref_pills() -> Image.Image:
    """原始参考：两枚胶囊按同样的像素高度裁出，用来做肉眼对照。"""
    im = Image.open(REF).convert("RGB")
    return im.crop((150, 480, 520, 700))


def cover_art() -> Image.Image:
    """播放页的封面区域：真实彩度与纹理。"""
    im = Image.open(PLAYER).convert("RGB")
    return im.crop((0, 200, 1256, 1180))


def color_field() -> Image.Image:
    """播放页下半的色场：平坦，用来标出材质的观感上限。"""
    im = Image.open(PLAYER).convert("RGB")
    return im.crop((0, 1200, 1256, 2200))


# ---------------------------------------------------------------- 材质

def to_f(img: Image.Image) -> np.ndarray:
    return np.asarray(img, dtype=np.float32) / 255.0


def to_i(a: np.ndarray) -> Image.Image:
    return Image.fromarray(np.clip(a * 255.0 + 0.5, 0, 255).astype(np.uint8), "RGB")


def blur_f(a: np.ndarray, radius_px: float) -> np.ndarray:
    img = to_i(a).filter(ImageFilter.GaussianBlur(radius_px))
    return to_f(img)


def saturate(a: np.ndarray, s: float) -> np.ndarray:
    luma = a[..., 0] * 0.2126 + a[..., 1] * 0.7152 + a[..., 2] * 0.0722
    return np.clip(luma[..., None] + (a - luma[..., None]) * s, 0.0, 1.0)


def fog(a: np.ndarray, sigma: float, mul: float) -> np.ndarray:
    """Screen(σ) 再 Multiply(m)。

    展开后 out = m·(1-σ)·in + m·σ，就是一条斜率为 m(1-σ)、截距为 mσ 的直线：
    暗部被抬起、亮部被压下，中间调基本不动。物理上对应磨砂玻璃的漫射，
    观感上对应"玻璃内部既有提亮也有压暗"——参考图里正是这样，白幕做不到。
    """
    return (1.0 - (1.0 - a) * (1.0 - sigma)) * mul


def tint(a: np.ndarray, rgb: tuple[int, int, int], alpha: float) -> np.ndarray:
    """source-over：把底色朝 rgb 插值。alpha 就是 Compose 里的 Color.copy(alpha)。"""
    c = np.array(rgb, dtype=np.float32) / 255.0
    return a * (1.0 - alpha) + c * alpha


def sdf_rounded(w: int, h: int, radius: float) -> np.ndarray:
    """圆角矩形有向距离场，形状内部为负，值等于到边界的像素距离。"""
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    px = xs - (w - 1) / 2.0
    py = ys - (h - 1) / 2.0
    qx = np.abs(px) - ((w - 1) / 2.0 - radius)
    qy = np.abs(py) - ((h - 1) / 2.0 - radius)
    outside = np.hypot(np.maximum(qx, 0), np.maximum(qy, 0))
    inside = np.minimum(np.maximum(qx, qy), 0)
    return outside + inside - radius


def band(depth: np.ndarray, inner: float, outer: float, soft: float) -> np.ndarray:
    """depth = 到边界的距离（正值在内部）。取 [inner, outer] 这一段，两端 soft 过渡。"""
    lo = np.clip((depth - inner) / max(soft, 1e-3), 0, 1)
    hi = np.clip((outer - depth) / max(soft, 1e-3), 0, 1)
    return np.minimum(lo, hi)


def lens(a: np.ndarray, w: int, h: int, scale: float) -> np.ndarray:
    """绕中心放大 scale 倍：位移量 = r*(scale-1)，用来伪造边缘折射。"""
    img = to_i(a)
    cw, ch = w / scale, h / scale
    box = ((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2)
    return to_f(img.crop((int(box[0]), int(box[1]), int(box[2]), int(box[3]))).resize((w, h), Image.BICUBIC))


def paint(backdrop: np.ndarray, spec: dict) -> np.ndarray:
    w, h = PANEL_W, PANEL_H
    r_px = spec["blur"] * PX_PER_DP

    # Haze 的做法：先按 backdrop 取景、模糊、再叠色效。这里先整幅模糊再裁。
    b = blur_f(backdrop, r_px)
    b = saturate(b, spec["sat"])

    bh, bw = b.shape[:2]
    y0 = (bh - h) // 2
    x0 = (bw - w) // 2
    panel = b[y0:y0 + h, x0:x0 + w].copy()

    # 体：磨砂漫射（压对比），或者退化成一层实色薄幕。
    if spec.get("fog"):
        panel = fog(panel, *spec["fog"])
    if spec["body_alpha"] > 0:
        panel = tint(panel, spec["body"], spec["body_alpha"])

    depth = -sdf_rounded(w, h, h / 2.0).astype(np.float32) * -1  # 内部为正值距离
    depth = sdf_rounded(w, h, h / 2.0)

    # 折射带：只在边缘几 dp 内把背景放大一点，模拟玻璃被弯折。
    if spec.get("lens_scale"):
        zoom = lens(b[y0:y0 + h, x0:x0 + w], w, h, spec["lens_scale"])
        m = band(depth, 0, spec["lens_width"] * PX_PER_DP, 1.5)[..., None]
        panel = panel * (1 - m) + zoom * m

    # 内圈暗带：先画，压在亮线底下。
    sh = band(depth, spec["hl_w"] * PX_PER_DP, (spec["hl_w"] + spec["sh_w"]) * PX_PER_DP, 1.2)[..., None]
    shadow_rgb = np.array(spec["shadow_rgb"], np.float32) / 255.0
    panel = panel * (1 - sh * spec["sh_alpha"]) + shadow_rgb * (sh * spec["sh_alpha"])

    # 外圈亮线。
    hl = band(depth, 0, spec["hl_w"] * PX_PER_DP, 1.0)[..., None]
    hl_rgb = np.array(spec["hl_rgb"], np.float32) / 255.0
    panel = panel * (1 - hl * spec["hl_alpha"]) + hl_rgb * (hl * spec["hl_alpha"])

    out = backdrop.copy()
    out[y0:y0 + h, x0:x0 + w] = panel
    return out


# 旧：组件里写死的暖白薄幕（深色主题），主题里的 fillTop/fillBottom 从未被读取。
OLD = dict(
    blur=30, sat=1.18,
    body=(0xF8, 0xF1, 0xE2), body_alpha=0.06,
    hl_w=1.0, hl_alpha=0.313, hl_rgb=(255, 255, 255),
    sh_w=3.0, sh_alpha=0.251, shadow_rgb=(0, 0, 0),
)

# 第 16 批：Color.kt 的 0x404C4238 / 0x474C4238（斜率 0.75）
MID = dict(
    blur=30, sat=1.18,
    body=(0x4C, 0x42, 0x38), body_alpha=(0x40 + 0x47) / 2 / 255,
    hl_w=1.0, hl_alpha=0.313, hl_rgb=(255, 255, 255),
    sh_w=3.0, sh_alpha=0.251, shadow_rgb=(0, 0, 0),
)

# 第 18 批（本次）：0x6B5B4F43 / 0x6B574B40，饱和度 1.72
# L = 0.58*Lb + 34  =>  单层幕 a=0.42、幕色亮度 81；色度按 1/0.58 补回
NEW = dict(
    blur=30, sat=1.72,
    body=(0x5B, 0x4F, 0x43), body_alpha=(0x6B + 0x6B) / 2 / 255,
    hl_w=1.0, hl_alpha=0.313, hl_rgb=(255, 255, 255),
    sh_w=3.0, sh_alpha=0.251, shadow_rgb=(0, 0, 0),
)

FONT_PATH = "C:/Windows/Fonts/msyh.ttc"


def label(canvas, xy, text, size=24, fill=(238, 234, 226)):
    canvas.text(xy, text, font=ImageFont.truetype(FONT_PATH, size), fill=fill)


def lum(a: np.ndarray) -> np.ndarray:
    return a[..., 0] * .2126 + a[..., 1] * .7152 + a[..., 2] * .0722


def panel_mean(img: np.ndarray, inset: int = 8) -> np.ndarray:
    h, w = PANEL_H, PANEL_W
    y0 = (img.shape[0] - h) // 2
    x0 = (img.shape[1] - w) // 2
    return img[y0 + inset:y0 + h - inset, x0 + inset:x0 + w - inset].reshape(-1, 3).mean(axis=0)


def sweep(spec: dict, levels=range(0, 256, 17)) -> tuple[float, float]:
    """跨一组平坦灰底扫一遍，读出「背景亮度 -> 面板亮度」的斜率与截距。

    必须跨底图取点、不能在单块面板内部拟合：Apple 的 0.58 / 34 就是三种底图
    （近黑桌面、紫壁纸、蓝色星场）的采样结果。同一块面板模糊之后亮度方差接近
    零，在内部强行拟合出来的是噪声（试过，会得到斜率 0.05 这种无意义的数）。
    """
    xs, ys = [], []
    for lv in levels:
        base = np.full((CELL_H, CELL_W, 3), lv / 255.0, dtype=np.float32)
        out = paint(base, spec)
        xs.append(lv)
        ys.append(float(lum(panel_mean(out, inset=10)) * 255))
    k, c = np.polyfit(np.array(xs), np.array(ys), 1)
    return float(k), float(c)


def chroma_gain(spec: dict, patch=(0.75, 0.25, 0.20)) -> float:
    """色度透过率：在饱和色块上量 (max-min)/max 的变化倍数。目标 1.00。"""
    base = np.zeros((CELL_H, CELL_W, 3), dtype=np.float32)
    base[:] = np.array(patch, dtype=np.float32)
    out = paint(base, spec)
    a, b = panel_mean(base, 10), panel_mean(out, 10)
    ca = (a.max() - a.min()) / max(a.max(), 1e-6)
    cb = (b.max() - b.min()) / max(b.max(), 1e-6)
    return float(cb / max(ca, 1e-6))


def step_wedge() -> Image.Image:
    """12 级灰阶楔：背景亮度是已知量，因此面板上的读数直接就是传递曲线。"""
    n = 12
    im = Image.new("RGB", (CELL_W * 2, CELL_H))
    d = ImageDraw.Draw(im)
    for i in range(n):
        lv = round(4 + (250 - 4) * i / (n - 1))
        x0 = i * im.width // n
        x1 = (i + 1) * im.width // n
        d.rectangle([x0, 0, x1, im.height], fill=(lv, lv, lv))
    return im


def pick_window(src: Image.Image, ox: int) -> Image.Image:
    """在底图上挑一扇"结构最明显"的取景窗，让玻璃真的透出东西来。

    否则面板很可能正好压在平坦区域上，测出来的差距反映的是取景运气而不是材质。
    """
    best = None
    for x in range(ox, max(src.width - CELL_W, ox) + 1, 40):
        for y in range(0, max(src.height - CELL_H, 0) + 1, 40):
            win = src.crop((x, y, x + CELL_W, y + CELL_H))
            a = to_f(win)
            lum = a[..., 0] * .2126 + a[..., 1] * .7152 + a[..., 2] * .0722
            score = float(lum.std())
            if best is None or score > best[0]:
                best = (score, x, y)
    _, x, y = best
    return src.crop((x, y, x + CELL_W, y + CELL_H))


def stats(before: np.ndarray, after: np.ndarray) -> tuple[float, float, float]:
    """面板内部（去掉边缘 8px）相对「该处模糊+提纯、尚未加材质」的亮度比与饱和度差。

    基准必须取"模糊后"，否则模糊本身就能把亮度改掉 2 倍，测到的是取景位置而不是材质。
    """
    w, h = PANEL_W, PANEL_H
    y0 = (before.shape[0] - h) // 2
    x0 = (before.shape[1] - w) // 2
    inset = 8
    a = before[y0 + inset:y0 + h - inset, x0 + inset:x0 + w - inset]
    b = after[y0 + inset:y0 + h - inset, x0 + inset:x0 + w - inset]
    lum = lambda x: float((x[..., 0] * .2126 + x[..., 1] * .7152 + x[..., 2] * .0722).mean())
    def sat(x):
        mx = x.max(axis=-1); mn = x.min(axis=-1)
        return float((np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0)).mean())
    return lum(b) / max(lum(a), 1e-6), sat(b) - sat(a), lum(b) - lum(a)


def main():
    rows = [
        ("底图 D：12 级灰阶楔（读数即传递曲线）", step_wedge(), 0),
        ("底图 A：macOS 壁纸（高对比彩色）", mac_wallpaper(), 300),
        ("底图 B：MilanMusic 播放页专辑封面", cover_art(), 60),
        ("底图 C：MilanMusic 播放页色场（平坦）", color_field(), 0),
    ]
    row_specs = [
        ("第 16 批：0x404C4238", MID),
        ("第 18 批：0x6B5B4F43（对齐 Apple）", NEW),
    ]

    # 先测，再画：图注里的数字必须来自这一次的实际扫描，不能写死。
    measured = {}
    print("=" * 92)
    print(f"{'材质':40s} {'斜率':>6s} {'截距':>7s} {'黑底L9':>7s} {'RGB色度':>8s}")
    print(f"{'Apple 实测（macOS 26 控制中心）':40s} {0.58:6.2f} {34.0:7.1f} {39.2:7.1f} {1.00:8.2f}")
    for col_title, spec in row_specs:
        k, c = sweep(spec)
        cg = chroma_gain(spec)
        measured[col_title] = (k, c, cg)
        print(f"{col_title:40s} {k:6.2f} {c:7.1f} {k * 9 + c:7.1f} {cg:8.2f}")
    print("=" * 92)

    gap = 18
    head = 66
    label_h = 36
    sheet_w = CELL_W * 2 + gap * 3
    ref_h = 224
    line_h = 27
    cap_lines = [
        "Apple 实测基准（macOS 26 控制中心，近黑桌面 / 紫壁纸 / 蓝色星场三处采样）：",
        "      L(玻璃) = 0.58 · L(背景) + 34",
        "      斜率 0.58  → 只让 58% 的背景明暗差透过来（压对比）",
        "      截距 34    → 黑底被抬起 4.3 倍（L 9 → 39）",
        "      饱和度 1 / 0.58 = 1.72 → 把被压掉的色度原样补回",
        "",
        "本次两块面板的实测（同一套边缘参数，只换体的参数）：",
    ]
    for col_title, spec in row_specs:
        k, c, cg = measured[col_title]
        cap_lines.append(f"      {col_title:26s} 斜率 {k:.2f}  截距 {c:.1f}  色度 ×{cg:.2f}")
    cap_lines += [
        "",
        "灰阶楔那一行最直白：右列的 12 级灰被压成少数几段，",
        "左列仍分得清层次 —— 这就是「玻璃」与「面纱」的区别，",
        "玻璃压对比，面纱只改平均亮度。",
    ]
    cap_h = len(cap_lines) * line_h
    sheet_h = head + len(rows) * (label_h + CELL_H + gap) + label_h + max(ref_h, cap_h) + 28
    sheet = Image.new("RGB", (sheet_w, sheet_h), (16, 15, 13))
    d = ImageDraw.Draw(sheet)
    label(d, (24, 16), "液态玻璃：按 Apple 实测曲线落地后的观感（48dp 面板，边缘参数两列相同）", size=27)

    y = head
    for row_title, backdrop_src, offset in rows:
        label(d, (24, y + 6), row_title, size=22, fill=(206, 198, 184))
        y += label_h
        xs = 24
        for col_title, spec in row_specs:
            bd = pick_window(backdrop_src, offset)
            bda = to_f(bd)
            out = paint(bda, spec)
            ref_panel = blur_f(bda, spec["blur"] * PX_PER_DP)
            ratio, dsat, dlum = stats(ref_panel, out)
            print(f"{row_title:30s} {col_title:40s} 亮度 ×{ratio:.2f}  Δ饱和 {dsat:+.3f}")
            sheet.paste(to_i(out), (xs, y))
            d.rectangle([xs, y, xs + CELL_W - 1, y + CELL_H - 1], outline=(52, 48, 42))
            label(d, (xs + 12, y + 10), col_title, size=20, fill=(236, 230, 218))
            label(d, (xs + 12, y + CELL_H - 32), f"亮度 ×{ratio:.2f}   Δ饱和 {dsat:+.2f}",
                  size=17, fill=(196, 190, 178))
            xs += CELL_W + gap
        y += CELL_H + gap

    label(d, (24, y + 4), "原始参考（同像素高度，非重渲染）", size=22, fill=(206, 198, 184))
    y += label_h
    ref = ref_pills().resize((int(ref_pills().width * ref_h / ref_pills().height), ref_h), Image.LANCZOS)
    sheet.paste(ref, (24, y))
    label(d, (24 + ref.width + 26, y + 4), "\n".join(cap_lines), size=19, fill=(202, 196, 184))
    sheet.save(OUT)
    print("\nwrote", OUT, sheet.size)


if __name__ == "__main__":
    main()
