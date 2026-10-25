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
val appVersionCode = 68

plugins {
    alias(libs.plugins.android.application)
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
        }
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
}
