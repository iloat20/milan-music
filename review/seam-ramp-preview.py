"""新过渡模型（模糊阶梯）的三方案离线对照。

输入：一张真机截图。用它的**清晰区域**当封面源，把镜面线放到清晰区的下缘，
按 `CoverReflection.kt` 里的模型重新合成，右侧再放两档阶梯强度做对照。

为什么镜面线要挪：截图里镜面线以下的 143px 已经被上一版糊过，拿不到"清晰封面
末段"，而末段正是判断有没有分界的关键。挪到清晰区下缘之后，三个面板用的是同一张
照片、同一段内容，唯一变量就是过渡参数本身。

几何全部换成了 px（DP = 873/390），公式与生产代码逐行对应：
    w_i(y) = rise_i(y) - rise_{i+1}(y)          # 单位分解
    fade(y) = 1 - smoothstep((y - 32dp) / (h - 32dp))
    alpha_i(y) = w_i(y) * fade(y)               # 包络在模糊外面
"""

import os
from PIL import Image, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
SHOT = r"C:\Users\Administrator\.workbuddy\clipboard-images\clipboard-2026-10-23T04-34-02-822Z-98baa410.jpg"
OUT = os.path.join(HERE, "_seam_ramp.png")

DP = 873 / 390.0
FADE_START_DP = 0
SCRIM_MAX = 0.26
SCRIM_FULL_AT = 0.45

# 三档阶梯：(半径 dp, 位置 dp 相对镜面线)
RAMP_SETS = {
    "提案 0→2→5→12": [(0, -128), (2, -86), (5, -44), (12, 0), (22, 96), (34, 240)],
    "偏强 4→8→14":   [(0, -128), (4, -86), (8, -44), (14, 0), (26, 96), (44, 240)],
}

shot = Image.open(SHOT).convert("RGB")
W, PAGE_REAL = shot.size
SEAM_REAL = 960                      # 实测镜面线
HERO = SEAM_REAL                     # 用清晰区（0..SEAM_REAL）当封面
PAGE = HERO * 2                      # 倒影高 = 框高，1:1 镜像
REFL = HERO

src = shot.crop((0, 0, W, HERO))
mirror = src.transpose(Image.FLIP_TOP_BOTTOM)
backdrop = shot.getpixel((W // 2, PAGE_REAL - 4))


def smoothstep(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def build(ramp):
    band = int(-ramp[0][1] * DP)
    node_h = band + REFL
    stops = [(r * DP, at * DP) for r, at in ramp]
    fade_start = FADE_START_DP * DP

    def rise(index, y):
        lo, hi = stops[index - 1][1], stops[index][1]
        if hi <= lo:
            return 1.0 if y >= hi else 0.0
        return smoothstep((y - lo) / (hi - lo))

    def weight(index, y):
        n = len(stops)
        if index < 0 or index >= n:
            return 0.0
        down = 1.0 if index == 0 else rise(index, y)
        up = 0.0 if index == n - 1 else rise(index + 1, y)
        return max(0.0, down - up)

    def fade(y):
        if y <= fade_start:
            return 1.0
        return 1.0 - smoothstep((y - fade_start) / (REFL - fade_start))

    node = Image.new("RGB", (W, node_h))
    node.paste(src.crop((0, HERO - band, W, HERO)), (0, 0))
    node.paste(mirror.crop((0, 0, W, REFL)), (0, band))

    page = Image.new("RGB", (W, PAGE), backdrop)
    page.paste(src, (0, 0))

    for index in range(len(stops) - 1, 0, -1):
        radius_px, _ = stops[index]
        layer = node.filter(ImageFilter.GaussianBlur(radius_px)) if radius_px > 0 else node
        mask = Image.new("L", (W, node_h), 0)
        for y in range(node_h):
            a = weight(index, y - band) * fade(y - band)
            mask.paste(int(a * 255), (0, y, W, y + 1))
        page.paste(layer, (0, HERO - band), mask)

    scrim = Image.new("L", (W, PAGE), 0)
    for y in range(band, PAGE):
        t = (y - band) / max(1, REFL)
        a = SCRIM_MAX * smoothstep(t / SCRIM_FULL_AT) if t < SCRIM_FULL_AT else SCRIM_MAX
        scrim.paste(int(a * 255), (0, y, W, y + 1))
    page.paste(Image.new("RGB", (W, PAGE), (0, 0, 0)), (0, 0), scrim)
    return page, stops


def probe(img, seam, tag):
    px = img.convert("L").load()
    w, h = img.size

    def rms(y0, y1):
        s = n = 0
        for y in range(y0, y1):
            for x in range(1, w, 2):
                dx = px[x, y] - px[x - 1, y]
                dy = px[x, y] - px[x, y - 1]
                s += dx * dx + dy * dy
                n += 2
        return (s / max(1, n)) ** 0.5

    def mean(y0, y1):
        t = n = 0
        for y in range(y0, y1):
            for x in range(0, w, 2):
                t += px[x, y]
                n += 1
        return t / max(1, n) / 255

    print(f"[{tag:10s}] 亮度台阶 Δ {mean(seam, seam + 8) - mean(seam - 8, seam):+.4f}"
          f"   硬度 {rms(seam - 24, seam - 4):5.2f} -> {rms(seam + 4, seam + 24):5.2f}"
          f"   带内 RMS -200/-100/0/+100/+200: "
          + "/".join(f"{rms(seam + d, seam + d + 2):4.2f}" for d in (-200, -100, 0, 100, 200)))


VIEW = 540
panels = [shot.crop((0, SEAM_REAL - VIEW, W, SEAM_REAL + VIEW))]
probe(panels[0], VIEW, "现状 b53")

for name, ramp in RAMP_SETS.items():
    page, stops = build(ramp)
    panels.append(page.crop((0, HERO - VIEW, W, HERO + VIEW)))
    probe(panels[-1], VIEW, name)

gap = 16
canvas = Image.new("RGB", ((W + gap) * len(panels) - gap, VIEW * 2), (18, 18, 18))
for i, panel in enumerate(panels):
    canvas.paste(panel, (i * (W + gap), 0))
canvas.save(OUT)
print(f"\n输出 {OUT}  （左→右：现状 / " + " / ".join(RAMP_SETS) + "）")
