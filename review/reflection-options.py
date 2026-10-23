"""倒影观感方案对比（直接操作真机截图，零失真）。

用法：python review/reflection-options.py <截图路径>
输出：review/_reflection_options.png

面板：
  1 现状（真机截图）
  2 纯镜像，不糊 —— 诊断用：暴露"内容重复"本来有多明显
  3 短倒影：倒影只保留镜面线下 SHORT_DP，之后化进色场
  4 压缩倒影：倒影纵向压到 SQUEEZE，内容变形 → 读成反射

面板 3/4 都基于**截图里真实的倒影像素**，不再重新合成，所以看到的就是
"只改这一个变量"的结果。
"""

import sys

from PIL import Image

DP = 2.24
SEAM_PX = int(429 * DP)
SHORT_DP = 150.0
SQUEEZE = 0.62


def smoothstep(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def fade_out(img, bg, start_dp, end_dp):
    """把 img 从 start_dp 起、到 end_dp 处完全化进 bg。"""
    w, h = img.size
    mask = Image.new("L", (1, h))
    vals = []
    for y in range(h):
        y_dp = y / DP
        if y_dp <= start_dp:
            vals.append(0)
        elif y_dp >= end_dp:
            vals.append(255)
        else:
            vals.append(int(round(255 * smoothstep((y_dp - start_dp) / (end_dp - start_dp)))))
    mask.putdata(vals)
    out = img.copy()
    out.paste(Image.new("RGB", (w, h), bg), (0, 0), mask.resize((w, h)))
    return out


def main(path):
    shot = Image.open(path).convert("RGB")
    w, h = shot.size
    cover = shot.crop((0, 0, w, SEAM_PX))
    now = shot.crop((0, SEAM_PX, w, h))
    refl_h = h - SEAM_PX
    bg = shot.crop((0, h - 40, w, h)).resize((1, 1), Image.LANCZOS).getpixel((0, 0))

    # 2) 纯镜像，不糊
    plain = cover.transpose(Image.FLIP_TOP_BOTTOM)

    # 3) 短倒影：只留前 150dp，其余交给色场
    short_h = int(SHORT_DP * DP)
    short = Image.new("RGB", (w, refl_h), bg)
    short.paste(now.crop((0, 0, w, short_h)), (0, 0))
    short = fade_out(short, bg, SHORT_DP * 0.55, SHORT_DP)

    # 4) 压缩 0.62：整段倒影压扁，内容随之变形
    sq_h = int(refl_h * SQUEEZE)
    sq = Image.new("RGB", (w, refl_h), bg)
    sq.paste(now.resize((w, sq_h), Image.LANCZOS), (0, 0))
    sq = fade_out(sq, bg, sq_h * 0.62 / DP, sq_h / DP)

    panels = [now, plain, short, sq]
    gap = 10
    out = Image.new("RGB", (w * 4 + gap * 3, refl_h), (16, 16, 16))
    for i, p in enumerate(panels):
        out.paste(p, (i * (w + gap), 0))
    out = out.resize((out.width // 3, out.height // 3), Image.LANCZOS)
    out.save("review/_reflection_options.png")
    print("已写出 review/_reflection_options.png")
    print(f"  1 现状 / 2 纯镜像 / 3 短倒影 {SHORT_DP:.0f}dp / 4 压缩 {SQUEEZE}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "shot.jpg")
