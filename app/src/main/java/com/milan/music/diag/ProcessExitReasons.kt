package com.milan.music.diag

/**
 * 进程死因分类。
 *
 * 判据只有官方给的那一条：**原因码 + 描述串**。`REASON_OTHER` 单独出现说明不了任何
 * 事——正常退出、包更新、被系统清理都在这个码里；只有描述里带
 * `MemoryLimiter:AnonSwap` 才是 Android 17 的 per-app 内存上限杀的进程。这也是
 * "用户说音乐突然没了、又没有崩溃栈"唯一可查的证据。
 */
internal enum class ExitCause(val token: String) {
    MEMORY_LIMITER("memory-limiter"),
    LOW_MEMORY("low-memory"),
    CRASH("crash"),
    ANR("anr"),
    OTHER("other"),
}

internal data class ProcessExit(
    val cause: ExitCause,
    val reason: Int,
    val description: String?,
    val timestampMs: Long,
    val rssKb: Long,
    val pssKb: Long,
) {
    /** 单行诊断；`rss` / `pss` 换成 MB，方便和 `dumpsys meminfo` 对照。 */
    fun describe(): String = buildString {
        append("exit cause=").append(cause.token)
        append(" reason=").append(reason)
        append(" atMs=").append(timestampMs)
        append(" rss=").append(rssKb / 1024).append("MB")
        append(" pss=").append(pssKb / 1024).append("MB")
        append(" desc=").append(description ?: "-")
    }
}

/**
 * 是否值得在启动时读出来。
 *
 * `EXIT_SELF` / `USER_REQUESTED` / 包更新都是正常退出，报出来只会淹没上面那几类；
 * 拉取本身还要多花一次 Binder 往返。
 */
internal fun isReportable(cause: ExitCause): Boolean = cause != ExitCause.OTHER

internal fun classifyProcessExit(reason: Int, description: String?): ExitCause = when {
    reason == REASON_OTHER && description?.contains(MEMORY_LIMITER_MARK) == true ->
        ExitCause.MEMORY_LIMITER

    reason == REASON_LOW_MEMORY -> ExitCause.LOW_MEMORY
    reason == REASON_CRASH || reason == REASON_CRASH_NATIVE -> ExitCause.CRASH
    reason == REASON_ANR -> ExitCause.ANR
    else -> ExitCause.OTHER
}

/** 官方指纹：`ApplicationExitInfo.getDescription()` 里出现这个串。 */
private const val MEMORY_LIMITER_MARK = "MemoryLimiter:AnonSwap"

/*
 * 原因码取自 `ApplicationExitInfo`（API 30）。这里写常量值而不是引用它，是因为本项目
 * minSdk 是 26，引用 API 30 的字段会被 lint 的 InlinedApi 判为 warning，而门禁要求
 * 零 warning。数值是公开 API 的固定值，测试用同一批字面量钉住契约。
 */
private const val REASON_LOW_MEMORY = 3
private const val REASON_CRASH = 4
private const val REASON_CRASH_NATIVE = 5
private const val REASON_ANR = 6
private const val REASON_OTHER = 13
