package com.milan.music.data.media

import android.graphics.Bitmap
import android.util.Log

private const val TAG = "BitmapPixels"

/**
 * 返回一张像素可读的位图，读不到就返回 `null`。
 *
 * `Bitmap.Config.HARDWARE` 的像素留在显存里：`getPixels()` 抛 `IllegalStateException`，
 * `Canvas.drawBitmap` 在软件画布上抛 `IllegalArgumentException: Software rendering
 * doesn't support hardware bitmaps`。而 `ContentResolver.loadThumbnail` 完全可能返回
 * 这样一张，类型系统不会告诉你。所以每个要读像素、或者要画到软件画布上的调用点，
 * 都得先换一张。
 *
 * **返回 `null` 而不是原图**，是这个函数存在的全部理由。此前四个调用点各写各的：
 * 一处返回原图、再由调用方二次检查 config，一处返回 `null`，一处内联 copy，还有一处
 * 根本没防 —— 同一个失败在不同地方的后果不同，其中一种是把 HARDWARE 位图喂给
 * `getPixels()`，异常发生在 `NonCancellable` 块里，一路到 recomposer 把进程带走。
 *
 * `null` 把降级方式的决定权交回各调用点（跳过修剪 / 不画倒影 / 保留上一套配色 /
 * 显示占位图），它们的正解本来就各不相同。
 *
 * 本身已是软件位图时直接返回自己：`copy()` 会多一份整图内存，而这条路每张专辑都要走。
 */
internal fun Bitmap.softwareCopy(): Bitmap? {
    if (config != Bitmap.Config.HARDWARE) return this
    // 拷贝在内存压力下会失败（返回 null）。这里只记一笔：调用方会各自降级，但
    // "没有封面"和"没有内存"在日志里必须能区分开。
    return copy(Bitmap.Config.ARGB_8888, false).also {
        if (it == null) Log.w(TAG, "software copy of a HARDWARE bitmap failed")
    }
}
