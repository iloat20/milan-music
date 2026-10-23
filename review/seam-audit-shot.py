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
from PIL import Image

SHOT = sys.argv[1] if len(sys.argv) > 1 else \
    r"C:\Users\Administrator\.workbuddy\clipboard-images\clipboard-2026-10-23T04-20-55-054Z-c38b1ca2.jpg"
DP = 873 / 390.0          # 2.2385 px per dp

im = Image.open(SHOT).convert("L")
W, H = im.size
px = im.load()
print(f"截图 {W}x{H}  density={DP:.3f}  64dp={64*DP:.0f}px  422dp={422*DP:.0f}px")


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
