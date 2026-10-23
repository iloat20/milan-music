"""首页背景「再暗一档」的四档离线对照。

生产绘制在 `HomeScreen.AmbientBackdrop`：
    - 竖直播 `Brush.verticalGradient(colorStops = stops)`，逐通道线性插值；
    - 三枚 `Brush.radialGradient(color → Transparent)`，中心 = (w·cx, h·cy)，
      半径 = **屏宽** × radius（不是对角线）。
这个脚本逐条照抄，只换 `Color.kt` 里的那组数值，所以图上的差别就是改数值后的差别。

压暗方式：在**线性亮度**上乘同一个系数 k，再转回 sRGB。这样色相与彩度比不变，
是做「同一块颜色暗一档」，而不是「往灰里推」。

    NOW  现状                   k = 1.00
    A    再暗一档               k = 0.94（浅）  / 0.85（深）
    B    再暗两档               k = 0.88        / 0.72
    C    再暗三档               k = 0.82        / 0.60
    D    只压光晕，底色不动      glow alpha × 0.65

用法：python review/backdrop-darker-preview.py
输出：review/backdrop-darker-preview.png（不入库）
"""

import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "backdrop-darker-preview.png")

W, H = 300, 560
GAP = 14
LABEL_H = 54


def _lin(c: int) -> float:
    v = c / 255.0
    return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4


def _srgb(v: float) -> int:
    v = 0.0 if v < 0 else (1.0 if v > 1 else v)
    return round(255 * (12.92 * v if v <= 0.0031308 else 1.055 * v ** (1 / 2.4) - 0.055))


def lum(h: int) -> float:
    return 0.2126 * _lin((h >> 16) & 255) + 0.7152 * _lin((h >> 8) & 255) + 0.0722 * _lin(h & 255)


def scale(h: int, k: float) -> int:
    r, g, b = (h >> 16) & 255, (h >> 8) & 255, h & 255
    return (_srgb(_lin(r) * k) << 16) | (_srgb(_lin(g) * k) << 8) | _srgb(_lin(b) * k)


def mix(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def rgb(h):
    return ((h >> 16) & 255, (h >> 8) & 255, h & 255)


def hexs(h):
    return f"#{h:06X}"


# ---- 生产数值（与 ui/theme/Color.kt 逐字对应）--------------------------------
LIGHT_STOPS = [(0.00, 0xF7EDD3), (0.55, 0xFBF4E4), (1.00, 0xFDFAF2)]
LIGHT_GLOWS = [(0xF2E4C2, 0.36, 0.16, 0.10, 0.80),
               (0xECDCB4, 0.30, 0.92, 0.28, 0.64),
               (0xE5D3A8, 0.26, 0.42, 0.86, 0.74)]

DARK_STOPS = [(0.00, 0x261D0E), (0.50, 0x1A1308), (1.00, 0x100C06)]
DARK_GLOWS = [(0x8A6C2A, 0.18, 0.16, 0.10, 0.80),
              (0x7A5F22, 0.15, 0.92, 0.28, 0.64),
              (0x6B521E, 0.13, 0.42, 0.86, 0.74)]


def render(stops, glows, k=1.0, glow_k=1.0):
    """逐像素合成：先竖直播渐变，再按 Compose 的 over 语义叠三枚径向光晕。"""
    img = Image.new("RGB", (W, H))
    px = img.load()
    st = [(loc, rgb(scale(c, k))) for loc, c in stops]
    gl = [(rgb(c), a * glow_k, cx * W, cy * H, r * W) for c, a, cx, cy, r in glows]

    for y in range(H):
        t = y / (H - 1)
        base = None
        for i in range(len(st) - 1):
            l0, c0 = st[i]
            l1, c1 = st[i + 1]
            if l0 <= t <= l1:
                base = mix(c0, c1, 0.0 if l1 == l0 else (t - l0) / (l1 - l0))
                break
        if base is None:
            base = st[-1][1]
        for x in range(W):
            out = base
            for col, a, gx, gy, gr in gl:
                d = ((x - gx) ** 2 + (y - gy) ** 2) ** 0.5
                if d >= gr:
                    continue
                al = a * (1 - d / gr)  # Compose 的径向渐变在 stop 之间线性插值
                out = mix(out, col, al)
            px[x, y] = out
    return img


def font(size):
    for path in (r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\msyhbd.ttc"):
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                pass
    return ImageFont.load_default()


def main():
    light = [
        ("现状", dict(k=1.00)),
        ("A 再暗一档", dict(k=0.94)),
        ("B 再暗两档", dict(k=0.88)),
        ("C 再暗三档", dict(k=0.82)),
        ("D 只压光晕", dict(k=1.00, glow_k=0.65)),
    ]
    dark = [
        ("现状", dict(k=1.00)),
        ("A 再暗一档", dict(k=0.85)),
        ("B 再暗两档", dict(k=0.72)),
        ("C 再暗三档", dict(k=0.60)),
        ("D 只压光晕", dict(k=1.00, glow_k=0.65)),
    ]

    cols = len(light)
    sheet = Image.new("RGB", (cols * W + (cols - 1) * GAP, 2 * (H + LABEL_H) + GAP), (28, 28, 30))
    draw = ImageDraw.Draw(sheet)
    f_title, f_small = font(15), font(12)

    for row, (stops, glows, plan, tag) in enumerate(
        ((LIGHT_STOPS, LIGHT_GLOWS, light, "浅色主题"), (DARK_STOPS, DARK_GLOWS, dark, "深色主题"))
    ):
        y0 = row * (H + LABEL_H + GAP)
        for i, (name, kw) in enumerate(plan):
            x0 = i * (W + GAP)
            sheet.paste(render(stops, glows, **kw), (x0, y0))
            top = scale(stops[0][1], kw["k"])
            bot = scale(stops[-1][1], kw["k"])
            ink = (235, 235, 235)
            draw.text((x0 + 8, y0 + H + 4), f"{tag}  {name}", font=f_title, fill=ink)
            draw.text(
                (x0 + 8, y0 + H + 24),
                f"{hexs(top)} → {hexs(bot)}   Y {lum(top):.3f}/{lum(bot):.3f}",
                font=f_small,
                fill=(150, 150, 150),
            )

    sheet.save(OUT)
    print("written", OUT, sheet.size)


if __name__ == "__main__":
    main()
