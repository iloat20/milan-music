"""深色主题压暗的离线对照。

只画深色，画的是 `HomeScreen.AmbientBackdrop` 的绘制方式（竖直播渐变 + 三枚径向光晕，
半径按**屏宽**算）＋ `DarkColors` 的表面层级（搜索框 / 列表封面块 / 迷你播放器 / 底栏），
文字用 `Moonlight`、次要文字用 `NightMuted`。

压暗方式：在**线性亮度**上乘系数再转回 sRGB —— 色相与彩度比不变，是「同一块颜色暗一档」，
不是「往灰里推」。

输出两张：
    dark-darker-preview.png   档位对照（NOW / A 轻 / B 中 / C 重 / D 近黑）
    dark-before-after.png     上一版（10-23）与当前（10-25 B 档）并排

用法：python review/dark-darker-preview.py
"""

import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_SHEET = os.path.join(HERE, "dark-darker-preview.png")
OUT_BA = os.path.join(HERE, "dark-before-after.png")

W, H = 300, 600
GAP = 14
LABEL_H = 58

# ---- 当前生产值（与 ui/theme/Color.kt / Theme.kt 逐字对应，2026-10-25 B 档）---------
NOW_STOPS = [0x1E1609, 0x130E05, 0x0B0804]
NOW_GLOWS = [0x8A6C2A, 0x7A5F22, 0x6B521E]
NOW_GLOW_A = [0.11, 0.09, 0.08]
NOW_SURFACE = 0x1B150D
NOW_VARIANT = 0x241D13
NOW_ACCENT = 0x402F10

# ---- 上一版（2026-10-23，A 档）---------------------------------------------------
OLD_STOPS = [0x231A0C, 0x171107, 0x0E0A05]
OLD_GLOW_A = [0.18, 0.15, 0.13]
OLD_SURFACE = 0x201A11
OLD_VARIANT = 0x2A2217
OLD_ACCENT = 0x4A3714

GLOW_POS = [(0.16, 0.10, 0.80), (0.92, 0.28, 0.64), (0.42, 0.86, 0.74)]
STOP_LOCS = [0.00, 0.50, 1.00]
TEXT = 0xF8F1E2       # Moonlight
TEXT_DIM = 0xB5A88E   # NightMuted


def _lin(c):
    v = c / 255.0
    return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4


def _srgb(v):
    v = 0.0 if v < 0 else (1.0 if v > 1 else v)
    return round(255 * (12.92 * v if v <= 0.0031308 else 1.055 * v ** (1 / 2.4) - 0.055))


def lum(h):
    return 0.2126 * _lin((h >> 16) & 255) + 0.7152 * _lin((h >> 8) & 255) + 0.0722 * _lin(h & 255)


def lstar(y):
    return 116 * y ** (1 / 3) - 16 if y > 0.008856 else 903.3 * y


def scale(h, k):
    r, g, b = (h >> 16) & 255, (h >> 8) & 255, h & 255
    return (_srgb(_lin(r) * k) << 16) | (_srgb(_lin(g) * k) << 8) | _srgb(_lin(b) * k)


def rgb(h):
    return ((h >> 16) & 255, (h >> 8) & 255, h & 255)


