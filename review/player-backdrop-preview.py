"""播放页背景「只取封面最下 20% + 渐变 + 模糊」的离线对照。

生产绘制在 `PlayerScreen.PlayerBackdrop`，配方来自 `CoverPalette`：

    渐变色点（backdropStopsOf）
        0        → palette.top          # 色带 80%–88% 的平均色，再按 TOP_VS_FIELD 压一档
        mirror   → palette.bottom       # 色带 80%–100% 的平均色（≤ FIELD_MAX_LUMINANCE）
        1        → darken(bottom, 0.70) # 底端再暗一档
    模糊层（API ≥ 31 才画）
        封面 80%–100% 这一段 → 纵向拉伸铺满整页 → blur(72dp) → alpha 0.40

这个脚本逐条照抄，左图是**旧做法**（顶部取封面最上 10%、下半页纯色平铺、没有模糊层），
右图是新做法。只换数值和层级，所以图上的差别就是改完之后的差别。

封面源：`screenshots/verify-04-player.png` 的上半部分 —— 那本来就是真机上的封面渲染，
比合成图更能看出"底部 20% 到底长什么样"。

用法：python review/player-backdrop-preview.py
输出：review/player-backdrop-preview.png（不入库）
"""

import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHOT = os.path.join(ROOT, "screenshots", "verify-04-player.png")
OUT = os.path.join(HERE, "player-backdrop-preview.png")

W, H = 1256, 2760
HERO = 1380            # heroHeight（屏高 50%）
MIRROR = HERO / H      # 镜面线 = 0.50

BAND_START = 0.80
TOP_END = 0.88
FIELD_MAX = 0.50
TOP_VS_FIELD = 0.75
TOP_MAX = 0.55
FALLOFF = 0.70
BLUR_DP = 72
TEXTURE_ALPHA = 0.40
PX_PER_DP = 1256 / 390.0


def lin(c: float) -> float:
    v = c / 255.0
    return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4


def srgb(v: float) -> float:
    v = 0.0 if v < 0 else (1.0 if v > 1 else v)
    return 12.92 * v if v <= 0.0031308 else 1.055 * v ** (1 / 2.4) - 0.055


def lum(rgb) -> float:
    return 0.2126 * lin(rgb[0]) + 0.7152 * lin(rgb[1]) + 0.0722 * lin(rgb[2])


def darken(rgb, k):
    return tuple(srgb(lin(c) * k) * 255 for c in rgb)


def shade(rgb, ceiling):
    """压到亮度上限之内（生产里的 `shade`）。"""
    l = lum(rgb)
    if l <= ceiling:
        return tuple(rgb)
    return darken(rgb, ceiling / l)


