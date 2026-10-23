"""第 59 批「切歌闪一下」的时序图：谁在哪一帧变。

这张图不做像素合成 —— 病在**时序**上，不在几何上。竖直方向一列 = 一帧，
横向三行 = 三处可见状态：
    页     轮播中央那一页（slot 0 的封面）
    带     倒影叠层（含压住封面末段 128dp 的过渡带）
    边     封面底边 / 隔壁页底边的淡出开关

BEFORE：带子和三个开关由 `reflection`（异步解码 + 512² 翻转，缓存还装不下单张）驱动，
        切页那一帧只有"页"变了 —— 屏幕下半块还是旧封面，1~3 帧后才整体换掉。
AFTER ：带子改由 `pitch` 同一行数据驱动（`pages.current` 的同步缓存位图），
        与"页"在同一批快照里换，永远同一帧。
"""
from PIL import Image, ImageDraw, ImageFont

W, H = 1240, 470
BG = (18, 14, 8)
FRAME_BG = (32, 26, 16)
INK = (235, 228, 214)
DIM = (150, 142, 128)
OK = (70, 200, 130)
BAD = (245, 85, 85)

F = "C:/Windows/Fonts/msyh.ttc"
title = ImageFont.truetype(F, 20)
label = ImageFont.truetype(F, 15)
cell = ImageFont.truetype(F, 15)
small = ImageFont.truetype(F, 13)

img = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(img)

COLS = 5
BW, BH, GAP = 200, 118, 20
X0 = 150
LANES = [
    ("BEFORE  1.0.59", 60, [
        # (页, 带, 边) 每个值: (文字, 是否一致)
        ("旧", "旧", "开"), ("新", "旧", "开"), ("新", "旧", "开"), ("新", "新", "开"), ("新", "新", "开"),
    ]),
    ("AFTER   1.0.60", 262, [
        ("旧", "旧", "开"), ("新", "新", "开"), ("新", "新", "开"), ("新", "新", "开"), ("新", "新", "开"),
    ]),
]

d.text((24, 18), "滑封面切歌：谁在哪一帧变（竖直一列 = 一帧）", font=title, fill=INK)

for name, y0, states in LANES:
    d.text((24, y0 + 46), name, font=label, fill=DIM)
    for i, (page, band, edge) in enumerate(states):
        x = X0 + i * (BW + GAP)
        d.rounded_rectangle([x, y0, x + BW, y0 + BH], 10, fill=FRAME_BG)
        cap = "F0 切页前" if i == 0 else f"F{i} 切页后"
        d.text((x + 10, y0 + 8), cap, font=small, fill=DIM)
        rows = [("页 ", page), ("带 ", band), ("边 ", edge)]
        for r, (k, v) in enumerate(rows):
            yy = y0 + 32 + r * 27
            bad = (v == "旧" and k.strip() == "带" and page == "新")
            d.text((x + 12, yy), k, font=cell, fill=DIM)
            d.text((x + 46, yy), v, font=cell, fill=BAD if bad else OK if v != "旧" else INK)
        if i > 0 and states[i][1] == "新" and states[i - 1][1] == "旧":
            d.line([(x, y0 - 6), (x + BW, y0 - 6)], fill=OK, width=3)
            d.text((x + 4, y0 - 26), "带子在这一帧才换 → 那一下闪", font=small, fill=BAD)

img.save("review/b59-flash-timeline.png")
print("ok", img.size)
