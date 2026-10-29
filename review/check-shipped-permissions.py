"""成品 APK 的权限白名单断言。

README 用整段篇幅承诺"只申请本地播放必需的权限、包里没有任何网络权限"，但在这之前
那条承诺只靠人工 `aapt2 dump permissions` 核对 —— 谁误删了 manifest 里那行
`tools:node="remove"`，网络权限就会静默漏进成品，而所有测试与 lint 依然全绿。
本脚本把那段文字变成退出码。

用法（两种输入，输出等价）：

    # 成品 APK：由 aapt2 解出权限行，管道送入
    aapt2 dump permissions app-release.apk | python review/check-shipped-permissions.py --stdin

    # 合并后的清单：不打包也能查，用于提交前自查
    python review/check-shipped-permissions.py --manifest AndroidManifest.xml

`--stdin` 是显式的，不靠"没给参数就去读 stdin"来猜：那样在交互终端里误跑一次就会
挂在等待输入上，而没有任何提示。

退出码：0 = 与白名单完全一致；1 = 多一个、少一个或 maxSdkVersion 不符；2 = 输入无法解析。

白名单里的每一条都必须有理由。加权限是一次产品决定，不是一个实现细节：本应用不联网、
不采集数据，任何 `INTERNET` / `ACCESS_NETWORK_STATE` 出现在成品里都是回归，不讨论。
"""

import re
import sys
import xml.etree.ElementTree as ET

# name -> 期望的 maxSdkVersion（None 表示不设上限）
EXPECTED = {
    # Android 13+ 的音频读取权限。
    "android.permission.READ_MEDIA_AUDIO": None,
    # Android 12 及以下的等价权限。maxSdkVersion=32 是刻意的：没有它，13+ 会同时
    # 索要两代权限，用户在设置里会看到一条多余的条目。
    "android.permission.READ_EXTERNAL_STORAGE": "32",
    # 前台播放通知（Android 13+ 需要运行时授权）。
    "android.permission.POST_NOTIFICATIONS": None,
    # 后台播放的会话服务。
    "android.permission.FOREGROUND_SERVICE": None,
    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK": None,
    # 熄屏继续播放。
    "android.permission.WAKE_LOCK": None,
    # AndroidX core 为 non-exported 动态注册的 receiver 自动注入的包内自定义权限。
    # 不在 manifest 里手写，是依赖合并的结果；列出来是为了让断言是"精确相等"而不是
    # "包含"，否则任何新增的传递权限都不会被发现。
    "com.milan.music.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION": None,
}

# 即使将来白名单被放宽，这三条也不该出现在成品里。单独列出，让失败信息直指原因。
FORBIDDEN = {
    "android.permission.INTERNET": "本应用不联网，Media3 传递引入的 INTERNET 必须被显式移除",
    "android.permission.ACCESS_NETWORK_STATE": "同上，联网状态对本地播放没有用途",
}

AAPT_LINE = re.compile(r"^uses-permission: name='([^']+)'(?:\s+maxSdkVersion='([^']+)')?\s*$")
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def from_aapt(text):
    found = {}
    for line in text.splitlines():
        match = AAPT_LINE.match(line.strip())
        if match:
            found[match.group(1)] = match.group(2)
    return found


def from_manifest(path):
    root = ET.parse(path).getroot()
    found = {}
    for element in root.findall("uses-permission"):
        name = element.get(ANDROID_NS + "name")
        if name:
            found[name] = element.get(ANDROID_NS + "maxSdkVersion")
    return found


def main(argv):
    if "--manifest" in argv:
        index = argv.index("--manifest")
        if index + 1 >= len(argv):
            print("用法：--manifest <AndroidManifest.xml>", file=sys.stderr)
            return 2
        try:
            found = from_manifest(argv[index + 1])
        except (OSError, ET.ParseError) as error:
            print(f"读不出清单：{error}", file=sys.stderr)
            return 2
    elif "--stdin" in argv:
        found = from_aapt(sys.stdin.read())
    else:
        print(__doc__, file=sys.stderr)
        return 2

    if not found:
        print("没有解析到任何 uses-permission —— 输入不是 aapt2 的输出，或清单为空", file=sys.stderr)
        return 2

    problems = []
    for name, reason in FORBIDDEN.items():
        if name in found:
            problems.append(f"禁止的权限出现在成品里：{name}（{reason}）")
    for name, max_sdk in EXPECTED.items():
        if name not in found:
            problems.append(f"缺少权限：{name}")
        elif (found[name] or None) != max_sdk:
            problems.append(
                f"{name} 的 maxSdkVersion 应为 {max_sdk or '未设置'}，实际 {found[name] or '未设置'}"
            )
    for name in found:
        if name not in EXPECTED and name not in FORBIDDEN:
            problems.append(f"白名单外的新权限：{name}（加权限是一次产品决定，请先更新 README 与本脚本）")

    if problems:
        for problem in problems:
            print(f"::error::{problem}" if _in_actions() else problem, file=sys.stderr)
        return 1

    print(f"权限与白名单一致：[{len(EXPECTED)}] 条，无网络权限")
    for name in sorted(found):
        suffix = f" maxSdkVersion={found[name]}" if found[name] else ""
        print(f"  {name}{suffix}")
    return 0


def _in_actions():
    """在 GitHub Actions 里把失败原因写成注解 —— 作业日志要凭据，注解公开可读。"""
    import os

    return os.environ.get("GITHUB_ACTIONS") == "true"


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
