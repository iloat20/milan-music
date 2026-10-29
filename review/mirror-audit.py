"""倒影对齐体检：倒影区显示的是封面的哪一段？

用法：python review/mirror-audit.py <截图路径>

做法：把倒影区的一块（宽全图、高 BLOCK）**只按行**翻转，回到封面范围内滑动
搜索归一化互相关（NCC）最大的位置。Scrim 压暗和模糊会压低绝对值，但 NCC 对
线性亮度变化免疫，所以"内容是否同一段"会非常明确。

判读：
  · 倒影第一块匹配到封面**底部**  → 正确（1:1 镜像，接得上）
  · 倒影第一块匹配到封面**顶部**  → 错位，倒影在重复封面，用户看到"两次封面"
"""

import sys

# 缺参数就退出，不用默认路径。曾经这里的兜底是 "shot.jpg"：在别人的工作目录里
# 它要么不存在（报一句 file not found），要么恰好存在一张无关的图 —— 后者会打印
# 一整屏像模像样的 NCC 数值，而结论完全不属于要验的那张截图。取证工具用错输入，
# 比取不到证更糟。
#
# 校验放在 `from PIL import Image` 之前：否则缺依赖的环境会先抛 ModuleNotFoundError，
# 把"忘了给路径"伪装成"环境没装好"。
if len(sys.argv) < 2:
    print("用法：python review/mirror-audit.py <真机截图路径>", file=sys.stderr)
    sys.exit(2)

from PIL import Image

W_SCALE = 4          # 缩小倍数，纯 Python 也能跑
BLOCK = 40           # 比对块高（缩小后像素）
SEAM_DP = 429.0      # CoverLayout.heroHeight(857.7dp, 300dp) = 429dp
PX_PER_DP = 2.24     # 873 / 390dp


def ncc(a, b):
    """归一化互相关：对整体亮度/对比度线性变化免疫。"""
    n = len(a)
    ma = sum(a) / n
    mb = sum(b) / n
    va = sum((v - ma) ** 2 for v in a) ** 0.5
    vb = sum((v - mb) ** 2 for v in b) ** 0.5
    if va < 1e-6 or vb < 1e-6:
        return 0.0
    return sum((a[i] - ma) * (b[i] - mb) for i in range(n)) / (va * vb)


def rows(img, y0, h):
    """返回 h 行、每行一个列表 —— **二维结构必须保留**，否则翻转会连左右一起反。"""
    px = img.load()
    return [[px[x, y] for x in range(img.width)] for y in range(y0, y0 + h)]


def flat(block):
    return [v for row in block for v in row]


def flip(block):
    return block[::-1]


def main(path):
    img = Image.open(path).convert("L")
    w, h = img.width, img.height
    small = img.resize((w // W_SCALE, h // W_SCALE), Image.LANCZOS)
    sw, sh = small.size
    seam = int(round(SEAM_DP * PX_PER_DP / W_SCALE))
    print(f"截图 {w}x{h} → 分析 {sw}x{sh}，镜面线 y≈{seam}（= {SEAM_DP}dp）")

    print()
    print("倒影一块（只翻行）→ 封面里最匹配的位置")
    print("  dy   最佳 y0   对应封面位置      NCC")
    for dy in (0, 20, 40, 60, 80, 100):
        top = seam + dy
        if top + BLOCK > sh:
            break
        block = flat(flip(rows(small, top, BLOCK)))
        best_y, best = -1, -2.0
        for y0 in range(0, seam - BLOCK + 1):
            score = ncc(block, flat(rows(small, y0, BLOCK)))
            if score > best:
                best_y, best = y0, score
        frac = best_y / seam
        where = "封面底部" if frac > 0.55 else ("封面中部" if frac > 0.2 else "封面顶部")
        print(f" {dy:4d}  {best_y:7d}   {where}（{frac:.2f}）  {best:+.3f}")

    block0 = flat(flip(rows(small, seam, BLOCK)))
    bottom = flat(rows(small, seam - BLOCK, BLOCK))
    top = flat(rows(small, 0, BLOCK))
    print()
    print("两个假设的直接比对（倒影第一块）")
    print(f"  正确镜像（= 封面最后 {BLOCK} 行）: NCC {ncc(block0, bottom):+.3f}")
    print(f"  重复封面（= 封面最前 {BLOCK} 行）: NCC {ncc(block0, top):+.3f}")
    print(f"  参照：封面最前/最后一块相互     : NCC {ncc(top, bottom):+.3f}（应接近 0）")


if __name__ == "__main__":
    main(sys.argv[1])
