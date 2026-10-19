# 批次 H：ML Kit GenAI Prompt API 接入评估

基线：批次 G 之后、未发版。本批次**不改任何代码**，只做事实核查与可行性判定。

结论：**不建议接入。** 不是"技术不成熟，再等等"这种含糊判断，而是三条各自独立就能否决的硬阻塞，
其中第一条直接说明它对这个应用的国内用户基数命中率为零。三条都会在下文附上可复核的证据。

---

## 一、先更正版本：beta3 已经过期

Google Maven 上的实际版本列表（`maven-metadata.xml`）：

```
1.0.0-alpha1 / 1.0.0-beta1 / 1.0.0-beta2 / 1.0.0-beta3 / 1.0.0-beta4
latest = release = 1.0.0-beta4
```

`beta3` 引入的是 System Instruction 与 Structured Output；`beta4`（2026-07-21）是针对
Gemini Nano v4 的兼容性修复。Pixel 11 / Galaxy Z Flip8 / Z Fold8 属于 nano-v4，**只有 beta4 才在这些机器上可用**。
如果按 beta3 接入，等于一上手就不支持最新的三款机型。

---

## 二、真实 API 面（反编译 `genai-prompt-1.0.0-beta4.aar` 得到，非文档抄写）

文档页只给了最简用法，实际公开 API 比文档多出一组**运行时能力探测**方法，这组方法恰恰说明
"Prompt API 能用"不等于"结构化输出能用"。

```kotlin
interface GenerativeModel {
    suspend fun checkStatus(): Int                       // FeatureStatus.UNAVAILABLE/DOWNLOADABLE/DOWNLOADING/AVAILABLE
    suspend fun getBaseModelName(): String               // 设备上的 Nano 版本
    suspend fun getTokenLimit(): Int                     // 动态上限，不是写死的 4000
    suspend fun isStructuredOutputFeatureAvailable(): Boolean
    suspend fun isSystemPromptAvailable(): Boolean
    suspend fun isThinkingModeAvailable(): Boolean
    suspend fun isCachingFeatureAvailable(): Boolean
    suspend fun countTokens(request: GenerateContentRequest): CountTokensResponse
    suspend fun warmup()
    fun download(): Flow<DownloadStatus>
    suspend fun generateContent(request): GenerateContentResponse
    fun generateContentStream(request): Flow<GenerateContentResponse>
    fun close()
}
```

请求侧 `GenerateContentRequest.Builder` 支持 `systemInstruction`、`temperature`、`seed`、`topK`、
`candidateCount`、`maxOutputTokens`、`promptPrefix`、`cachedContextName`、`enableThinking`。
`enableThinking`（思考模式）文档里完全没有提，属于 beta4 未公开能力。

`GenAiException.ErrorCode` 共 21 个，与本文相关的：`BUSY`（短时配额）、
`PER_APP_BATTERY_USE_QUOTA_EXCEEDED`（日配额）、`BACKGROUND_USE_BLOCKED`、`NOT_ENOUGH_DISK_SPACE`、
`NEEDS_SYSTEM_UPDATE`、`AICORE_INCOMPATIBLE`。另外异常本身带 `getRetryDelay(): Duration`，
说明配额类错误应当按服务端给的时长退避，而不是自己写固定指数。

---

## 三、三条硬阻塞

### 阻塞 1（决定性）：国内行货机型没有 AICore，命中率接近零

AICore 是 Gemini Nano 的宿主，以 `com.google.android.aicore` 系统应用的形式**通过 Google Play 分发**，
不属于 AOSP。没有预装 GMS 的设备无法使用。

对这个应用意味着：README:3 写的是"Android 本地音乐播放器"，用户主体是国内安卓机。国内厂商 ROM
普遍不含 GMS，AICore 不存在 → `checkStatus()` 返回 `UNAVAILABLE` → 功能永远进灰。
Google 公布的 Prompt API 机型清单里确实有小米 17、OPPO Find X9、vivo X300、荣耀 Magic 8 Pro、
一加 15，但这些是**海外版**；国内行货版本需实测确认，不能按清单直接推断。

换句话说：这个特性在目标用户里能真正跑起来的比例，可能比"全面屏手势"这种机型要求还低。

### 阻塞 2：中文不在已验证范围内

同一套 Gemini Nano 基座上，唯一**显式声明语言支持**的是 Summarization API：

> The GenAI Summarization API supports English, Japanese, and Korean.

Prompt API 没有语言参数，Google 也没有公布它的验证语言清单。这不是"支持所有语言"，
而是"没承诺"。对一个中文曲库应用，"自然语言搜歌"的效果完全落在未验证区间，
而且**在当前环境下无法验证**——手上没有一台 Nano 设备，写出来的提示词质量和召回率都是猜的。

### 阻塞 3：遥测管线会被静默合并进 APK，与"不采集任何数据"冲突

`genai-prompt` 自身的清单很干净：

```
=== genai-prompt 440 bytes
   com.google.android.aicore
   com.google.android.apps.aicore.service.BIND_SERVICE
```

