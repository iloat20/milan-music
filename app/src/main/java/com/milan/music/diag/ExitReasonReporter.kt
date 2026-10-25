package com.milan.music.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 把上一次进程的死因写进 logcat。
 *
 * Android 17 的 per-app 内存上限杀进程**不留堆栈**：用户说"音乐突然没了"，崩溃上报
 * 里什么都没有。唯一可查的证据是 `ApplicationExitInfo`——下次启动时读一遍，把
 * `MemoryLimiter:AnonSwap` 这类死因连同 rss/pss 记下来。
 *
 * 读取放在 IO 线程：`getHistoricalProcessExitReasons` 是一次 Binder 往返，启动路径上
 * 不该为诊断付这份钱。
 */
internal object ExitReasonReporter {

    private const val TAG = "ExitReason"
    private const val PREFS = "milan-exit-report"
    private const val KEY_LAST_REPORTED = "lastReportedAtMs"

    /**
     * 每次启动最多回看 10 条。
     *
     * 系统给的上限是 16；10 条足够覆盖"用户昨天放了半小时音乐、今天打开说没声"的
     * 场景，又不至于把整段历史重新打一遍。
     */
    private const val MAX_ENTRIES = 10

    /**
     * 启动时调用一次。
     *
     * 已经报过的条目靠 SharedPreferences 里的时间戳去重：否则每启动一次就把上周的
     * 同一次 kill 再打一遍，日志里永远分不清新旧。
     */
    fun reportAsync(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val appContext = context.applicationContext
        // 版本守卫在构造函数外面这一层，读取器整体标 @RequiresApi——lint 只有在
        // 调用点能看见守卫时才认得这是安全的。
        val source = ExitSource(appContext)
        // 一次性作用域：协程跑完即结束，没有需要取消的长生命周期任务。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { source.read() }
                .onSuccess { report(appContext, it) }
                .onFailure { Log.w(TAG, "exit history unavailable", it) }
        }
    }

    private fun report(context: Context, exits: List<ProcessExit>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastReported = prefs.getLong(KEY_LAST_REPORTED, 0L)

        val fresh = exits
            .filter { it.timestampMs > lastReported }
            .filter { isReportable(it.cause) }
            .sortedBy { it.timestampMs }
        if (fresh.isEmpty()) return

        fresh.forEach { Log.w(TAG, it.describe()) }
        // KTX 的 edit {} 默认 commit=false，等价于 apply()：这里不需要等待落盘，
        // 而且本来就在 IO 线程上。
        prefs.edit { putLong(KEY_LAST_REPORTED, fresh.last().timestampMs) }
    }

    /** 只在 API 30+ 构造，见 [reportAsync] 的版本守卫。 */
    @RequiresApi(Build.VERSION_CODES.R)
    private class ExitSource(private val context: Context) {

        fun read(): List<ProcessExit> {
            val manager = context.getSystemService(ActivityManager::class.java)
                ?: return emptyList()
            // pid = 0 表示"本包所有进程"；maxNum 必须 > 0。
            return manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_ENTRIES)
                .map { info ->
                    ProcessExit(
                        cause = classifyProcessExit(info.reason, info.description),
                        reason = info.reason,
                        description = info.description,
                        timestampMs = info.timestamp,
                        rssKb = info.rss,
                        pssKb = info.pss,
                    )
                }
        }
    }
}
