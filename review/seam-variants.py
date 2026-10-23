"""镜面过渡的四种方案同输入对比（纯 PIL）。

目的：在同一个封面、同一套几何下，把"封面 / 倒影接缝"的四种处理画出来，
并量化两个指标 —— 亮度台阶与清晰度台阶。这两个台阶只要不是 0，人眼就一定看得见
一条横线，和参数怎么调无关。

几何与生产代码同源：
  - heroHeight = min(高*0.50, 高-300dp)
  - 封面取景 = 铺满 + 纵向 5% 拉伸（CoverLayout.HeroContentScale）
  - 倒影 = 封面整幅的上下镜像，高度 = 封面框高（1:1）
  - 三层倒影：18dp/1.00/1.00/0.10，28dp/0.85/0.90/0.50，44dp/0.70/0.60/1.00

用法：python review/seam-variants.py
输出：review/_seam_variants.png
"""

import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageStat

HERE = os.path.dirname(os.path.abspath(__file__))
COVER = os.path.join(HERE, "..", "..", "cover_zoom.png")
OUT = os.path.join(HERE, "_seam_variants.png")

DP = 2.0                    # 1dp = 2px
PAGE_W, PAGE_H = 390, 844   # 逻辑尺寸（dp）
HERO_H = min(PAGE_H * 0.50, PAGE_H - 300)      # = 422
REFL_H = PAGE_H - HERO_H                        # = 422
SEAM = int(round(HERO_H * DP))                  # 844 px


def frame(cover, w_px, h_px):
    """按 CoverLayout.HeroContentScale：铺满 + 纵向 5% 拉伸，再横向居中裁切。"""
    sw, sh = cover.size
    stretched = h_px / sh / 1.05
    wr = w_px / sw
    if stretched > wr:
        tw, th = sw * stretched, sh * stretched * 1.05
    else:
        tw, th = sw * wr, sh * wr
    resized = cover.resize((max(1, int(round(tw))), max(1, int(round(th)))), Image.LANCZOS)
    left = (resized.width - w_px) // 2
    top = 0
    return resized.crop((left, top, left + w_px, top + h_px))


def dim(img, k):
    if k >= 0.999:
        return img
    return img.point(lambda v: int(v * k))


def alpha_of(rows, stops):
    """边沿线性插值的 alpha 曲线。stops = [(行号, alpha), ...]，按行号升序。"""
    out = [0.0] * rows
    if not stops:
        return out
    for i in range(rows):
        y = i
        if y <= stops[0][0]:
            out[i] = stops[0][1]
        elif y >= stops[-1][0]:
            out[i] = stops[-1][1]
        else:
            for (y0, a0), (y1, a1) in zip(stops, stops[1:]):
                if y0 <= y <= y1:
                    t = 0.0 if y1 == y0 else (y - y0) / (y1 - y0)
                    out[i] = a0 + (a1 - a0) * t
                    break
    return out


def paste(canvas, band, blur_px, dim_k, y0, stops):
    """把已经是"一条带"的 band 贴到画布第 y0 行，按 stops 的 alpha 曲线叠加。

    band 必须**已经裁好**（height == rows）：从整页里取带子的事交给调用方，
    这里只管模糊、压暗、上包络 —— 早先在这里从 y=0 裁，等于永远取页面顶部。
    """
    rows = band.height
    band = dim(band, dim_k)
    if blur_px > 0:
        blur = ImageFilter.GaussianBlur(blur_px)
        # 模糊要在足够大的图上做，否则边缘会被核吃掉 —— 这正是真机上
        # Rectangle 模糊掉 alpha 的同一件事。补齐用**边缘复制**，不能用黑边：
        # 黑边会被核吸进来，凭空造出一条纵向渐变，把清晰度指标顶上去（踩过）。
        pad = int(blur_px * 3) + 1
        big = Image.new("RGB", (band.width, rows + pad * 2))
        top_row = band.crop((0, 0, band.width, 1)).resize((band.width, pad))
        bottom_row = band.crop((0, rows - 1, band.width, rows)).resize((band.width, pad))
        big.paste(top_row, (0, 0))
        big.paste(band, (0, pad))
        big.paste(bottom_row, (0, pad + rows))
        band = big.filter(blur).crop((0, pad, band.width, pad + rows))
    a = alpha_of(rows, stops)
    # 逐行 alpha 要铺成"每个像素一个值"：mask 是 width × rows，不是 rows。
    # 直接 putdata(a) 只会填满第一行，其余保持 0 —— 曾让四个方案看起来一模一样。
    flat = []
    for v in a:
        flat.extend([int(max(0.0, min(1.0, v)) * 255)] * band.width)
    mask = Image.new("L", (band.width, rows))
    mask.putdata(flat)
    canvas.paste(band, (0, y0), mask)


