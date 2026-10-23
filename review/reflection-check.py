"""倒影观感体检：把"截图里的倒影"和"纯镜像合成"并排，看是否一致；
再扫肤色剖面，定位"第二张脸"到底落在哪一段。

用法：python review/reflection-check.py <截图路径>
输出：review/_reflection_check.png（三联：现状 / 纯镜像 / 差异）
"""

import sys

from PIL import Image, ImageChops

DP = 2.24
SEAM_PX = int(429 * DP)          # 镜面线 = heroHeight(429dp)


def skin_rows(img, y0, y1):
    """每行的肤色像素占比，用来定位人脸/皮肤。"""
    px = img.convert("RGB").load()
    w = img.width
    out = []
    for y in range(y0, y1):
        n = 0
        for x in range(0, w, 2):
            r, g, b = px[x, y]
            if r > 95 and g > 40 and b > 20 and r > g and r > b and abs(r - g) > 15:
                n += 1
        out.append(n / (w / 2))
    return out


def peak(profile, y0, limit=0.06):
    """返回肤色占比超过 limit 的行区间（像素）。"""
    runs, start = [], None
    for i, v in enumerate(profile):
        if v >= limit and start is None:
            start = i
        elif v < limit and start is not None:
            runs.append((y0 + start, y0 + i, max(profile[start:i])))
            start = None
    if start is not None:
        runs.append((y0 + start, y0 + len(profile), max(profile[start:])))
    return [r for r in runs if r[1] - r[0] > 20]


def main(path):
    shot = Image.open(path).convert("RGB")
    w, h = shot.size
    print(f"截图 {w}x{h}，镜面线 y={SEAM_PX}")

    cover = shot.crop((0, 0, w, SEAM_PX))
    actual = shot.crop((0, SEAM_PX, w, h))

    mirror = cover.transpose(Image.FLIP_TOP_BOTTOM).crop((0, 0, w, h - SEAM_PX))
    diff = ImageChops.difference(actual, mirror)

    # 三联图：现状 / 纯镜像 / 差异（差异放大 3 倍便于看）
    panels = [actual, mirror, diff.point(lambda v: min(255, v * 3))]
    labels_h = 0
    out = Image.new("RGB", (w * 3 + 24, actual.height + labels_h), (16, 16, 16))
    for i, p in enumerate(panels):
        out.paste(p, (i * (w + 12), labels_h))
    out = out.resize((out.width // 2, out.height // 2), Image.LANCZOS)
    out.save("review/_reflection_check.png")
    print("已写出 review/_reflection_check.png（左=现状 中=纯镜像 右=差异×3）")

    # 肤色剖面：封面 vs 倒影
    cv = peak(skin_rows(shot, 0, SEAM_PX), 0)
    rf = peak(skin_rows(shot, SEAM_PX, h), SEAM_PX)
    print()
    print("肤色（人脸）纵向区间")
    print("  封面:", [f"{a//int(DP)}–{b//int(DP)}dp" for a, b, _ in cv])
    print("  倒影:", [f"{a//int(DP)}–{b//int(DP)}dp" for a, b, _ in rf])
    print()
    print("若倒影是严格镜像，倒影里的脸应在：",
          [f"{(2 * SEAM_PX - b)//int(DP)}–{(2 * SEAM_PX - a)//int(DP)}dp" for a, b, _ in cv])


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "shot.jpg")
