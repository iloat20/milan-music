"""离线复刻 GlassSurface 的新材质（T1 漫射/鲜艳度 + T2 边缘光学），用于无设备时的视觉核验。

只复刻"材质"这一层：模糊半径、饱和度、体幕、上下缘描边、内圈暗环。不复刻 Haze 的
降采样、噪点、elevation 阴影——那三项在视觉对比里不是变量。

与 Kotlin 侧的对应关系：
  innerRingStops()  -> inner_ramp()
  （rimStops() 已于 2026-10-22 撤除，rim_ramp() 只用来渲染"上一批"做对比）
  GlassBlurRadius 26/30 dp
  GlassSaturation 1.18
  深色主题玻璃体 #F8F1E2 alpha 0.06 -> 0.04

用法：python review/glass-t2-preview.py
"""

from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter, ImageFont

SRC = "screenshots/verify-01-home.png"
OUT = "review/2026-10-22-glass-double-edge.png"
FONT = "C:/Windows/Fonts/msyh.ttc"

# 1256 px / 390 dp
PX_PER_DP = 1256 / 390

# 深色主题下的 GlassColors
EDGE_HIGHLIGHT = 0xB3     # 旧版的渐变峰值，供 mode=full 复刻
EDGE_SHADOW = 0x40
# 本次"整圈"方案直接用最终值（深色主题）
EDGE_HIGHLIGHT_ALPHA = 0.70
EDGE_SHADOW_ALPHA = 0.18

# 面板在条带内的位置（x, y, w, h）：贴近真实底栏的 14dp 边距、28dp 圆角、约 59dp 高。
PANEL = (45, 22, 1166, 190)
RADIUS = round(28 * PX_PER_DP)


def dp(value: float) -> int:
    return round(value * PX_PER_DP)


def lerp_stops(stops, t):
    if t <= stops[0][0]:
        return stops[0][1]
    for (o0, v0), (o1, v1) in zip(stops, stops[1:]):
        if t <= o1:
            span = o1 - o0
            k = 0.0 if span == 0 else (t - o0) / span
            return v0 + (v1 - v0) * k
    return stops[-1][1]


def rounded_mask(size, radius, inset=0):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [inset, inset, size[0] - 1 - inset, size[1] - 1 - inset],
        radius=max(radius - inset, 0),
        fill=255,
    )
    return mask


def ring_mask(size, radius, thickness, inner_inset=0):
    outer = rounded_mask(size, radius, inner_inset)
    inner = rounded_mask(size, radius, inner_inset + thickness)
    return ImageChops.subtract(outer, inner)


def ramp(size, stops):
    column = Image.new("L", (1, size[1]))
    pixels = column.load()
    for y in range(size[1]):
        fraction = y / max(size[1] - 1, 1)
        pixels[0, y] = int(round(lerp_stops(stops, fraction)))
    return column.resize(size)


def rim_ramp(size, scale=1.0):
    peak = min(round(EDGE_HIGHLIGHT * scale), 255)
    deep = min(round(EDGE_SHADOW * scale), 255)
    return ramp(
        size,
        [
            (0.0, peak),
            (0.10, peak * 0.50),
            (0.40, 0),
            (0.72, deep * 0.25),
            (1.0, deep),
        ],
    )


def inner_ramp(size, scale=1.0):
    deep = min(round(EDGE_SHADOW * scale), 255)
    return ramp(
        size,
        [
            (0.0, 0),
            (0.45, 0),
            (0.78, deep * 0.30),
            (1.0, deep * 0.65),
        ],
    )


def body_ramp(size):
    return ramp(size, [(0.0, round(0.06 * 255)), (1.0, round(0.04 * 255))])


