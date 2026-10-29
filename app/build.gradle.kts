import java.util.Properties

// Keystore credentials live in local.properties (machine-local, never committed):
//   KEYSTORE_STORE_PASSWORD=...
//   KEYSTORE_KEY_PASSWORD=...
// A machine without those entries builds debug fine; release signing fails with a
// keystore error until the two lines are added.
//
// 用 `providers.fileContents` 而不是 `File.inputStream()`：后者是在配置期裸读文件，
// 配置缓存看不到这层依赖，改了 local.properties 也可能复用旧条目（最坏情况是把空口令
// 缓存下来，表现成"签名突然失败"）。FileContents 是受跟踪的值源。
val keystoreProps: Provider<Properties> = providers.fileContents(
    rootProject.layout.projectDirectory.file("local.properties"),
).asText.orElse("").map { text ->
    Properties().apply { text.reader().use { load(it) } }
}

/** 唯一版本号来源；版本名由它派生（见 `defaultConfig`）。 */
val appVersionCode = 89

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.milan.music"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.milan.music"
        minSdk = 26
        targetSdk = 37
        // 只维护 versionCode，版本名由它派生。两者手改必然错位（P2-6 当时就是
        // vc 61 配 1.0.60）；1.0.x 期间 patch 恒等于 code − 1，升到 1.1 再改成显式两者。
        // DSL 里的 versionCode 是 Int?，所以派生要从这个本地值算。
        versionCode = appVersionCode
        versionName = "1.0.${appVersionCode - 1}"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/milanmusic-release.keystore")
            storePassword = keystoreProps.get().getProperty("KEYSTORE_STORE_PASSWORD", "")
            keyAlias = "milanmusic"
            keyPassword = keystoreProps.get().getProperty("KEYSTORE_KEY_PASSWORD", "")
            // v3 is what allows key rotation if the keystore is ever replaced; v4
            // signs the .idsig side file that incremental installs use.
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            // R8 strips the unused bulk of material-icons-extended and unreferenced
            // code; without it the APK carries the entire extended icon set.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")

            // arm64-v8a only. The app has no native code of its own: the two .so files
            // in the package come from androidx (graphics-path, datastore shared counter)
            // and ship per ABI. Filtering to 64-bit ARM drops the 32-bit and x86 copies —
            // ~50 KB — and makes the shipped artifact's ABI set explicit instead of
            // inherited from whichever transitive dependency added a native lib next.
            //
            // Scoped to release on purpose: `debug` keeps all four ABIs so x86_64
            // emulators still install. The trade-off is real — release no longer installs
            // on 32-bit-only devices, which is acceptable above minSdk 26 in 2026.
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 曲库列表末尾那行版本戳要读 BuildConfig.VERSION_NAME / VERSION_CODE / DEBUG。
        // 有了它，"装错包/改动没生效"从两轮排查变成滚到底看一眼。
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // 依赖会把各自的 LICENSE.txt 摊进 META-INF，本工程命中的是 8 份逐字节相同的
            // Apache-2.0 文本（lifecycle-common ×3、collection ×2、datastore ×2、annotation ×1），
            // 压缩后合计 29,244 B。同一份文本重复 8 遍不构成"附许可证"，也不再提供任何信息；
            // 只在二进制里去掉，仓库侧与依赖声明不变。
            excludes += "/META-INF/**/LICENSE.txt"
        }
    }

    // 体积（第 81 批）：AndroidX 只为 85 个 locale 出串，而本应用只有中文界面。
    // arsc 从 248,272 B 降到 33,184 B，dex 与其余条目逐字节不变。
    //
    // 这里必须是**准确 qualifier**。包内只有 zh-rCN / zh-rHK / zh-rTW 与 en-rCA/GB/XC/IN/AU，
    // 没有裸 values-zh、values-en：写 "zh"/"en" 会静默匹配到 0 个 locale，中文串全没了
    // （`apkanalyzer resources configs --type string` 只剩 default），而构建、体积、门禁
    // 全都正常。被保住的 34 条中文串里有 media3_controls_play/pause_description、
    // seek_to_next/previous、selected/not_selected、state_on/off —— TalkBack 的播报文案。
    //
    // 只裁 values-* 语言配置：density 与 night 的 drawable 配置实测一个没少。
    androidResources {
        localeFilters += listOf("zh-rCN", "zh-rHK", "zh-rTW")
    }

    lint {
        // arm64-v8a is the only shipped ABI, and that is a deliberate, audited decision:
        // the app has no native code of its own, the release artifact is verified
        // against a single ABI, and ChromeOS is not a target. `ChromeOsAbiSupport`
        // cannot do anything but restate the trade-off this module already documents in
        // the ndk block, so it is switched off by id rather than left as noise that
        // hides a real warning later.
        disable += "ChromeOsAbiSupport"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

/**
 * P1-5：Compose 稳定性报告。
 *
 * 只写进构建目录，不改变产物。报告是给"要不要加 `stabilityConfigurationFile`"提供
 * 证据的：只有报告里确实标成 unstable、而代码又保证不可变的类才值得加，凭感觉写
 * `@Immutable` 会让重组跳过本该发生的更新。
 */
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}

/**
 * 应用级 baseline profile（第 86 批）。
 *
 * 包里的 `assets/dexopt/baseline.prof` 在此之前全部来自依赖 —— Compose、media3、
 * datastore 各带一份，合并后只覆盖它们自己的类。我们自己的启动路径（`MilanMusicApp`
 * → `MainActivity` → 曲库恢复 → 首页首帧）没有一条规则，冷启动那段只能现场 JIT。
 *
 * 采集端是 `:baselineprofile`（`com.android.test` 模块），跑：
 *   ./gradlew :app:generateReleaseBaselineProfile
 * 结果写回 `app/src/release/generated/baselineProfiles/`，随下一次 `assembleRelease` 进包。
 * 需要连一台真机（或能跑的模拟器）；没有设备时命令会直接失败，不会产出一个空 profile。
 */
baselineProfile {
    // 采集结果进源码目录而不是只在 build/：一次性采集、长期复用，否则每次发布都要重采。
    saveInSrc = true
    // 构建时不自动采集。采一次要连设备跑完冷启动循环，把它挂在 assembleRelease 上会让
    // 每个没有设备的构建（CI、换机器）直接失败。采集是显式动作：
    //   ./gradlew :app:generateReleaseBaselineProfile
    automaticGenerationDuringBuild = false
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    // haze-glass 自带深度模糊与折射，haze-blur 已经没有调用点了 —— 留着会是一条
    // 永远不会被走到、却仍要在升级时一起维护的模糊路径。
    implementation(libs.haze)
    implementation(libs.haze.glass)

    testImplementation(libs.junit)
    // 只为 MemoCache 的并发时序：合并同 key 请求、以及"生产者被取消后等待方重读缓存"
    // 这两条只有在能控制调度器时才能可靠复现。不是 mock 框架，也不进生产包。
    testImplementation(libs.kotlinx.coroutines.test)

    // 采集端模块；`baselineProfile` 配置由 androidx.baselineprofile 插件创建。
    // 它在 release 变体里只参与 generateBaselineProfile 任务，不产生运行时依赖。
    baselineProfile(project(":baselineprofile"))
}