但它 POM 里带的传递依赖会把下面这一整套合并进来（`transport-runtime` 清单实录）：

```
=== transport-runtime 1826 bytes
   android.permission.ACCESS_NETWORK_STATE
   android.permission.BIND_JOB_SERVICE
   com.google.android.datatransport.runtime.backends.TransportBackendDiscovery
   com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService
   com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver
```

POM 里的传递依赖还包括 `firebase-encoders`、`firebase-encoders-json`、`play-services-basement`、
`play-services-tasks`。而 Google 自己的数据披露页对 GenAI 特性写的是：

> For GenAI features: User, device or other identifiers used for diagnostics and usage analytics.

也就是说：不写一行遥测代码，只要加上这个依赖，APK 就会多出 JobScheduler 服务、AlarmManager 广播接收器
和 ACCESS_NETWORK_STATE 权限，并具备向 `firebaselogging.googleapis.com` 上报的能力。
当前应用**没有声明 INTERNET 权限**，所以实际上传会失败——但组件照样会被唤醒、照样会尝试。
这是一个"今天靠缺权限兜住、明天某个依赖顺手加了 INTERNET 就漏"的结构性风险。

要消除它，必须在清单里显式移除三个组件（`tools:node="remove"`），并补一条回归验证。
这是可做的，但属于"为了一个用不上的功能，主动引入并手动拆掉一条遥测链路"——投入产出比不成立。

---

## 四、其余约束（不是否决项，但决定了设计形态）

| 约束 | 内容 |
|---|---|
| 版本稳定性 | beta，明确"不受 SLA 或弃用政策约束，可能发生不向后兼容的变更" |
| 前台限定 | 仅当应用是顶层前台应用时可推理，含前台服务也返回 `BACKGROUND_USE_BLOCKED` |
| 配额 | AICore 按应用限流，短时超限 `BUSY`，日超额 `PER_APP_BATTERY_USE_QUOTA_EXCEEDED` |
| 输入上限 | 必须 < 4000 token（约 3000 英文词） |
| 引导锁 | 解锁 bootloader 的设备一律不支持 |
| 磁盘 | 模型下载占空间，空间不足直接 `NOT_ENOUGH_DISK_SPACE` |
| 结构化输出 | 需要额外配 KSP，且**运行时要先探测** `isStructuredOutputFeatureAvailable()` |

---

## 五、如果仍要做：唯一可接受的形态

先明确一点：**"让模型直接决定搜索结果"是不可接受的形态**。理由不是模型差，而是它的失败模式
在音乐播放器里表现为"搜不到歌"，用户无从理解、无法绕过。一个本地播放器最核心的能力不能建立在
可能返回 `BUSY`、可能语言效果未知、可能机型不支持的外部服务上。

可接受的形态是**把模型降级成"查询解析器"，而不是决策者**：

```
用户输入 "去年加的 慢歌"
        ↓  Prompt API（结构化输出，maxOutputTokens 很小）
{ "year": 2025, "tempo": "slow" }        ← 只允许产出受控枚举内的字段
        ↓  纯本地、确定性的过滤管线（现有曲库数据）
[Track, Track, Track]
        ↓  任何一步失败 / 超时 / 不支持
回落到现有子串匹配，用户无感
```

要点：

1. 模型只输出**受控词表内的标签**，不输出歌名、不输出排序；
2. 输出用 `GenerateTypedContentRequest` + `@Generable`，把"胡说"压到结构层；
3. 本地过滤是纯函数，可单测、可离线、可复现——AI 只是输入适配层；
4. 超时（建议 800ms）就回落，绝不阻塞搜索框；
5. 入口只在搜索框内，Nano 不可用时**完全没有 UI 痕迹**（不显示灰按钮、不显示"升级提示"）。

前置条件（缺一不可）：

- 一台 nano-v2/v3/v4 任一版本的真机，且是**国内行货**，用来确认真实可用性；
- 在这台机器上跑一轮中文提示词评测：至少 50 条真实中文查询，统计标签解析准确率；
- 清单里补 `tools:node="remove"` 三件套 + 一条"`dumpsys` 里无 datatransport 组件"的回归验证。

在这三条满足之前动手，产出的是一段**无法验证、也无法向用户承诺**的代码。

---

## 六、本批次未改代码，原因

1. 手上无 adb 设备，更无 Nano 设备——任何实现都无法做红绿验证，与项目"不接受空断言"的标准冲突；
2. 阻塞 1 与阻塞 3 都需要产品层面取舍，不是技术选型能单方面决定的；
3. 阻塞 3 一旦引入，会直接改动 README:3 对外声明的隐私口径。

建议：把这条从"待做"移出，改为**观察项**——等 Google 公布 Prompt API 的语言验证范围，
或 AICore 随 AOSP 下沉（目前无迹象）。同一轮里更值得做的是批次 F 遗留的 ReplayGain：
它不依赖任何外部服务，收益确定，只差一个设置页入口。
