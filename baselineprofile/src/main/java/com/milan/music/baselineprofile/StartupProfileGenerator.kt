package com.milan.music.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 采集应用自己启动路径的 baseline profile。
 *
 * 为什么需要这个：包里的 `assets/dexopt/baseline.prof` 现在全部来自依赖（Compose、
 * media3、datastore 各自带一份），我们自己那条"Application.onCreate → 曲库恢复 →
 * 首页首帧"完全没被预编译，冷启动要现场 JIT。这份测试就是补上那一段。
 *
 * 执行：`./gradlew :app:generateReleaseBaselineProfile`（需要一台真机或能跑的模拟器）。
 * 采集结果由插件写回 `app/src/release/generated/baselineProfiles/`，随下次构建进包。
 *
 * 这里刻意只采冷启动、不采播放：播放路径依赖媒体文件与 MediaSession，在采集设备上
 * 既不稳定，也会把 profile 预算浪费在只在播放时才热的代码上。
 */
@RunWith(AndroidJUnit4::class)
class StartupProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun coldStartup() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // 没授予音频权限时应用停在权限门，采到的就只是"权限页首帧"，曲库恢复那段
        // 一行都采不到。pm grant 是幂等的；API < 33 上 READ_MEDIA_AUDIO 不存在、
        // 旧系统上 READ_EXTERNAL_STORAGE 才是那条，两条都试，失败无副作用。
        device.executeShellCommand("pm grant $PACKAGE android.permission.READ_MEDIA_AUDIO")
        device.executeShellCommand("pm grant $PACKAGE android.permission.READ_EXTERNAL_STORAGE")

        rule.collect(PACKAGE) {
            // 从桌面起步，避免"应用已在栈顶"把冷启动变成热启动。
            pressHome()
            startActivityAndWait()
        }
    }

    private companion object {
        const val PACKAGE = "com.milan.music"
    }
}
