"""拖动中途的合成预览：叠层到底跟不跟封面走（第 58 批）。

按生产代码的**逐字公式**合成 4 帧：
    封面第 s 页的横向位移 = pageTranslationPx(s, pitch, drag) = s*pitch + drag
    倒影叠层的横向位移   = pageTranslationPx(0, pitch, drag)  = drag
    隔壁页底边          = pageFadesBottom(slot, neighbourFades) → 只有 -1/+1 淡出

叠层的内容（阶梯 + 衰减 + 逐行映射）不在这里重算 —— 它由 `reflection-new-preview.py`
单独验证过，这一版只负责把那一块**贴到正确的位置**上，因为第 58 批改的正是位置。

判据（肉眼即可）：每一帧里"封面下半部分"那条带子的左右边界，必须和封面的左右边界
严丝合缝地重合。只要带子有一侧挪出封面边界，它就是一层"钉住的叠加层"。
"""
from PIL import Image, ImageDraw, ImageFont

ART = "review/sim-cover.png"        # 873x944，封面同款取景
SRC_W = 873
DP = 2.2385                          # 真机 px/dp
S = 0.55                             # 预览缩放：只为跑得快、看得清
W_DP = 390                           # 屏宽 390dp

W = int(SRC_W * S)
dp = DP * S
HERO = 960 * S
BAND = 128 * dp
PITCH = (W_DP + 12) * dp             # CoverLayout.pitch：页宽 + 页缝
FRAME_H = int(1200 * S)
FADE_OUT = 0.80                      # CoverLayout.HeroFadeOut
BG = (26, 19, 8)

art = Image.open(ART).convert("RGB")
drawn_w = int(round(HERO / 1.05))
hero_img = art.resize((drawn_w, int(HERO)), Image.LANCZOS)
x0 = (hero_img.width - W) // 2
hero_img = hero_img.crop((x0, 0, x0 + W, int(HERO)))
NEXT = hero_img.transpose(Image.FLIP_LEFT_RIGHT)

# --- 叠层：上半 = 封面最后 BAND，下半 = 逐行镜像（coverRowAt） -------------------
BAND_PX = int(round(BAND))
MIRROR = int(round(HERO))
band = Image.new("RGB", (W, BAND_PX + MIRROR))
cp = hero_img.load()
bp = band.load()
for y in range(BAND_PX + MIRROR):
    src = (HERO - BAND + y) if y <= BAND_PX else (HERO - (y - BAND_PX))
    src = int(max(0, min(HERO - 1, src)))
    for x in range(W):
        bp[x, y] = cp[x, src]


def smoothstep(t):
    t = min(1.0, max(0.0, t))
    return t * t * (3 - 2 * t)


def fade_mask() -> Image.Image:
    """叠层的纵向包络：FadeHold=24dp 满，到 FadeEnd=104dp 收干净（下方全透明）。"""
    hold = 24 * dp
    end = 104 * dp
    m = Image.new("L", (W, band.height))
    mp = m.load()
    for y in range(band.height):
        yr = y - BAND_PX
        a = 1.0 if yr <= hold else 1.0 - smoothstep((yr - hold) / max(1.0, end - hold))
        v = int(round(255 * max(0.0, a)))
        for x in range(W):
            mp[x, y] = v
    return m


def neighbour_mask() -> Image.Image:
    """隔壁页底边淡出：HeroFadeOut → 框底渐隐到 0（与 heroBottomFadeMask 同一条曲线）。"""
    m = Image.new("L", (W, int(HERO)))
    mp = m.load()
    y0 = int(FADE_OUT * HERO)
    for y in range(int(HERO)):
        v = 255 if y <= y0 else int(round(255 * (1.0 - (y - y0) / max(1.0, HERO - y0))))
        for x in range(W):
            mp[x, y] = v
    return m


BAND_MASK = fade_mask()
NEXT_MASK = neighbour_mask()


def place(canvas, img, x, y, mask=None):
    """把 img 贴到 (x,y)，越出画布的部分裁掉。"""
    left = max(0, x)
    top = max(0, y)
    right = min(canvas.width, x + img.width)
    bottom = min(canvas.height, y + img.height)
    if right <= left or bottom <= top:
        return
    box = (left - x, top - y, right - x, bottom - y)
    if mask is not None:
        canvas.paste(img.crop(box), (left, top), mask.crop(box))
    else:
        canvas.paste(img.crop(box), (left, top))


DRAGS = [0.0, -0.30, -0.70, -1.0]
frames = []
for frac in DRAGS:
    drag = frac * PITCH
    canvas = Image.new("RGB", (W, FRAME_H), BG)
    # 隔壁页（下一首）：没有叠层兜底，自己淡底边
    place(canvas, NEXT, int(round(PITCH + drag)), 0, NEXT_MASK)
    # 当前页：底边不淡
    place(canvas, hero_img, int(round(drag)), 0)
    # 叠层：与当前页**同一个位移**
    place(canvas, band, int(round(drag)), int(round(HERO - BAND)), BAND_MASK)
    frames.append((frac, canvas))

# --- 拼版 ------------------------------------------------------------------
GAP = 6
HEAD = 26
out = Image.new("RGB", (W * len(frames) + GAP * (len(frames) - 1), FRAME_H + HEAD), (0, 0, 0))
d = ImageDraw.Draw(out)
for i, (frac, f) in enumerate(frames):
    x = i * (W + GAP)
    out.paste(f, (x, HEAD))
    # 封面与叠层应有的左右边界（两帧都画，对照用）
    cx = int(round(frac * PITCH))
    d.line([(x + cx, HEAD), (x + cx, HEAD + FRAME_H)], fill=(255, 60, 60))
    d.line([(x + cx + W, HEAD), (x + cx + W, HEAD + FRAME_H)], fill=(255, 60, 60))
    d.text((x + 4, 6), f"drag {frac:+.0%} pitch", fill=(0, 220, 120))

out.save("review/swipe-frames.png")

# --- 修前 vs 修后：同一帧，只差叠层的横向位移 -------------------------------
frac = -0.5
drag = frac * PITCH
pairs = []
for pinned in (True, False):
    canvas = Image.new("RGB", (W, FRAME_H), BG)
    place(canvas, NEXT, int(round(PITCH + drag)), 0, NEXT_MASK)
    place(canvas, hero_img, int(round(drag)), 0)
    # pinned=True 就是第 57 批的真机行为：叠层留在屏幕原点，封面被拖走了。
    bx = 0 if pinned else drag
    place(canvas, band, int(round(bx)), int(round(HERO - BAND)), BAND_MASK)
    pairs.append((pinned, canvas))

out = Image.new("RGB", (W * 2 + GAP, FRAME_H + HEAD), (0, 0, 0))
d = ImageDraw.Draw(out)
for i, (pinned, f) in enumerate(pairs):
    x = i * (W + GAP)
    out.paste(f, (x, HEAD))
    label = "BEFORE: band pinned at screen origin" if pinned else "FIXED: band rides the cover"
    d.text((x + 4, 6), label, fill=(255, 80, 80) if pinned else (0, 220, 120))
out.save("review/swipe-before-after.png")
print("ok", out.size)