def build(cover, page_w, backdrop, variant):
    """返回一整页（封面 + 倒影）的图像。"""
    src = frame(cover, page_w, int(HERO_H * DP))
    sharp = Image.new("RGB", (page_w, PAGE_H * 2), backdrop)
    sharp.paste(src, (0, 0))
    refl = src.transpose(Image.FLIP_TOP_BOTTOM)
    sharp.paste(refl, (0, SEAM))
    # 基图 = "封面一直清晰、倒影直接接上"的硬接版本，各方案在它上面叠过渡层。
    page = sharp.copy()

    refl_h = int(REFL_H * DP)
    refl_band = sharp.crop((0, SEAM, page_w, SEAM + refl_h))

    band = variant["band"]          # 封面侧过渡带（dp）
    hold = variant["hold"]          # 倒影侧满 alpha 保持（占倒影高度）
    tail = variant["tail"]          # 倒影侧衰减终点（占倒影高度）
    blur = variant["blur"]          # 镜面处模糊半径（dp）
    near = variant.get("near", blur)  # 封面侧是不是用更小的糊（0 = 不叠）

    # 远景层：最糊，先画
    paste(page, refl_band, 44 * DP, 0.70, SEAM,
          [(0, 0.60), (refl_h, 0)])
    # 中层
    paste(page, refl_band, 28 * DP, 0.85, SEAM,
          [(0, 0.90), (refl_h * 0.50, 0)])

    if near > 0:
        # 封面侧：把最后 band dp 的封面**渐进**换成糊版
        rows = int(band * DP)
        cover_band = sharp.crop((0, SEAM - rows, page_w, SEAM))
        paste(page, cover_band, near * DP, 1.0, SEAM - rows,
              [(0, 0.0), (rows, 1.0)])
    # 接触层：倒影侧
    rows = int(refl_h * tail)
    paste(page, refl_band.crop((0, 0, page_w, rows)), blur * DP, 1.0, SEAM,
          [(0, 1.0), (refl_h * hold, 1.0), (rows, 0.0)])
    return page


VARIANTS = [
    dict(key="V0", name="对照组：硬接",
         desc="封面一直清晰到镜面线，倒影 18dp 直接接上（第 45~48 批）",
         band=0, hold=0.0, tail=0.10, blur=18, near=0),
    dict(key="V1", name="现状 B52：跨缝 64dp 三角",
         desc="镜面线上方 64dp 由 0 升到满 alpha，下方 0.10 高度收完",
         band=64, hold=0.0, tail=0.10, blur=18, near=18),
    dict(key="V2", name="宽带 150dp 平滑",
         desc="过渡带拉到 150dp，倒影侧先保持 0.18 再衰减到 0.55",
         band=150, hold=0.18, tail=0.55, blur=18, near=18),
    dict(key="V3", name="近缝清晰 + 长尾",
         desc="镜面处只糊 8dp（看得出是倒影），向下 8→18→44dp 渐进",
         band=70, hold=0.22, tail=0.72, blur=8, near=8),
]


def sharpness_profile(page, w):
    """逐行高频能量：梯度**均方根**，用来量化"糊度"。

    两条都是踩过的坑：
    1. 只算横向差分，会在横向均匀的色带上退化成 0 —— 必须连纵向一起算。
    2. 用一阶差分的**均值**（总变差）不行：模糊把台阶摊成斜坡，总变差不变，
       数值根本不动。平方之后模糊才会真正压低它，所以用 RMS。
    """
    g = page.convert("L")
    px = g.load()
    gw, gh = g.size
    out = []
    for y in range(gh):
        s = 0
        for x in range(1, gw):
            dx = px[x, y] - px[x - 1, y]
            s += dx * dx
            if y:
                dy = px[x, y] - px[x, y - 1]
                s += dy * dy
        out.append((s / (gw - 1)) ** 0.5)
    return out


def metrics(page, w, name):
    blur = sharpness_profile(page, w)
    lum = page.convert("L")
    stat = ImageStat.Stat
    def L(y0, y1):
        return stat(lum.crop((0, y0, w, y1))).mean[0] / 255.0
    def HF(y0, y1):
        return sum(blur[y0:y1]) / max(1, y1 - y0)
    above_l, below_l = L(SEAM - 6, SEAM), L(SEAM, SEAM + 6)
    above_h, below_h = HF(SEAM - 24, SEAM - 4), HF(SEAM + 4, SEAM + 24)
    print(f"{name:28s} 亮度台阶 {below_l - above_l:+.4f}   "
          f"清晰度 {above_h:.4f} -> {below_h:.4f} "
          f"({below_h / max(above_h, 1e-6):.2f}x)")
    return below_l - above_l, above_h, below_h


def main():
    cover = Image.open(COVER).convert("RGB")
    page_w = int(PAGE_W * DP)
    # 背景色场：取封面最下 10% 的均值再压一点，模拟 PlayerBackdrop 的 bottom 段
    tail = cover.crop((0, int(cover.height * 0.9), cover.width, cover.height))
    mean = ImageStat.Stat(tail).mean
    backdrop = tuple(int(v * 0.55) for v in mean)

    pages = []
    for v in VARIANTS:
        page = build(cover, page_w, backdrop, v)
        pages.append((v, page))
        metrics(page, page_w, v["key"] + " " + v["name"])

    # 只截接缝附近，看过渡
    top = max(0, SEAM - int(150 * DP))
    bottom = SEAM + int(170 * DP)
    crop_w = page_w
    gap, label_h = 14, 46
    total_h = sum(bottom - top for _ in pages) + gap * (len(pages) - 1) + label_h * len(pages)
    sheet = Image.new("RGB", (crop_w, total_h), (18, 18, 20))
    d = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 22)
        fsmall = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 17)
    except Exception:
        font = fsmall = ImageFont.load_default()

    y = 0
    for v, page in pages:
        d.text((10, y + 6), f'{v["key"]}  {v["name"]}', font=font, fill=(255, 236, 200))
        d.text((10, y + 26), v["desc"], font=fsmall, fill=(160, 160, 165))
        y += label_h
        strip = page.crop((0, top, crop_w, bottom))
        sheet.paste(strip, (0, y))
        # 镜面线标一根细线，方便定位
        seam_y = y + (SEAM - top)
        for x in range(0, crop_w, 24):
            d.line([(x, seam_y), (x + 12, seam_y)], fill=(255, 80, 80), width=2)
        y += bottom - top + gap

    sheet.save(OUT)
    print("\n输出:", OUT, sheet.size)


if __name__ == "__main__":
    main()
