# 结论台账（claim ledger）— 2026-10-29 / Track H

对应需求 `2026-10-29-deep-spec.md` 的 13 行验收表。**只填有读数的行**；没有仪器的行保持 D 级并写明缺什么。

等级定义：**A** 真机仪器读数 · **B** CI/host 确定性产物 · **C** 静态推断 · **D** 未测推断。
规则：D 级不得进入"全部通过"。

---

## 一、本轮闭合（5 行，全部 B 级）

| # | 断言 | 仪器 / 原始数据 | 阈值 | 外部基准 | 读数 | 级 |
|---|---|---|---|---|---|---|
| 4 | 畸形输入不抛、不超读、可复现 | `Id3StreamFuzzTest`：固定种子 `0x1D3F022` 生成 15,000 例（随机 3k / 结构化 4k / 良构 2k / 变异 4k / 截断 2k），计数流统计 `read` 字节 | 0 未捕获异常；读取 ≤ min(文件, 1 MB)+16；两次调用同值 | 不变量（机器生成输入，非人工挑选） | 通过。分歧 35 例，全部是「缓冲解析有值 / 流式返回 null」的截断类，无一例「两路径给出不同数值」 | **B** |
| 5 | 单测有牙（变异率） | `review/mutate.py`：17 个语义变异体逐条投放，真实编译 + 真实跑 3 个测试类 | killed / (killed+survived) | 变异测试（mutants killed） | **17/17 = 100%**；去掉 fuzz 测试后同样 17/17 | **B** |
| 11a | 发布物 = 被审代码（CI 包 vs 本机包） | `review/apk-diff.py`：逐条目 压缩尺寸 + CRC + 解压后 SHA-256 | 差异集合 ⊆ 已知非确定项 | 逐条目哈希 | 185 条目，**184 逐字节相同**，仅 `META-INF/version-control-info.textproto` 变更（120 B→120 B，sha `73c548a5…`→`280b2396…`） | **B** |
| 11b | 版本间差异可归因（1.0.86→1.0.87） | 同上 | 每条差异都能解释 | 逐条目哈希 | 178 相同 / 5 变更 / 2 删 / 2 增：`classes.dex` +1,012 B、manifest 同长不同（版本号）、`baseline.prof` −22、`.profm` −12、`version-control-info`；增删为 `META-INF/services/{c7,m00}`→`{d7,n00}` 的混淆改名。与发布说明逐条一致 | **B** |
| 13a | 文字对比度达 WCAG 2.2 AA | `DarkThemeToneTest` / `IconInkTest`（已存在） | 正文 ≥7:1、次要 ≥4.5:1、图标 ≥3:1 | WCAG 2.2 AA 1.4.3 / 1.4.11 | 已由既有门禁覆盖，无需新建 | **B** |
| 13b | 触控目标尺寸 | `ProgressTouchTargetTest`（已存在，48dp） | ≥24×24 CSS px | WCAG 2.2 AA 2.5.8 | 48dp > 24，已覆盖 | **B** |

## 二、被推翻的结论（本轮唯一"新知识"）

**原假设**：「31+8 条手写用例覆盖不足，模糊测试能补上机器想不到的输入」。

**证伪过程**：先跑 14 个变异体，两套断言（有/无 fuzz）都是 100%；补 3 个只有生成式输入才可能触发的边界变异（填充检测 `N1`、循环边界 `N2`、syncsafe 高位守卫 `N3`），仍然两套都 100%。

**结论**：在 `Id3StreamReader` / `ReplayGainTags` 这条路径上，**既有断言的杀伤力已经是 100%（就这 17 个变异体而言），fuzz 的增量杀伤为 0**。fuzz 的真实价值只剩一条：对 15,000 个生成输入证明「不抛、不超读、确定性」，这是崩溃安全性，不是覆盖补强。

**边界**：17 个变异体是我读过测试后挑的，100% 是这 17 个的下界，不是"任意 bug 都会被抓住"。要得到无偏估计得随机变异（本文未做）。

**由此产生的处置建议**：不要再往这条路径加测试。真正薄弱的是没有任何变异覆盖的区域——UI/动效（`Motion.kt`、`CoverReflection.kt`、`MemoCache.kt`）只有"参数对不对"的断言，没有"行为错了会不会被发现"的度量。

## 三、仍未闭合（8 行，保持 D 级）

| # | 断言 | 缺的仪器 | 备注 |
|---|---|---|---|
| 1 | 大封面切歌不再整段读 tag | 真机 + 应用内读 `/proc/self/io` 的 `rchar` 差值 | 静态上已改（流式走 `skip`→`lseek`），**没有计数器读数** |
| 2 | 解析耗时未被推高 | 真机 + `elapsedRealtimeNanos()` 包住解析段，配对 ABBA ≥30 次 | 注意 `nanoTime` 在深睡停走 |
| 3 | ReplayGain 数值正确 | 语料 20 曲 + `ffmpeg -af ebur128` 对照 | 现有断言只验证"与自己的理解一致" |
| 6 | 列表滚动不掉帧 | 真机 `dumpsys gfxinfo <pkg> framestats` | slow >16.67 ms、frozen >700 ms |
| 7 | 音频不断续 | 真机 `onAudioUnderrun` / `dumpsys media.audio_flinger` | — |
| 8 | 通知/锁屏封面不串 | 真机截图序列 + `dumpsys media_session` | — |
| 9 | 起播路径可解释 | 真机 Perfetto trace | "12 ms 不是主因"至今是推断 |
| 10 | baseline profile 真生效 | 真机 `dumpsys package` 的 dexopt 状态 | 包内 profile 仍全是依赖合并结果 |
| 12 | 产物来源第三方可验证 | GitHub artifact attestation（`actions/attest-build-provenance` + `id-token`/`attestations` 写权限） | 需改 CI 并 push，本轮未动 |

## 四、方法学附注（给下一个做这件事的人）

1. **工具本身要先被证明不会破坏被测对象。** 变异 harness 第一版用 `read_text/write_text` 做备份，在 Windows 上把 CRLF 语义交给平台翻译，还原校验直接失败。改成字节级备份后，用 `git hash-object` == `git ls-files -s` == `HEAD:path` 三个 blob 哈希相同来证明源码未被污染——这比"我看了一眼 diff 没问题"强。
2. **自证不算证明。** 本轮三条 B 级证据里，只有 fuzz 和变异率是"别人能拿同一个种子重跑"，APK 逐条目 diff 是"别人能拿同样两个文件重跑"。发布说明里"我们比对了 sha256"仍然是他证不了的自证。
3. **负对照必须做。** 把 `frameId == "TXXX"` 改成 `!=`，fuzz 立刻红并打印出可复现的十六进制样本；否则无法区分"全绿"和"没测到"。
