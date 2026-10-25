package com.milan.music.diag

import android.util.Log
import androidx.media3.common.C

/**
 * 音频管线的两条观测线：欠载与真实输出格式。
 *
 * 播放器里所有缓冲参数（`minBuffer 15 s`、`ByteCappedLoadControl 4 MB`、起播阈值
 * `120 ms`）目前都是推算值。没有欠载计数，"缓冲够不够"只能用"听起来没卡"来回答，
 * 而那种回答既不能证伪也不能调参。
 *
 * `setEnableAudioFloatOutput(true)` 同理：它是请求，不是结果。只有渲染器真正拿到的
 * 输入格式能说明 24-bit 源有没有被截成 16-bit。
 *
 * 字段名是契约，过滤用 `adb logcat -s AudioPipeline`。
 */
internal object AudioPipeline {

    private const val TAG = "AudioPipeline"

    fun recordUnderrun(bufferSizeBytes: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
        Log.w(TAG, underrunLine(bufferSizeBytes, bufferSizeMs, elapsedSinceLastFeedMs))
    }

    fun recordInput(sampleRate: Int, channelCount: Int, pcmEncoding: Int) {
        Log.i(TAG, audioInputLine(sampleRate, channelCount, pcmEncoding))
    }
}

internal fun underrunLine(
    bufferSizeBytes: Int,
    bufferSizeMs: Long,
    elapsedSinceLastFeedMs: Long,
): String = "audio underrun buffer=$bufferSizeBytes bufferMs=$bufferSizeMs " +
    "sinceFeedMs=$elapsedSinceLastFeedMs"

internal fun audioInputLine(
    sampleRate: Int,
    channelCount: Int,
    pcmEncoding: Int,
): String = "audio input rate=$sampleRate ch=$channelCount pcm=${pcmEncodingName(pcmEncoding)}"

/**
 * Names the encodings the renderer can hand to `AudioTrack`.
 *
 * `float` is the one that matters for quality: it is the only value that proves
 * `setEnableAudioFloatOutput(true)` was honoured instead of silently falling back.
 */
internal fun pcmEncodingName(pcmEncoding: Int): String = when (pcmEncoding) {
    C.ENCODING_PCM_8BIT -> "u8"
    C.ENCODING_PCM_16BIT -> "s16"
    C.ENCODING_PCM_24BIT -> "s24"
    C.ENCODING_PCM_32BIT -> "s32"
    C.ENCODING_PCM_FLOAT -> "float"
    else -> "unknown($pcmEncoding)"
}
