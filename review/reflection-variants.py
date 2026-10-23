"""在真机截图上叠加出三种"封面下方"的处理，用于选型。

只用真机像素做封面侧（900..980），下方 980..1400 按各方案合成，底面取真机
背景色（截图中 1240..1300 的纯背景行），这样四种面板的封面侧完全一致，
差别只在倒影本身。
"""
from PIL import Image, ImageFilter, ImageDraw

SHOT = r"C:/Users/Administrator/.workbuddy/clipboard-images/clipboard-2026-10-23T05-31-21-209Z-2a16b092.jpg"
ART = "review/sim-cover.png"
S = 980            # 镜面线（实测）
TOP = 900          # 面板上界
BOT = 1400
DP = 2.2385

shot = Image.open(SHOT).convert("RGB")
art = Image.open(ART).convert("RGB")     # 873x944，封面同款取景
W = shot.width

bg = shot.crop((0, 1250, 6, 1290)).resize((1, 1), Image.BOX).getpixel((0, 0))
print("背景色", bg)

def fade_alpha(h, hold, end):
    out = []
    for y in range(h):
        if y <= hold: out.append(1.0)
        else:
            t = (y - hold) / max(1e-6, (end - hold))
            t = min(1.0, max(0.0, t))
            out.append(1.0 - t * t * (3 - 2 * t))
    return out

def blurred_ramp(img, stops):
    """按行变化的高斯模糊：分段糊 + 线性交叉。stops = [(y, sigma_px), ...]"""
    out = Image.new("RGB", img.size)
    bands = []
    for i in range(len(stops) - 1):
        y0, s0 = stops[i]; y1, s1 = stops[i + 1]
        bands.append((y0, y1, (s0 + s1) / 2))
    for y0, y1, sigma in bands:
        h = y1 - y0
        if h <= 0: continue
        seg = img.crop((0, max(0, y0 - 60), W, min(img.height, y1 + 60)))
        seg = seg.filter(ImageFilter.GaussianBlur(max(0.5, sigma)))
        out.paste(seg.crop((0, min(60, y0), W, min(60, y0) + h)), (0, y0))
    return out

def panel_current():
    """A：现状 —— 1:1 镜像，12->34dp，56..150dp 淡出。"""
    h = BOT - S
    mir = art.transpose(Image.FLIP_TOP_BOTTOM).resize((W, 944), Image.LANCZOS)
    mir = mir.crop((0, 0, W, h))
    mir = blurred_ramp(mir, [(0, 12 * DP), (h // 3, 22 * DP), (h * 2 // 3, 34 * DP), (h, 34 * DP)])
    al = fade_alpha(h, 56 * DP, 150 * DP)
    base = Image.new("RGB", (W, h), bg)
    px = mir.load(); out = base.load()
    for y in range(h):
        a = al[y]
        for x in range(W):
            c = px[x, y]
            out[x, y] = tuple(int(bg[i] + (c[i] - bg[i]) * a) for i in range(3))
    return base

def panel_spill():
    """B：不做镜像，只用封底那一行的列颜色向下铺开（光晕延续）。"""
    h = BOT - S
    row = art.crop((0, 941, W, 944)).resize((W, 1), Image.BOX)
    strip = row.resize((W, h), Image.NEAREST)
    strip = strip.filter(ImageFilter.GaussianBlur(26 * DP / 4))
    strip = strip.filter(ImageFilter.GaussianBlur(26 * DP / 4))
    al = fade_alpha(h, 10 * DP, 120 * DP)
    base = Image.new("RGB", (W, h), bg)
    px = strip.load(); out = base.load()
    for y in range(h):
        a = al[y]
        for x in range(W):
            c = px[x, y]
            out[x, y] = tuple(int(bg[i] + (c[i] - bg[i]) * a) for i in range(3))
    return base

def panel_short():
    """C：仍是镜像，但一上来就 24dp 起糊，90dp 内收干净。"""
    h = BOT - S
    mir = art.transpose(Image.FLIP_TOP_BOTTOM).resize((W, 944), Image.LANCZOS).crop((0, 0, W, h))
    mir = blurred_ramp(mir, [(0, 24 * DP), (h // 3, 34 * DP), (h, 44 * DP)])
    al = fade_alpha(h, 8 * DP, 90 * DP)
    base = Image.new("RGB", (W, h), bg)
    px = mir.load(); out = base.load()
    for y in range(h):
        a = al[y]
        for x in range(W):
            c = px[x, y]
            out[x, y] = tuple(int(bg[i] + (c[i] - bg[i]) * a) for i in range(3))
    return base

head = shot.crop((0, TOP, W, S))
panels = [("A now: mirror 150dp", panel_current()),
          ("B spill: no mirror", panel_spill()),
          ("C mirror 90dp hard", panel_short())]
ch = S - TOP
canvas = Image.new("RGB", (W, (BOT - TOP) * 4 + 16 * 3), (0, 255, 0))
for i, (_, p) in enumerate(panels):
    oy = i * (BOT - TOP) + 16 * i
    canvas.paste(head, (0, oy))
    canvas.paste(p, (0, oy + ch))
canvas.paste(shot.crop((0, TOP, W, BOT)), (0, 3 * (BOT - TOP) + 48))
d = ImageDraw.Draw(canvas)
for i, (name, _) in enumerate(panels):
    oy = i * (BOT - TOP) + 16 * i
    d.rectangle((0, oy, W, oy + 18), fill=(0, 0, 0))
    d.text((6, oy + 4), name, fill=(0, 255, 120))
d.rectangle((0, 3 * (BOT - TOP) + 48, W, 3 * (BOT - TOP) + 66), fill=(0, 0, 0))
d.text((6, 3 * (BOT - TOP) + 52), "D real device", fill=(255, 200, 0))
canvas.save("review/reflection-variants.png")
print("ok", canvas.size)
