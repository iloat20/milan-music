package com.milan.music.play

/**
 * 换曲到真正出声的耗时，单位毫秒。
 *
 * 这是「要不要引入分级预加载」的前置证据。没有这个数字，任何 preload 的引入都只是
 * 猜测；本地文件的理论下限是几毫秒读盘加 10–50 ms 解码器初始化，实测只要贴着这个
 * 量级，就没有第二个 player 实例的位置。
 */
internal fun trackStartLatency(markAtMs: Long, nowMs: Long): Long? {
    val elapsed = nowMs - markAtMs
    if (elapsed < 0L) return null
    if (elapsed > MAX_TRACK_START_MS) return null
    return elapsed
}

/**
 * Samples "playback requested or buffering began" → "sound is actually running".
 *
 * The old probe marked the media-item transition itself. A paused snapshot also
 * prepares a transition, so a user who pressed play a few seconds later was measured
 * as having waited that whole time. This state machine starts at the play request
 * (or at a buffering transition that is already allowed to play), clears on pause,
 * and reports a seamless transition immediately as zero.
 */
internal class TrackStartProbe {

    private var markAtMs: Long? = null

    /** Returns 0 for a seamless transition, otherwise arms a buffering mark. */
    fun onTransition(nowMs: Long, playWhenReady: Boolean, isPlaying: Boolean): Long? {
        if (isPlaying) {
            markAtMs = null
            return 0L
        }
        markAtMs = if (playWhenReady) nowMs else null
        return null
    }

    fun onPlayWhenReadyChanged(playWhenReady: Boolean, nowMs: Long) {
        markAtMs = if (playWhenReady) nowMs else null
    }

    fun onIsPlayingChanged(nowMs: Long): Long? {
        val mark = markAtMs ?: return null
        markAtMs = null
        return trackStartLatency(mark, nowMs)
    }
}

/**
 * 超过这个值就不是"启动延迟"，而是别的东西。
 *
 * 典型场景：暂停状态下拖动进度条（不会出声，标记一直挂着），十分钟后用户点播放。
 * 那 60 万毫秒的量级进了统计只会把均值彻底带偏，所以宁可丢掉这一笔。
 */
private const val MAX_TRACK_START_MS = 60_000L
