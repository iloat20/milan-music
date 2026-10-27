// 应用级 baseline profile 的采集端。
//
// 这个模块**不产出应用**：`com.android.test` 让它打出一个 instrument `:app` 的测试 APK，
// `BaselineProfileRule` 在里面反复冷启动目标应用并记录真实执行到的方法，产物由
// `androidx.baselineprofile` 插件合并进 `:app` 的 release 包（assets/dexopt/baseline.prof）。
//
// 它不进发布产物：`dist/` 里那份 APK 的结构不因这个模块改变，只是 dexopt 目录里多出
// 我们自己启动路径的规则（依赖自带的那些规则本来就已经在里面了）。
//
// 采集必须有一台真机或可跑的模拟器 —— `./gradlew :app:generateReleaseBaselineProfile`。
// 没有设备时这条命令会明确失败，不会静默产出一个空 profile。
plugins {
    alias(libs.plugins.android.test)
    // 这个模块是 profile 的「producer」：应用插件后才出现下面的 baselineProfile{} 扩展
    // （useConnectedDevices 属于 producer，不属于 :app 那侧的 consumer 扩展）。
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.milan.music.baselineprofile"
    compileSdk = 37

    defaultConfig {
        // 与 :app 的 minSdk 一致：采集设备必须能装目标应用，否则 instrument 起不来。
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 指向被采集的应用模块。没有它，这个 test 模块不知道自己该 instrument 谁。
    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.espresso.core)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

// producer 侧（这个模块）才有采集设备的选择；consumer 侧（:app）没有这个属性。
baselineProfile {
    // 默认 false 会先去找 Gradle Managed Devices —— 那要下载系统镜像、要硬件虚拟化。
    // 本工程的采集对象是插着的真机，显式指定，免得在没有镜像的机器上先失败一轮。
    useConnectedDevices = true
    // 模拟器上采出来的 profile 依旧可用（方法覆盖与设备无关），只是耗时数据不可信；
    // 这台机器 BIOS 里虚拟化是关的，模拟器跑不起来，所以这里不做额外拦截。
    skipBenchmarksOnEmulator = false
}
