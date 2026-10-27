"""倒影顶端"太清楚"的方案对照（离线预览，与 Kotlin 侧同源公式）。

背景：第 82 批把过渡带整体移到镜面线以下后，阶梯是
    0dp@0 / 12dp@32 / 24dp@80 / 34dp@160
清晰层（半径 0，即真实镜像）从镜面线的权重 1 一路衰减到 32dp 才让位完，
所以镜下前 ~20dp 读起来仍是"封面底部被复制了一份"。用户 2026-10-27 反馈"倒影顶端太清楚"。

能动的旋钮只有一个：**清晰层让位的距离**。首级半径必须是 0（镜面线两侧清晰度相等是
接缝不显形的唯一凭据，改成 6dp 就立刻出现清晰度断崖），层数不变（每多一层多一次
全屏离屏 + 一个 Modifier.blur）。

档位：
  CUR  0@0 / 12@32 / 24@80 / 34@160   清晰层 32dp（现状）
  T1   0@0 / 12@16 / 24@64 / 34@160   清晰层 16dp
  T2   0@0 / 12@10 / 24@56 / 34@160   清晰层 10dp
  T3   0@0 / 16@14 / 28@72 / 36@160   清晰层 14dp，二级半径加大

用法：python review/seam-top-clear-preview.py
输出：review/_top-clear-options.png
"""

import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
SHOT = os.path.join(HERE, "..", "screenshots", "verify-04-player.png")
OUT = os.path.join(HERE, "_top-clear-options.png")

DP = 873 / 390.0          # 2.238 px/dp（真机参考 873x1920 / 390dp 宽）
HERO = int(429 * DP)      # 封面框高 = 屏高 50% → 960px
FADE_HOLD = 24.0
FADE_END = 128.0
SCRIM_MAX = 0.26
SCRIM_FULL_AT = 0.45
BACKDROP = np.array([58, 52, 44], np.float32)


def smoothstep(t):
    t = np.clip(t, 0.0, 1.0)
    return t * t * (3 - 2 * t)


def ramp_weights(stops, y):
    """单位分解：返回 (层数, N)，任意 y 上各层权重之和为 1。"""
    n = len(stops)
    at = [s[1] for s in stops]

    def rise(i):
        lo, hi = at[i - 1], at[i]
        if hi <= lo:
            return (y >= hi).astype(float)
        return smoothstep((y - lo) / (hi - lo))

    rises = [None] + [rise(i) for i in range(1, n)]
    weights = []
    for i in range(n):
        down = np.ones_like(y) if i == 0 else rises[i]
        up = np.zeros_like(y) if i == n - 1 else rises[i + 1]
        weights.append(np.clip(down - up, 0, None))
    return np.array(weights)


def fade_curve(y, bottom):
    out = np.ones_like(y)
    span = min(bottom, FADE_END) - FADE_HOLD
    mask = y > FADE_HOLD
    out[mask] = 0.0 if span <= 0 else 1.0 - smoothstep((y[mask] - FADE_HOLD) / span)
    return out


def load_art(path):
    shot = Image.open(path).convert("RGB")
    # 这张 1256x2760 截图：封面框 y≈120..1445（实测清晰度剖面在该段无断崖）。
    art = shot.crop((0, 120, shot.size[0], 1445))
    art = art.resize((873, int(art.size[1] * 873 / art.size[0])), Image.LANCZOS)
    return art.crop((0, 0, 873, HERO))


def render(art, stops, label):
    height = HERO + int(FADE_END * DP) + 40
    layers = [
        np.asarray(art.filter(ImageFilter.GaussianBlur(r * DP)) if r > 0 else art, np.float32)
        for r, _ in stops
    ]
    u = np.arange(height) - HERO
    u_dp = u / DP
    rows = np.where(u <= 0, HERO + u, HERO - u).clip(0, HERO - 1)
    weights = ramp_weights(stops, u_dp)

    acc = np.zeros((height, 873, 3), np.float32)
    for i, layer in enumerate(layers):
        acc += weights[i][:, None, None] * layer[rows]
    acc /= np.maximum(weights.sum(0)[:, None, None], 1e-6)

    fade = fade_curve(u_dp, FADE_END)[:, None, None]
    scrim = np.clip(u_dp / (SCRIM_FULL_AT * FADE_END), 0, 1)[:, None, None] * SCRIM_MAX
    below = (u > 0)[:, None, None]
    out = np.where(below, acc * (1 - scrim) * fade + BACKDROP * (1 - fade), acc)

    im = Image.fromarray(np.clip(out, 0, 255).astype(np.uint8))
    draw = ImageDraw.Draw(im)
    draw.line([(0, HERO), (873, HERO)], fill=(255, 60, 60), width=2)
    draw.rectangle([0, 0, 873, 30], fill=(0, 0, 0))
    draw.text((8, 9), label, fill=(255, 255, 255))
    return im


VARIANTS = [
    ([(0, 0), (12, 32), (24, 80), (34, 160)], "CUR  sharp 0-32dp"),
    ([(0, 0), (12, 16), (24, 64), (34, 160)], "T1   sharp 0-16dp"),
    ([(0, 0), (12, 10), (24, 56), (34, 160)], "T2   sharp 0-10dp"),
    ([(0, 0), (16, 14), (28, 72), (36, 160)], "T3   sharp 0-14dp + heavier 2nd"),
]


def report():
    """镜下各位置的清晰层权重（= 有多少比例是真实镜像）。"""
    ys = np.array([0, 4, 8, 12, 16, 24, 32], np.float32)
    print("y(dp)   " + "  ".join(f"{v[1].split()[0]:>4}" for v in VARIANTS))
    for y in ys:
        cells = []
        for stops, _ in VARIANTS:
            w = ramp_weights(stops, np.array([y], np.float32))[0][0]
            cells.append(f"{w:4.2f}")
        print(f"{y:5.0f}   " + "  ".join(cells))


def main():
    art = load_art(SHOT)
    report()
    tiles = [render(art, stops, label) for stops, label in VARIANTS]
    crop = (0, HERO - 150, 873, HERO + 210)      # 镜下 -67dp .. +94dp
    tiles = [t.crop(crop) for t in tiles]
    w, h = tiles[0].size
    gap = 6
    cols = 2
    rows = (len(tiles) + cols - 1) // cols
    canvas = Image.new(
        "RGB",
        (w * cols + gap * (cols - 1), h * rows + gap * (rows - 1)),
        (18, 18, 18),
    )
    for i, tile in enumerate(tiles):
        canvas.paste(tile, ((i % cols) * (w + gap), (i // cols) * (h + gap)))
    canvas.save(OUT)

    # 只放大镜下 0..70dp 的一段（细微差别必须放大才看得见）
    zoom_src = [render(art, stops, label).crop((0, HERO - 45, 430, HERO + 160)) for stops, label in VARIANTS]
    zoom = [z.resize((z.size[0] * 3, z.size[1] * 3), Image.LANCZOS) for z in zoom_src]
    zw, zh = zoom[0].size
    zcanvas = Image.new("RGB", (zw * 2 + gap, zh * 2 + gap), (18, 18, 18))
    for i, tile in enumerate(zoom):
        zcanvas.paste(tile, ((i % 2) * (zw + gap), (i // 2) * (zh + gap)))
    zout = OUT.replace(".png", "-zoom.png")
    zcanvas.save(zout)
    print(zout, zcanvas.size)
    print(OUT, canvas.size)


if __name__ == "__main__":
    sys.exit(main())