def paint_glass(backdrop, blur_dp, saturation, edge_scale, mode, hl=0.70, sh=0.18):
    """在一块背景上画一块玻璃面板，返回与该背景同尺寸的图。"""
    width, height = backdrop.size
    x, y, w, h = PANEL
    panel_size = (w, h)

    canvas = backdrop.copy()
    blurred = ImageEnhance.Color(
        backdrop.filter(ImageFilter.GaussianBlur(dp(blur_dp))),
    ).enhance(saturation)

    clip = rounded_mask(panel_size, RADIUS)

    canvas.paste(blurred.crop((x, y, x + w, y + h)), (x, y), clip)

    wash = Image.new("RGB", panel_size, (0xF8, 0xF1, 0xE2))
    canvas.paste(wash, (x, y), ImageChops.multiply(clip, body_ramp(panel_size)))

    if mode == "none":
        return canvas

    if mode == "full":
        rim = ring_mask(panel_size, RADIUS, thickness=dp(1.0))
        canvas.paste(
            Image.new("RGB", panel_size, (255, 255, 255)),
            (x, y),
            ImageChops.multiply(rim, rim_ramp(panel_size, edge_scale)),
        )
        inner = ring_mask(panel_size, RADIUS, thickness=dp(2.5), inner_inset=dp(1.0))
        canvas.paste(
            Image.new("RGB", panel_size, (0, 0, 0)),
            (x, y),
            ImageChops.multiply(inner, inner_ramp(panel_size, edge_scale)),
        )

    if mode == "lower":
        inner = ring_mask(panel_size, RADIUS, thickness=dp(2.5), inner_inset=dp(1.0))
        canvas.paste(
            Image.new("RGB", panel_size, (0, 0, 0)),
            (x, y),
            ImageChops.multiply(inner, inner_ramp(panel_size, edge_scale)),
        )

    if mode == "double":
        # 本次：整圈 3dp 暗带 + 整圈 1dp 亮线，亮线压在外沿上
        band = ring_mask(panel_size, RADIUS, thickness=dp(3.0))
        canvas.paste(
            Image.new("RGB", panel_size, (0, 0, 0)),
            (x, y),
            ImageChops.multiply(band, Image.new("L", panel_size, round(sh * 255))),
        )
        line = ring_mask(panel_size, RADIUS, thickness=dp(1.0))
        canvas.paste(
            Image.new("RGB", panel_size, (255, 255, 255)),
            (x, y),
            ImageChops.multiply(line, Image.new("L", panel_size, round(hl * 255))),
        )

    return canvas


def label(canvas, xy, text, size=26, fill=(240, 236, 226)):
    font = ImageFont.truetype(FONT, size)
    ImageDraw.Draw(canvas).text(xy, text, font=font, fill=fill)


def main():
    source = Image.open(SRC).convert("RGB")

    # 一条同时包含封面与两行文字的列表带：玻璃盖在第二行上，能直接看出漫射抹掉了什么。
    strip = source.crop((0, 340, 1256, 680))
    strip.save("review/_probe-strip.png")

    variants = [
        ("昨夜：只有下半圈暗环（无亮线）", 30, 1.18, 1.0, "lower", 0.0, 0.18),
        ("整圈双层 · 亮线 31% / 暗带 25%（参考图的相对强度）", 30, 1.18, 1.0, "double", 0.31, 0.25),
        ("整圈双层 · 亮线 45% / 暗带 25%", 30, 1.18, 1.0, "double", 0.45, 0.25),
        ("整圈双层 · 亮线 70% / 暗带 18%（刚才那版，过曝）", 30, 1.18, 1.0, "double", 0.70, 0.18),
    ]

    width = 1256
    header = 54
    label_h = 40
    row_h = 340
    gap = 24
    zoom_label = 40
    zoom_w, zoom_h = 280, 240

    height = (
        header
        + len(variants) * (label_h + row_h + gap)
        + zoom_label
        + zoom_h * 2
        + 20
    )
    sheet = Image.new("RGB", (width, height), (18, 16, 13))
    label(sheet, (28, 14), "液态玻璃边缘三代对比：外亮内暗才是玻璃切面（深色主题，真实截图做底）", size=28)

    rendered = []
    cursor = header
    for title, blur, sat, edge, mode, hl, sh in variants:
        label(sheet, (28, cursor + 8), title)
        panel = paint_glass(strip, blur, sat, edge, mode, hl, sh)
        sheet.paste(panel, (0, cursor + label_h))
        rendered.append(panel)
        cursor += label_h + row_h + gap

    # 左端圆角 2× 放大：边缘高光与内圈暗环都在这里可见。
    label(sheet, (28, cursor + 6), "左端圆角 2× 放大：左=亮线 31% · 右=亮线 45%", size=24)
    cursor += zoom_label
    zoom_box = (20, 2, 20 + zoom_w, 2 + zoom_h)
    for index, x in ((1, 20), (2, 636)):
        zoomed = rendered[index].crop(zoom_box).resize((zoom_w * 2, zoom_h * 2), Image.LANCZOS)
        sheet.paste(zoomed, (x, cursor))

    sheet.save(OUT)
    print("wrote", OUT, sheet.size)


if __name__ == "__main__":
    main()
