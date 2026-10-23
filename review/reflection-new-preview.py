"""按新的逐行映射渲染"将要上线"的过渡带，和真机同屏对照。

映射与生产代码 `coverRowAt()` 逐字对应：
    节点第 y 行 -> 封面框第  (y <= band) ? hero - band + y : hero - (y - band)  行
模糊用阶梯（12/22/34dp），衰减 FadeHold=24dp → FadeEnd=104dp，压暗 0.26。
"""
from PIL import Image, ImageFilter, ImageDraw

SHOT = r"C:/Users/Administrator/.workbuddy/clipboard-images/clipboard-2026-10-23T05-31-21-209Z-2a16b092.jpg"
ART = "review/sim-cover.png"        # 873x944，封面同款取景
W = 873
DP = 2.2385
HERO = 960.0
BAND = 128 * DP                     # 286.5
MIRROR = HERO                       # min(reflectionHeight, hero)
NODE = BAND + MIRROR
HOLD = 24 * DP
END = 104 * DP
SCRIM_MAX = 0.26
SCRIM_AT = 0.45 * 150 * DP          # 压暗到满值的位置（沿用生产口径的一半）

shot = Image.open(SHOT).convert("RGB")
art = Image.open(ART).convert("RGB")
# 封面框：方图铺满，纵向 5% 拉伸，横向居中裁掉
drawn_w = HERO / 1.05
hero_img = art.resize((int(round(drawn_w)), int(HERO)), Image.LANCZOS)
x0 = (hero_img.width - W) // 2
hero_img = hero_img.crop((x0, 0, x0 + W, int(HERO)))
cp = hero_img.load()

bg = shot.crop((0, 1250, 8, 1300)).resize((1, 1), Image.BOX).getpixel((0, 0))


def cover_row(y):
    if y <= BAND:
        return HERO - BAND + y
    return HERO - (y - BAND)


def alpha_at(y):
    if y <= HOLD:
        return 1.0
    t = min(1.0, max(0.0, (y - HOLD) / (END - HOLD)))
    return 1.0 - t * t * (3 - 2 * t)


def smoothstep(t):
    t = min(1.0, max(0.0, t))
    return t * t * (3 - 2 * t)


def layer_weight(i, y):
    """与 CoverReflection.layerWeight 同构：阶梯的单位分解。"""
    stops = [(-128 * DP, 0), (-86 * DP, 1), (-44 * DP, 2), (0.0, 3), (96 * DP, 4), (240 * DP, 5)]
    # 转成"距缝"坐标
    def rise(k):
        lo, hi = stops[k - 1][0], stops[k][0]
        return smoothstep((y - hi) / (hi - lo)) if hi > lo else (1.0 if y >= hi else 0.0)
    down = 1.0 if i == 0 else rise(i)
    up = 0.0 if i == len(stops) - 1 else rise(i + 1)
    return max(0.0, down - up)


RADII = [0, 2, 5, 12, 22, 34]

bands = [Image.new("RGB", (W, int(NODE))) for _ in RADII]
for i, r in enumerate(RADII):
    if i == 0:
        continue
    px = bands[i].load()
    for y in range(int(NODE)):
        cr = cover_row(y)
        crop = hero_img.crop((0, int(max(0, min(HERO - 2, cr))), W, int(max(2, min(HERO, cr + 1)))))
        bands[i].paste(crop, (0, y))
    bands[i] = bands[i].filter(ImageFilter.GaussianBlur(max(0.4, r * DP)))

out = Image.new("RGB", (W, int(NODE)))
op = out.load()
srcs = [bands[i].load() for i in range(len(RADII))]

for y in range(int(NODE)):
    yr = y - BAND                     # 生产代码里的 y 永远是"距缝"坐标
    fade = alpha_at(yr)
    scrim = SCRIM_MAX * min(1.0, max(0.0, yr) / max(1.0, SCRIM_AT))
    for x in range(W):
        acc = [0.0, 0.0, 0.0]
        wsum = 0.0
        for i in range(len(RADII)):
            w = layer_weight(i, yr)
            if w <= 0:
                continue
            c = srcs[i][x, y]
            acc[0] += c[0] * w
            acc[1] += c[1] * w
            acc[2] += c[2] * w
            wsum += w
        if wsum <= 0:
            out.putpixel((x, y), bg)
            continue
        c = [v / wsum for v in acc]
        k = fade
        s = 1.0 - scrim / 0.26 * 0.26   # 压暗：背景也一起压
        r0 = bg[0] * (1 - scrim) + c[0] * k * (1 - scrim)
        g0 = bg[1] * (1 - scrim) + c[1] * k * (1 - scrim)
        b0 = bg[2] * (1 - scrim) + c[2] * k * (1 - scrim)
        out.putpixel((x, y), (int(min(255, r0)), int(min(255, g0)), int(min(255, b0))))

out.save("review/sim-new-full.png")
# 拼接：真机 vs 新方案（600..1400）
top, bot = 560, 1400
a = shot.crop((0, top, W, bot))
b = out.crop((0, top - int(BAND), W, bot - int(BAND)))
canvas = Image.new("RGB", (W, (bot - top) * 2 + 8), (0, 255, 0))
canvas.paste(a, (0, 0))
canvas.paste(b, (0, bot - top + 8))
d = ImageDraw.Draw(canvas)
d.rectangle((0, 0, W, 16), fill=(0, 0, 0)); d.text((6, 3), "NOW (device)", fill=(255, 80, 80))
d.rectangle((0, bot - top + 8, W, bot - top + 24), fill=(0, 0, 0))
d.text((6, bot - top + 11), "NEW (exact mirror, 104dp)", fill=(0, 220, 120))
canvas.save("review/cmp-new-vs-device.png")
print("ok", canvas.size)
