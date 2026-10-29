# 最优档：ReplayGain 读放大（P2-4），P2-5 / P2-6 的处置（2026-10-29）

基线 1.0.86（b87）→ 本批 1.0.87（b88）。审计遗留三项，本批只实现一项，另外两项的
不实现理由写在下面——**否决本身也是结论**，不留"待办"假象。

## 一、结论摘要

| 项 | 处置 | 依据 |
|---|---|---|
| P2-4 `ReplayGainReader` 对无 ReplayGain 的文件读满 1 MB | **实现** | 根因是"声明大小当读取量"，可流式化解，纯函数可测 |
| P2-5 通知封面缓存改按专辑 id | **不做** | 会显示错封面：同 `albumId` 不保证内嵌图相同 |
| P2-6 播放页 72dp 全屏模糊 + 4 层倒影的 GPU 峰值 | **暂缓** | 任何减层都改视觉，必须真机量；本机无设备、无模拟器 |

## 二、P2-4：把"声明大小"从读取量变成预算

### 根因

`readLimitFor` 返回 ID3 声明的 tag 大小（上限 1 MB），`readHead` 把这个数当作读取量，
整段读进 `ByteArrayOutputStream`，再 `toByteArray()` 复制一次。于是一个带 900 KB
`APIC` 的 MP3，每次切歌要付：900 KB 读 + 900 KB 复制的峰值 —— 只为找一个通常位于头
1 KB 的 `TXXX` 帧。

"读满 1 MB"不是实现疏忽，是 `parse(ByteArray)` 的接口决定的：它要一段连续字节才能
在内存里跳帧。

### 改法

帧表是自描述的（`frameId` 4 + `size` 4 + `flags` 2），不需要 body 就能走到下一帧。

- `Id3StreamReader`：每帧读 10 字节头；`TXXX` 读 body，其余 `skip`。`skip` 在
  `ContentResolver` 给出的文件流上落到 `lseek`，不是读后丢弃。
- `ReplayGainReader.readGain`：peek 10 字节 → `readLimitFor > SMALL_HEAD_BYTES` 走流式，
  否则 `reset` 走原来的 `readHead` + `parse`。**小于 64 KB 的 tag 行为完全不变**，
  没有回归面。
- `MAX_TAG_BYTES` 的语义从"分配上限"变成"预算上限"：声明 2 GB 的损坏头只让 walk 停下，
  不再决定分配。

### 为什么不动 `parseId3`

`Id3StreamReader` 与 `parseId3` 有重复的帧表规则，这是本批唯一的架构代价。用不重复的
办法（把 `parse` 改成流式）会让 31 个既有用例连同它们覆盖的 8 个健壮性场景一起失效，
风险远大于收益。折中是：

- 共享 `parseUserText` / `parseGainDb`（解码逻辑只有一份，改一处两处生效）；
- 新增一条门禁 `streamed and buffered parsing agree`，同一批 fixture 两条路径必须给出
  相同结果 —— 规则漂移会被这条用例抓到时，是编译期看不到的那类 bug；
- 流式路径**不接受**任何 `parseId3` 不接受的输入（v2.2 同样拒绝、同样的帧长校验、
  同样的零填充终止），行为边界一致。

### 收益（可测）

300 KB `APIC` 在 `TXXX` 之前、tag 总量 300 KB 的文件：

| | 读入字节 | 内存峰值 |
|---|---|---|
| 改前 | 300 KB+ | 约 2 × tag 大小 |
| 改后 | ~70 B | 仅 `TXXX` body |

无 gain 的大 tag 同样只读帧头：原来的"读满才能断言没有"变成"走完帧表才能断言没有"，
而走帧表不需要 body。

## 三、P2-5 不做：复用会显示错封面

现状 key 是音频 `Uri`，切到同专辑下一首要重新解码一次（~10 ms、~1 MB）。看起来按
`albumId` 复用是纯收益，但它预设了"同专辑内嵌图相同"——这个前提不成立：

