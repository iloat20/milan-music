"""真机截图上的镜面过渡体检。

输入：设备截图（873×1920）。
输出：
  1. 镜面线的真实行号（纵向亮度梯度的绝对值最大行）
  2. 镜面线两侧的亮度台阶
  3. 镜面线两侧的清晰度（梯度 RMS）—— 台阶在哪一侧
  4. 封面侧 64dp 过渡带里到底糊没糊（逐行 RMS 剖面）

判读：
  封面侧 143px(64dp) 内 RMS 不下降 → SeamTransition 的上半没画出来
  封面侧下降但镜面处仍有断崖 → 包络拐点问题
"""

import sys

# 缺参数就退出。这里的兜底原来指向一张 clipboard 临时图 —— 那是一张真实存在过的
# 截图，于是"忘了给路径"和"给了路径"跑出来的是同一串数值，没人会发现验错了图。
#
# 校验放在 `from PIL import Image` 之前：否则缺依赖的环境会先抛 ModuleNotFoundError，
# 把"忘了给路径"伪装成"环境没装好"。
ARGS = sys.argv[1:]
DP = 873 / 390.0          # 2.2385 px per dp，按 390dp 宽的参考机标定
if "--density" in ARGS:
    index = ARGS.index("--density")
    if index + 2 > len(ARGS):
        print("用法：--density <px per dp>", file=sys.stderr)
        sys.exit(2)
    try:
        DP = float(ARGS[index + 1])
    except ValueError:
        print(f"--density 需要一个数字，收到 {ARGS[index + 1]!r}", file=sys.stderr)
        sys.exit(2)
    ARGS = ARGS[:index] + ARGS[index + 2:]

if not ARGS:
    print(
        "用法：python review/seam-audit-shot.py <真机截图路径> [--density <px per dp>]",
        file=sys.stderr,
    )
    sys.exit(2)

from PIL import Image

SHOT = ARGS[0]

im = Image.open(SHOT).convert("L")
W, H = im.size
px = im.load()
DP_WIDTH = W / DP
print(f"截图 {W}x{H}  density={DP:.3f}  宽 {DP_WIDTH:.0f}dp  64dp={64*DP:.0f}px  422dp={422*DP:.0f}px")

# 下面的"理论位置 422dp""封面侧 64dp"全是按参考机标定的常量。换一台宽度的机器，
# 它们就都不成立 —— 而输出依旧是一屏带小数点的数字，看不出错。宁可显式警告。
if not 380 <= DP_WIDTH <= 400:
    print(
        f"\n警告：几何常量按 390dp 宽标定，这张图是 {DP_WIDTH:.0f}dp 宽 —— "
        "dp↔px 换算不成立，下面的偏差与带宽数字不能直接套用。\n"
        "      确认设备密度后用 --density 指定该机的 px/dp。",
        file=sys.stderr,
    )


def row_rms(y0, y1):
    """[y0,y1) 行的梯度均方根：横向 + 纵向。"""
    s = n = 0
    for y in range(y0, y1):
        for x in range(1, W, 2):
            dx = px[x, y] - px[x - 1, y]
            s += dx * dx
            if y:
                dy = px[x, y] - px[x, y - 1]
                s += dy * dy
            n += 2
    return (s / max(1, n)) ** 0.5


def row_mean(y0, y1):
    t = 0
    n = 0
    for y in range(y0, y1):
        for x in range(0, W, 3):
            t += px[x, y]
            n += 1
    return t / max(1, n) / 255.0


# ---- 1. 找镜面线：纵向亮度差的绝对值最大的行（只在封面上半页找）----
best, best_y = 0, 0
for y in range(int(H * 0.2), int(H * 0.75)):
    s = 0
    for x in range(0, W, 2):
        s += abs(px[x, y] - px[x, y - 1])
    if s > best:
        best, best_y = s, y
print(f"\n镜面线（最大纵向梯度行）= y {best_y}  强度 {best / (W / 2):.2f}/px")
print(f"  理论位置 422dp = {422 * DP:.0f}px，偏差 {best_y - 422 * DP:+.0f}px")

# ---- 2. 亮度台阶 ----
a = row_mean(best_y - 8, best_y)
b = row_mean(best_y, best_y + 8)
print(f"\n亮度台阶: 上 {a:.4f} → 下 {b:.4f}  Δ {b - a:+.4f}（满量程 1.0）")

# ---- 3. 清晰度台阶 ----
ha = row_rms(best_y - 24, best_y - 4)
hb = row_rms(best_y + 4, best_y + 24)
print(f"清晰度:   上 {ha:.2f} → 下 {hb:.2f}  ({hb / max(ha, 1e-6):.2f}x)")

# ---- 4. 封面侧过渡带逐行剖面 ----
band = int(64 * DP)
print(f"\n封面侧 {band}px 内逐行 RMS（每 {band // 12}px 一行）：")
step = max(1, band // 12)
for y in range(best_y - band, best_y, step):
    r = row_rms(y, y + 2)
    print(f"  y={y:5d}  距镜面 {best_y - y:4d}px  RMS {r:6.2f}")

print("\n对照：更上方（应当是清晰封面）")
for y in range(best_y - band - 260, best_y - band - 40, 40):
    r = row_rms(y, y + 2)
    print(f"  y={y:5d}  距镜面 {best_y - y:4d}px  RMS {r:6.2f}")

print("\n镜面下方（倒影）")
for y in range(best_y + 4, best_y + band, step):
    r = row_rms(y, y + 2)
    print(f"  y={y:5d}  距镜面 {y - best_y:4d}px  RMS {r:6.2f}")
