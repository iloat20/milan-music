"""6 级阶梯 vs 4 级阶梯（3 个绘制层）的离线对照。

为什么另写一个而不是改 `seam-ramp-preview.py`：那个脚本是第 45~53 批的模型 ——
`fade` 从镜面线立刻开始衰减、终点取整幅倒影高度。生产代码后来加了 `FadeHold = 24dp`
（镜下先给一段实的）并把可见终点收到 `FadeEnd = 128dp`。参数不同，得出的结论不能
互相引用，所以这里按 `CoverReflection.kt` **当前**的四个函数逐行重写：

    w_i(y)     = rise_i(y) - rise_{i+1}(y)        # 单位分解
    rise_i(y)  = smoothstep((y - at_{i-1}) / (at_i - at_{i-1}))
    fade(y)    = 1 - smoothstep((y - 24) / (min(REFL, 128) - 24))
    alpha_i(y) = w_i(y) * fade(y)                  # 包络在模糊外面

输出：接缝两侧各 540px 的并排图 + 分区平均/最大逐像素差，以及接缝的亮度台阶与硬度。
"""

import os
from PIL import Image, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
SHOT = r"C:\Users\Administrator\.workbuddy\clipboard-images\clipboard-2026-10-23T04-34-02-822Z-98baa410.jpg"
OUT = os.path.join(HERE, "_ramp3_compare.png")

DP = 873 / 390.0          # 873px 宽对应 390dp
SEAM_REAL = 960           # 实测镜面线
VIEW = 540                # 对照面板的半高

SCRIM_MAX = 0.26
SCRIM_FULL_AT = 0.45
FADE_HOLD_DP = 24
FADE_END_DP = 128

RAMP_NOW = [(0, -128), (2, -86), (5, -44), (12, 0), (22, 96), (34, 240)]
RAMP_3L = [(0, -128), (5, -44), (12, 0), (34, 240)]      # 绘制层 = 3


def smoothstep(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


shot = Image.open(SHOT).convert("RGB")
W, PAGE_REAL = shot.size
HERO = SEAM_REAL
REFL = HERO                                   # 倒影区高度 = 封面框高
backdrop = shot.getpixel((W // 2, PAGE_REAL - 4))

src = shot.crop((0, 0, W, HERO))
mirror = src.transpose(Image.FLIP_TOP_BOTTOM)


def build(ramp):
    band = int(round(-ramp[0][1] * DP))
    node_h = band + REFL
    stops = [(r * DP, at * DP) for r, at in ramp]
    hold = FADE_HOLD_DP * DP
    end = min(REFL, FADE_END_DP * DP)

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
        if y <= hold:
            return 1.0
        if end <= hold:
            return 0.0
        return 1.0 - smoothstep((y - hold) / (end - hold))

    node = Image.new("RGB", (W, node_h))
    node.paste(src.crop((0, HERO - band, W, HERO)), (0, 0))
    node.paste(mirror.crop((0, 0, W, REFL)), (0, band))

    # 画布必须装得下「封面 + 整幅倒影」：只开到封面高的话，接缝以下的对照结果全是
    # 越界填充的黑边，两块面板当然逐像素相同 —— 第一版就是这么骗过自己的。
    page_h = HERO + REFL
    page = Image.new("RGB", (W, page_h), backdrop)
    page.paste(src, (0, 0))

    drawn = 0
    for index in range(len(stops) - 1, 0, -1):
        radius_px, _ = stops[index]
        layer = node.filter(ImageFilter.GaussianBlur(radius_px)) if radius_px > 0 else node
        mask = Image.new("L", (W, node_h), 0)
        for y in range(node_h):
            a = weight(index, y - band) * fade(y - band)
            mask.paste(int(a * 255), (0, y, W, y + 1))
        page.paste(layer, (0, HERO - band), mask)
        drawn += 1

    scrim = Image.new("L", (W, page_h), 0)
    for y in range(band, page_h):
        t = (y - band) / max(1, REFL)
        a = SCRIM_MAX * smoothstep(t / SCRIM_FULL_AT) if t < SCRIM_FULL_AT else SCRIM_MAX
        scrim.paste(int(a * 255), (0, y, W, y + 1))
    page.paste(Image.new("RGB", (W, page_h), (0, 0, 0)), (0, 0), scrim)
    return page.crop((0, HERO - VIEW, W, HERO + VIEW)), drawn


now, n_now = build(RAMP_NOW)
three, n_3l = build(RAMP_3L)

# ---- 分区逐像素差 ----
now_px = now.load()
three_px = three.load()


def region(lo, hi, name):
    total = peak = 0
    count = 0
    for y in range(VIEW + lo, VIEW + hi):
        for x in range(0, W, 2):
            a = now_px[x, y]
            b = three_px[x, y]
            d = max(abs(a[0] - b[0]), abs(a[1] - b[1]), abs(a[2] - b[2]))
            total += d
            peak = max(peak, d)
            count += 1
    print(f"  {name:22s} 平均 {total / count:5.2f}/255   最大 {peak:3d}/255")


print(f"绘制层数：现状 {n_now}  ->  4 级方案 {n_3l}")
print("接缝两侧逐像素差（0 = 完全相同）：")
region(-128, 0, "封面过渡带 (上)")
region(-8, 8, "接缝 ±8dp")
region(0, 64, "镜下 0..64dp")
region(64, 128, "镜下 64..128dp")
region(128, 400, "镜下 128dp 以下")


# ---- 接缝硬指标 ----
def probe(img, tag):
    px = img.convert("L").load()

    def rms(y0, y1):
        s = n = 0
        for y in range(y0, y1):
            for x in range(1, W, 2):
                dx = px[x, y] - px[x - 1, y]
                dy = px[x, y] - px[x, y - 1]
                s += dx * dx + dy * dy
                n += 2
        return (s / max(1, n)) ** 0.5

    def mean(y0, y1):
        t = n = 0
        for y in range(y0, y1):
            for x in range(0, W, 2):
                t += px[x, y]
                n += 1
        return t / max(1, n) / 255

    print(f"  [{tag:8s}] 接缝亮度台阶 Δ {mean(VIEW, VIEW + 8) - mean(VIEW - 8, VIEW):+.4f}"
          f"   硬度 {rms(VIEW - 24, VIEW - 4):5.2f} -> {rms(VIEW + 4, VIEW + 24):5.2f}"
          f"   带内 RMS -200/-100/0/+100/+200: "
          + "/".join(f"{rms(VIEW + d, VIEW + d + 2):4.2f}" for d in (-200, -100, 0, 100, 200)))


print("接缝指标（理想：Δ≈0，硬度两边接近）：")
probe(now, "现状 6 级")
probe(three, "4 级")

gap = 16
canvas = Image.new("RGB", (W * 2 + gap, VIEW * 2), (18, 18, 18))
canvas.paste(now, (0, 0))
canvas.paste(three, (W + gap, 0))
canvas.save(OUT)
print("已写出", OUT)