def band_mean(img, from_f: float, to_f: float):
    """一条纵向色带的算术平均，只剔 luma ≤ 3 与 ≥ 251 的像素。"""
    y0 = int(img.height * from_f)
    y1 = max(y0 + 1, int(img.height * to_f))
    region = img.crop((0, y0, img.width, y1)).convert("RGB")
    step_x = max(1, region.width // 24)
    step_y = max(1, (y1 - y0) // 24)
    total = [0, 0, 0]
    count = 0
    for y in range(0, region.height, step_y):
        for x in range(0, region.width, step_x):
            r, g, b = region.getpixel((x, y))
            if 4 <= (r * 299 + g * 587 + b * 114) // 1000 <= 250:
                total[0] += r
                total[1] += g
                total[2] += b
                count += 1
    if count == 0:
        return None
    return tuple(t / count for t in total)


def gradient(stops, width, height):
    """竖直播渐变，逐通道线性插值，与 `Brush.verticalGradient` 一致。"""
    img = Image.new("RGB", (width, height))
    px = img.load()
    for y in range(height):
        t = y / (height - 1)
        base = stops[-1][1]
        for i in range(len(stops) - 1):
            l0, c0 = stops[i]
            l1, c1 = stops[i + 1]
            if l0 <= t <= l1:
                f = 0.0 if l1 == l0 else (t - l0) / (l1 - l0)
                base = tuple(c0[j] + (c1[j] - c0[j]) * f for j in range(3))
                break
        for x in range(width):
            px[x, y] = tuple(int(round(c)) for c in base)
    return img


def blurred_band(cover, width, height):
    """封面最下 20% → 拉伸铺满 → 等效 blur(72dp)。

    PIL 对 1256×2760 直接做 σ≈232 的高斯模糊非常慢，所以先缩到 1/8 再模糊再放大 ——
    高斯模糊与缩放可交换，"缩小 → 模糊 → 放大"与在满尺寸模糊得到的低频结构一致。
    """
    band = cover.crop((0, int(cover.height * BAND_START), cover.width, cover.height))
    stretched = band.resize((width, height), Image.BILINEAR)
    small = stretched.resize((max(1, width // 8), max(1, height // 8)), Image.BILINEAR)
    sigma = BLUR_DP * PX_PER_DP / 8.0
    return small.filter(ImageFilter.GaussianBlur(sigma)).resize((width, height), Image.BILINEAR)


def draw_chrome(img, ink):
    """画上标题/艺人/进度条，用来判断下半页的可读性。"""
    draw = ImageDraw.Draw(img, "RGBA")
    try:
        big = ImageFont.truetype(r"C:\Windows\Fonts\msyhbd.ttc", 74)
        small = ImageFont.truetype(r"C:\Windows\Fonts\msyh.ttc", 46)
    except OSError:
        big = small = ImageFont.load_default()

    left, top = 72, int(HERO) + 190
    draw.text((left, top), "夜空中最亮的星", font=big, fill=ink + (255,))
    draw.text((left, top + 110), "逃跑计划", font=small, fill=ink + (190,))

    bar_y = top + 260
    draw.rounded_rectangle([left, bar_y, W - 140, bar_y + 12], radius=6, fill=ink + (80,))
    draw.rounded_rectangle([left, bar_y, left + (W - 212) * 0.38, bar_y + 12], radius=6, fill=ink + (230,))

    cy = bar_y + 210
    for cx, r in ((left + 60, 34), (left + 250, 58), (W - left - 60, 34)):
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], outline=ink + (220,), width=8)


def main():
    shot = Image.open(SHOT).convert("RGB")
    cover = shot.crop((0, 0, W, HERO))

    # ---- 旧做法：顶部取封面最上 10%，下半页纯色平铺，无模糊层 ----
    old_top_sample = band_mean(cover, 0.0, 0.10)
    old_bottom = shade(band_mean(cover, BAND_START, 1.0), FIELD_MAX)
    old_ceiling = min(lum(old_bottom) * TOP_VS_FIELD, TOP_MAX)
    old_top = shade(old_top_sample, old_ceiling)
    old = gradient([(0.0, old_top), (MIRROR, old_bottom), (1.0, old_bottom)], W, H)

    # ---- 新做法：两段色都只取最下 20%，底端再暗一档，叠模糊层 ----
    new_top_sample = band_mean(cover, BAND_START, TOP_END)
    new_bottom = shade(band_mean(cover, BAND_START, 1.0), FIELD_MAX)
    new_ceiling = min(lum(new_bottom) * TOP_VS_FIELD, TOP_MAX)
    new_top = shade(new_top_sample, new_ceiling)
    new = gradient(
        [(0.0, new_top), (MIRROR, new_bottom), (1.0, darken(new_bottom, FALLOFF))],
        W,
        H,
    )
    texture = blurred_band(cover, W, H)
    new = Image.blend(new, texture, TEXTURE_ALPHA)

    # ---- 两张都贴回封面，并画同样的文字/控件 ----
    panes = []
    for backdrop, tag in ((old, "旧做法"), (new, "新做法")):
        pane = backdrop.copy()
        pane.paste(cover, (0, 0))
        draw_chrome(pane, (246, 241, 232))
        bar = Image.new("RGB", (W, 120), (24, 24, 26))
        d = ImageDraw.Draw(bar)
        try:
            f = ImageFont.truetype(r"C:\Windows\Fonts\msyhbd.ttc", 40)
        except OSError:
            f = ImageFont.load_default()
        d.text((40, 24), f"{tag}  顶部 {lum(backdrop.getpixel((10, 4))):.3f}  底端 {lum(backdrop.getpixel((10, H - 4))):.3f}", font=f, fill=(225, 225, 225))
        d.text((40, 70), f"色带顶 #{'%02X%02X%02X' % tuple(int(round(c)) for c in (new_top if tag == '新做法' else old_top))}", font=f, fill=(150, 150, 150))
        panes.append((pane, bar))

    gap = 40
    sheet = Image.new("RGB", (2 * W + gap, H + 120), (24, 24, 26))
    for i, (pane, bar) in enumerate(panes):
        sheet.paste(pane, (i * (W + gap), 0))
        sheet.paste(bar, (i * (W + gap), H))

    sheet = sheet.resize((sheet.width // 2, sheet.height // 2), Image.LANCZOS)
    sheet.save(OUT)
    print("written", OUT, sheet.size)


if __name__ == "__main__":
    main()