- `MediaStore` 的 `album_id` 是按专辑目录与元数据分组的，不校验 `APIC` 内容；
- 合辑、手动改过单曲封面的专辑、以及"每首歌内嵌不同图"的 DIY 库都真实存在。

后果是切歌后通知栏、锁屏、Android Auto 仍显示上一首的封面。这是一个**安静的正确性
错误**：没有异常、没有日志，只有用户偶尔发现封面不对。参考本项目对 ReplayGain 的判据
——"错的值比没有值更糟"——这里同样适用：省一次解码换一次错封面，不成立。

真要消除重复解码，只能做"同 `albumId` 且已验证位图字节相同"的缓存，需要读字节哈希，
成本高于收益。

## 四、P2-6 暂缓：这一项没有证据就不该动

播放页有四层倒影（半径 0 / 12 / 24 / 34 dp）+ 72 dp 全屏模糊 + 28 dp 边缘模糊。减少
任何一层都会改变视觉输出，而本项目对倒影的判据是**真机拍镜面线、量 std**（`review/seam-*.py`），
不是看截图感觉。

本机确认不可用：

```
$ adb devices          → List of devices attached（空）
$ emulator -list-avds  → command not found
BIOS: VirtualizationFirmwareEnabled = False
```

所以在拿到设备之前，这一项的"最优方案"只能是方案而不是代码。若要推进，顺序应是：
先用 `dumpsys gfxinfo <pkg> framestats` 取 120 帧的 99 分位确认瓶颈到底在模糊还是倒影，
再决定要不要改——目前"GPU 峰值高"仍是审计推断，没有测量。

## 五、验证

| 项 | 结果 |
|---|---|
| `:app:testDebugUnitTest` | **295 例全绿**（b87 为 282，新增 13） |
| 零 `w:` | 0 |
| `:app:lintRelease` | `lint-results-release.xml` 无 `<issues>` 条目，0 error |
| 签名 | v2 true / v3 true，证书 `340ceea2…`（与 b87 同一枚） |
| `zipalign -c -P 16` | 通过 |
| badging | `versionCode='88' versionName='1.0.87'`，`native-code: 'arm64-v8a'` 唯一 |
| APK 体积 | 2,301,656 B —— 与 b87 **完全相同** |
| `classes.dex` | 4,079,904 → 4,080,916（+1,012 B） |
| `classes.dex` sha256 | `357145e2…` → `2f78ecb4…` |

体积相同是因为压缩后的增量被对齐填充吸收，**改动进包以 dex sha256 为准**（这是本项目
既有的判据，不是本批新增）。

### 红态验证（两轮）

1. `skipFully` → `readFully(ByteArray(frameSize))`：`Id3StreamReaderTest` 11 例中
   **3 红**（`cover art ahead of the gain frame is skipped, not read`、
   `an untagged tag is not read to its declared size`、
   `an id3v2_4 extended header is skipped before the frame table`），8 例正确性仍绿
   —— 证明读量断言只锁读量，不锁解析结果。还原后复绿。
2. `readLimitFor` 的 cap 从 `MAX_TAG_BYTES` 改成 `SMALL_HEAD_BYTES`：
   `ReplayGainReadLimitTest` 6 例中 **2 红**（`an oversized id3 tag is capped`、
   `a tag above the small window asks for the streamed path`）—— 证明"分流开关"被
   门禁锁住，不会有人改掉 cap 后让流式路径静默失效。还原后复绿。

## 六、真机仍待确认

本机无设备，以下只能在设备上验证：

1. 播一张内嵌封面较大的 MP3（tag > 64 KB），`logcat -s PlaybackService` 里
   `replaygain … readMs=` 应从数十毫秒降到个位数；改前改后可对比。
2. 连续切同一专辑的 20 首：`replaygain` 行应每首都有 `readMs`，且无重复读取
   （缓存命中时该日志不出现）。
3. FLAC / Ogg 曲目行为不变（不走流式路径，`readLimitFor` 返回 64 KB）。

## 七、下一批候选

- P2-6 先测量再决定（需要设备）。
- `review/2026-10-29-playback-write-path.md` 与本批文档一并入库（前者此前是未跟踪文件）。
