"""镜面过渡带"取消上半段"的方案对照（离线预览，与 Kotlin 侧同源公式）。

背景：现状 `BlurRamp` 起点在镜面线以上 128dp，也就是**封面自己的最后 128dp 被逐级糊掉**。
用户 2026-10-27 反馈"上面的一个条状模糊应该取消"。

本脚本把三种几何画在同一张封面上，只看镜面线附近：

  CUR  现状：stop0 = -128dp            → 封面底部 128dp 是糊的
  S1   带子归零 + 倒影快速化开         → stop = 0dp / 12@32dp / 24@80dp / 34@160dp
  S2   带子归零 + 倒影慢速化开         → stop = 0dp / 5@32dp / 12@80dp / 34@200dp

为什么 S1/S2 必须把**清晰层（半径 0）也画进倒影**：阶梯是单位分解（Σw ≡ 1），
镜面线以下若只有半径 >0 的几层被画出来，覆盖率会掉到 `1 − Π(1−w)` 那一档
（镜面下方 10dp 处只有 ~0.3），底下色场直接透出来 —— 就是第 44 批那条背景色带。

用法：python review/seam-band-remove-preview.py [封面图路径]
输出：review/_band-options.png
"""

import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
SHOT = os.path.join(HERE, "..", "screenshots", "verify-04-player.png")
OUT = os.path.join(HERE, "_band-options.png")

# 真机参考：873 x 1920 截图，390dp 宽 → 2.238 px/dp
DP = 873 / 390.0
HERO = int(429 * DP)          # 封面框 = 屏高 50%
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
    # verify-04-player.png：状态栏 76px 以下是整张封面，1706 796 以下是信息区。
    art = shot.crop((0, int(shot.size[1] * 0.0275), shot.size[0], int(shot.size[1] * 0.664)))
    art = art.resize((873, int(art.size[1] * 873 / art.size[0])), Image.LANCZOS)
    return art.crop((0, 0, 873, HERO))


def render(art, stops, label):
    band = -stops[0][1]
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
    # 红线 = 镜面线；黄线 = 过渡带起点（只有 CUR 会与红线分开）
    draw.line([(0, HERO), (873, HERO)], fill=(255, 60, 60), width=2)
    if band > 0:
        draw.line([(0, HERO - band * DP), (873, HERO - band * DP)], fill=(255, 190, 0), width=2)
    draw.rectangle([0, 0, 873, 34], fill=(0, 0, 0))
    draw.text((10, 10), label, fill=(255, 255, 255))
    return im


VARIANTS = [
    ([(0, -128), (5, -44), (12, 0), (34, 240)], "CUR  band -128dp  (cover bottom 128dp blurred)"),
    ([(0, 0), (12, 32), (24, 80), (34, 160)], "S1  band 0  blur 12dp@32dp (fast)"),
    ([(0, 0), (5, 32), (12, 80), (34, 200)], "S2  band 0  blur 5dp@32dp (slow)"),
]


def main():
    art = load_art(SHOT)
    tiles = [render(art, stops, label) for stops, label in VARIANTS]
    # 只裁镜面线附近：上 380px 封面 + 下 300px 倒影
    crop = (0, HERO - 380, 873, HERO + 300)
    tiles = [t.crop(crop) for t in tiles]
    w, h = tiles[0].size
    canvas = Image.new("RGB", (w * 3 + 20, h), (18, 18, 18))
    for i, tile in enumerate(tiles):
        canvas.paste(tile, (i * (w + 10), 0))
    canvas = canvas.resize((canvas.size[0] * 3 // 4, canvas.size[1] * 3 // 4), Image.LANCZOS)
    canvas.save(OUT)
    print(OUT, canvas.size)


if __name__ == "__main__":
    sys.exit(main())
