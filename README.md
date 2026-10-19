# 米兰音乐 · Milan Music

Android 本地音乐播放器。直接读取设备上的音频文件，**不联网、无广告、无账号、不采集任何数据**。

Jetpack Compose + Media3 全量重写，暖白底色配淡金描边的水墨玻璃质感。

**[下载最新版本](https://github.com/iloat20/milan-music/releases/latest)** · 签名的 APK 与 AAB 发布在 Releases，不随 git 仓库分发。

<img src="screenshots/library.jpg" width="320" alt="曲库界面">

## 功能

| 模块 | 说明 |
|---|---|
| 曲库 | 歌曲 / 专辑 / 艺人 / 文件夹 四个视图；按标题、艺人、专辑、添加时间、时长排序 |
| 搜索 | 跨歌曲、艺人、专辑的即时过滤 |
| 收藏 | 单曲喜欢，独立「收藏」页 |
| 歌单 | 自建歌单 |
| 播放页 | 全屏覆盖层，下滑手势或返回键关闭；进度拖拽、上一首 / 下一首、播放暂停 |
| 后台播放 | Media3 `MediaSessionService`，通知栏 / 锁屏 / 蓝牙控制，封面同步 |
| 桌面小组件 | 2×2 卡片，显示封面与曲名，可直接播放暂停；封面圆角在 Android 8–11 上同样生效 |
| 主题图标 | Android 13+ 自适应图标的 monochrome 层 |

## 技术栈

| 层 | 选型 |
|---|---|
| UI | Jetpack Compose（BOM 2026.09.00）、Material 3 |
| 播放 | Media3 1.11.1（ExoPlayer + MediaSession，音频硬解卸载 + gapless） |
| 模糊 / 玻璃 | Haze 2.0.1 |
| 持久化 | DataStore Preferences + kotlinx.serialization |
| 音乐库 | MediaStore 查询 + 磁盘缓存 |
| 构建 | AGP 9.1.1、Kotlin 2.4.20、Gradle 9.3.1、JDK 17 |
| 平台 | minSdk 26 / targetSdk 37 / compileSdk 37 |

26 个 Kotlin 文件，约 3.7k 行。

## 构建

前置：JDK 17、Android SDK（platform 37）、`ANDROID_HOME` 或 `local.properties` 中的 `sdk.dir`。

```bash
git clone git@github.com:iloat20/milan-music.git
cd milan-music

# 指向本机 SDK（不要提交这个文件）
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（包名 `com.milan.music.debug`）。

### Release 签名

release 构建读取 `~/.android/milanmusic-release.keystore`，口令来自 `local.properties`（已被 `.gitignore` 排除）：

```properties
KEYSTORE_STORE_PASSWORD=...
KEYSTORE_KEY_PASSWORD=...
```

缺失时 debug 构建不受影响，只有 release 签名会报 keystore 错误。

```bash
./gradlew :app:assembleRelease :app:bundleRelease
```

## 项目结构

```
app/src/main/java/com/milan/music/
├── MainActivity.kt          单 Activity 宿主，权限与 onStop 生命周期
├── MilanMusicApp.kt         Application，装配 AppContainer
├── di/AppContainer.kt       手工依赖注入
├── data/
│   ├── media/               MediaStore 扫描、封面读取、磁盘缓存
│   ├── model/               Track 等数据模型
│   ├── prefs/               DataStore 设置（排序方式）
│   └── userdata/            收藏与歌单的本地存储
├── play/                    PlayerController（MediaController 客户端）
│                            PlaybackService、ArtworkBitmapLoader
├── ui/
│   ├── library/             曲库首页、各视图列表、ViewModel
│   ├── player/              播放页
│   ├── components/          封面、列表行、迷你播放器
│   ├── glass/               Haze 玻璃材质封装
│   └── theme/               配色、字体、主题
└── widget/                  桌面小组件
```

播放页是**覆盖层**而非导航目的地：`HomeScreen` 始终保留在 Compose 树中，下滑关闭时能直接露出下层页面（Navigation-Compose 的 pop 过渡做不到这一点）。

## 已知问题

- **暂停态从最近任务划掉卡片后服务仍存活**：有队列时主动保留绑定以便重开续播，属产品取舍。
- **Compose 内约 20 处中文硬编码**：暂无多语言计划。
- **无自动化测试**：`src/test` 与 `src/androidTest` 均为空。行为验证目前依赖真机冒烟。

## 审查与迭代记录

`review/` 保留了完整的工程审查与逐批执行记录，包括每一项改动的原因、验证方式与未验证清单：

| 文件 | 内容 |
|---|---|
| `2026-10-18-app-review.md` | 首次全量审查（P0–P3 共 60+ 项） |
| `2026-10-19-batch-a-fixes.md` ~ `batch-e-lint-zero.md` | 五批修复与 lint 清零的执行记录 |
| `2026-10-19-batch-f-tech-refresh.md` | 音频卸载启用、小组件圆角跨版本修复，及其实测待验证清单 |
| `2026-10-19-batch-g-梯队二.md` | 预测式返回落地；音效链与共享元素过渡的待决策项 |
| `2026-10-19-batch-h-genai-prompt-评估.md` | ML Kit GenAI Prompt API 可行性评估：三条否决项与"仅作查询解析器"的备选形态 |
| `2026-10-19-release-1.0.46.md` | 1.0.46 发布说明：offload / 小组件圆角 / 预测式返回三项合并发布 |
| `2026-10-19-release-1.0.45.md` | 1.0.45 发布说明：搜索框空态文案移除与图标调整 |
| `2026-10-19-code-review-1.0.44.md` | 1.0.44 合并前代码审查 |
| `2026-10-19-release-1.0.44.md` | 1.0.44 发布说明与产品取舍 |

## 说明

仓库未附 License，保留所有权利。视频/音频文件不随仓库分发。