def mix(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def hexs(h):
    return f"#{h:06X}"


def contrast(bg, fg):
    a, b = lum(bg) + 0.05, lum(fg) + 0.05
    return max(a, b) / min(a, b)


def backdrop(stops, glow_a, k=1.0, glow_k=1.0):
    """逐像素：竖直播渐变 → 三枚径向光晕按 over 语义叠加。"""
    img = Image.new("RGB", (W, H))
    px = img.load()
    st = list(zip(STOP_LOCS, [rgb(scale(c, k)) for c in stops]))
    gl = [(rgb(g), a * glow_k, cx * W, cy * H, r * W)
          for g, a, (cx, cy, r) in zip(NOW_GLOWS, glow_a, GLOW_POS)]

    for y in range(H):
        t = y / (H - 1)
        base = st[-1][1]
        for i in range(len(st) - 1):
            l0, c0 = st[i]
            l1, c1 = st[i + 1]
            if l0 <= t <= l1:
                base = mix(c0, c1, 0.0 if l1 == l0 else (t - l0) / (l1 - l0))
                break
        for x in range(W):
            out = base
            for col, a, gx, gy, gr in gl:
                d = ((x - gx) ** 2 + (y - gy) ** 2) ** 0.5
                if d < gr:
                    out = mix(out, col, a * (1 - d / gr))
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


def draw_ui(base, surf, variant, accent):
    """把一个「深色首页」的层级画上去。尺寸按 300 宽等比放大（真机 390dp 的 0.77 倍）。"""
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)

    # 顶部玻璃搜索框：无 tint，只在边界留一道亮线（生产是 haze，这里画光学近似）
    d.rounded_rectangle((11, 24, W - 11, 68), radius=17,
                        outline=(255, 255, 255, 26), width=1)
    d.line((30, 46, 26, 42), fill=rgb(TEXT_DIM) + (200,), width=2)

    # 列表：4 行「封面块 + 两行字 + 三点」，第 2 行是选中行
    y = 92
    for i in range(4):
        if i == 1:
            d.rounded_rectangle((9, y - 8, W - 9, y + 54), radius=14, fill=rgb(surf) + (255,))
        d.rounded_rectangle((20, y, 62, y + 42), radius=8, fill=rgb(variant) + (255,))
        d.rounded_rectangle((54, y + 26, 70, y + 42), radius=6, fill=rgb(accent) + (255,))
        d.rounded_rectangle((76, y + 6, 76 + 118 - (i * 12), y + 15), radius=4,
                            fill=rgb(TEXT) + (235,))
        d.rounded_rectangle((76, y + 24, 76 + 84, y + 31), radius=3, fill=rgb(TEXT_DIM) + (150,))
        for j in range(3):
            d.ellipse((W - 26, y + 14 + j * 6, W - 23, y + 17 + j * 6),
                      fill=rgb(TEXT_DIM) + (190,))
        y += 66

    # 迷你播放器
    top = H - 132
    d.rounded_rectangle((11, top, W - 11, top + 56), radius=16, fill=rgb(surf) + (255,),
                        outline=(255, 255, 255, 22), width=1)
    d.rounded_rectangle((22, top + 10, 58, top + 46), radius=7, fill=rgb(variant) + (255,))
    d.rounded_rectangle((68, top + 18, 150, top + 27), radius=4, fill=rgb(TEXT) + (235,))
    d.rounded_rectangle((68, top + 34, 120, top + 41), radius=3, fill=rgb(TEXT_DIM) + (150,))
    d.polygon([(W - 96, top + 19), (W - 96, top + 39), (W - 82, top + 29)], fill=rgb(TEXT) + (245,))
    d.polygon([(W - 62, top + 19), (W - 62, top + 39), (W - 48, top + 29)], fill=rgb(TEXT) + (245,))
    d.polygon([(W - 44, top + 19), (W - 44, top + 39), (W - 30, top + 29)], fill=rgb(TEXT) + (245,))

    # 底栏 5 个 tab，首个是选中态
    d.rounded_rectangle((11, H - 64, W - 11, H - 12), radius=18, fill=rgb(surf) + (255,))
    for j in range(5):
        cx = 11 + (W - 22) * (j + 0.5) / 5
        col = rgb(TEXT) if j == 0 else rgb(TEXT_DIM)
        alpha = 240 if j == 0 else 130
        d.ellipse((cx - 8, H - 46, cx + 8, H - 30), fill=col + (alpha,))

    return Image.alpha_composite(base.convert("RGBA"), layer).convert("RGB")


def compose(items, path):
    cols = len(items)
    sheet = Image.new("RGB", (cols * W + (cols - 1) * GAP, H + LABEL_H), (24, 24, 26))
    draw = ImageDraw.Draw(sheet)
    f_title, f_small = font(15), font(12)

    for i, (name, shot, top, bot, surf, extra) in enumerate(items):
        x0 = i * (W + GAP)
        sheet.paste(shot, (x0, 0))
        draw.text((x0 + 8, H + 4), name, font=f_title, fill=(235, 235, 235))
        draw.text((x0 + 8, H + 24),
                  f"bg {hexs(top)}→{hexs(bot)}  L*{lstar(lum(top)):.1f}/{lstar(lum(bot)):.1f}",
                  font=f_small, fill=(150, 150, 150))
        draw.text((x0 + 8, H + 40),
                  f"surf {hexs(surf)} L*{lstar(lum(surf)):.1f}  {extra}",
                  font=f_small, fill=(150, 150, 150))

    sheet.save(path)
    print("written", path, sheet.size)


def main():
    plans = [
        ("NOW 当前", 1.00, 1.00, 1.00),
        ("A 轻度", 0.90, 0.75, 0.88),
        ("B 中度", 0.78, 0.60, 0.75),
        ("C 重度", 0.62, 0.45, 0.62),
        ("D 近黑", 0.40, 0.35, 0.55),
    ]

    items = []
    for name, kb, kg, ks in plans:
        stops = [scale(c, kb) for c in NOW_STOPS]
        surf = scale(NOW_SURFACE, ks)
        shot = draw_ui(
            backdrop(NOW_STOPS, NOW_GLOW_A, kb, kg),
            surf,
            scale(NOW_VARIANT, ks),
            scale(NOW_ACCENT, ks),
        )
        items.append((name, shot, stops[0], stops[-1], surf,
                      f"glow {sum(NOW_GLOW_A) * kg:.2f}  contr {contrast(surf, TEXT):.1f}:1"))
    compose(items, OUT_SHEET)

    # before / after：系数都是 1.0，只是两组数值本身不同
    before = draw_ui(backdrop(OLD_STOPS, OLD_GLOW_A), OLD_SURFACE, OLD_VARIANT, OLD_ACCENT)
    after = draw_ui(backdrop(NOW_STOPS, NOW_GLOW_A), NOW_SURFACE, NOW_VARIANT, NOW_ACCENT)
    compose([
        ("上一版 10-23", before, OLD_STOPS[0], OLD_STOPS[-1], OLD_SURFACE,
         f"glow {sum(OLD_GLOW_A):.2f}  contr {contrast(OLD_SURFACE, TEXT):.1f}:1"),
        ("当前 B 档 10-25", after, NOW_STOPS[0], NOW_STOPS[-1], NOW_SURFACE,
         f"glow {sum(NOW_GLOW_A):.2f}  contr {contrast(NOW_SURFACE, TEXT):.1f}:1"),
    ], OUT_BA)


if __name__ == "__main__":
    main()
