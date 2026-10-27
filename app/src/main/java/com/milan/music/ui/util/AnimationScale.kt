package com.milan.music.ui.util

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 系统的动画时长倍率，**在运行期间跟随设置变化**。
 *
 * 这个值原先在组合期读一次就够了，前提是"改设置会重建界面"——它不成立：本工程的
 * `MainActivity` 声明了 `configChanges` 接管方向与主题变化，用户在设置里关掉"移除动画"
 * 再回来，界面不重建，读到的仍是旧值。于是无障碍开关的表现是"下次冷启动才生效"。
 *
 * 唯一可靠的信号是设置项本身的变更：`ANIMATOR_DURATION_SCALE` 是 `Settings.Global`
 * 里的一个普通键，`SettingsProvider` 在它被改写时会通知观察者，不需要任何权限。
 *
 * **这是全工程唯一允许读该键的地方**——另一处再写 `remember { getFloat(...) }`，
 * 就会退回"只在组合期生效"的旧行为，而两处读同一个键不会有任何编译或运行期提示。
 * `AnimationScaleGateTest` 守这一条。
 *
 * 读不到时按 1 处理：宁可多动一帧，也不要因为一次读取失败让"正在播放"变成三根
 * 不动的短棍 —— 那会被读成"卡住了"。
 */
@Composable
internal fun rememberAnimationScale(): Float {
    val context = LocalContext.current
    val scale = remember(context) { mutableFloatStateOf(readAnimationScale(context)) }

    DisposableEffect(context) {
        val resolver = context.contentResolver
        // 回调落在主线程：`scale` 是 Compose 状态，只能在那里写。
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scale.floatValue = readAnimationScale(context)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose {
            // 注销一个未注册成功的观察者会抛；而这里抛出去没有任何人接得住。
            runCatching { resolver.unregisterContentObserver(observer) }
        }
    }

    return scale.floatValue
}

/**
 * 读一次当前倍率。
 *
 * `runCatching` 不加会有一条真实崩溃路径：观察者的 `onChange` 跑在主线程，那里抛出的
 * 异常直达 `Thread.uncaughtExceptionHandler`，而设置读取本身在个别定制系统上会抛。
 * 默认值 1f 与 `Settings.Global.getFloat` 的 `def` 参数语义一致。
 */
private fun readAnimationScale(context: Context): Float = runCatching {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
}.getOrDefault(1f)
