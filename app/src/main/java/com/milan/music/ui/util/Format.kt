package com.milan.music.ui.util

import java.util.Locale

/**
 * 播放时间标签（`3:45`、`1:02:03`）。
 *
 * **必须固定 [Locale.ROOT]**。这里输出的是量纲，不是文案：`"%d:%02d".format(...)`
 * 走的是 `Locale.getDefault()`，在 `ar-EG`、`fa-IR`、`bn-IN` 这类把本地数字设为默认
 * 数字系统的 locale 下会变成 `٣:٤٥`。后果不只是"看不懂"——阿拉伯-印度数字与拉丁数字
 * 的字符宽度不同，进度条两端的两枚标签会随着秒数跳动**逐秒左右微移**，而 `ar` 下的
 * 冒号位置也会跟着翻。`FormatDurationTest` 用 `ar-EG` 钉住这一条。
 */
fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "--:--"
    val totalSeconds = durationMs / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}
