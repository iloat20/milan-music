package com.milan.music.diag

import androidx.annotation.OptIn
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.common.util.UnstableApi

/**
 * 播放失败的诊断分类。
 *
 * 只写日志，不进 UI 状态：播放页没有错误视图，把失败塞进 `PlayerUiState` 只会多出
 * 一个永远不显示的分支。分类的价值在于**区分卡死**——那是本项目唯一必须显式声明
 * "不许自动重试"的类别。
 */
internal enum class PlaybackFailureKind(val token: String) {
    /** Media3 1.9+ 的 `StuckPlayerException`：播放器自己在缓冲/播放/抑制状态停住了。 */
    STUCK("stuck"),

    /** 解码器与输出链：init、query、解码失败、AudioTrack 初始化失败。 */
    DECODER("decoder"),

    /** 读不到数据：文件被删、权限被撤、存储卸载。 */
    UNREADABLE("unreadable"),

    /** 容器或编码不被支持，通常不是坏文件而是格式本身。 */
    UNSUPPORTED("unsupported"),

    OTHER("other"),
}

/**
 * [StuckPlayerException] 的 `type` 与 `timeoutMs`。
 *
 * 两个字段直接来自 media3：`stuckType` 是 0..4 的五种卡死形态（缓冲无加载、缓冲无
 * 进展、播放无进展、超出声明时长未结束、被抑制），`timeoutMs` 是判定超时。有了它们，
 * "卡死"这条日志才能落到具体形态，而不是一句笼统的 unexpected。
 */
internal data class StuckDetail(
    val type: Int,
    val timeoutMs: Int,
)

internal data class PlaybackFailure(
    val kind: PlaybackFailureKind,
    val errorCode: String,
    val stuck: StuckDetail? = null,
) {
    /**
     * 是否允许自动重试。
     *
     * 卡死明确不允许：Media3 的判定本身就意味着"同一个解码器、同一个文件已经
     * 10 s / 10 min 没有任何推进"，用同样的输入再来一次几乎必然复现；而重试期间
     * UI 只显示暂停，用户点一次播放可能就被锁进一个看不见的重试循环。其余类别重试
     * 是安全的——换一首歌就绕开了，失败也不会掩盖成"没反应"。
     */
    val autoRetryAllowed: Boolean get() = kind != PlaybackFailureKind.STUCK

    /** 单行诊断，字段顺序固定，便于 `adb logcat | grep "kind=stuck"`。 */
    fun describe(): String = buildString {
        append("kind=").append(kind.token)
        append(" code=").append(errorCode)
        stuck?.let {
            append(" stuckType=").append(it.type)
            append(" stuckTimeoutMs=").append(it.timeoutMs)
        }
    }
}

/**
 * 把 [androidx.media3.common.PlaybackException.getErrorCodeName] 归类。
 *
 * [stuck] 优先于 code：media3 把 `StuckPlayerException` 包成
 * `ERROR_CODE_UNEXPECTED` 的 `ExoPlaybackException`，只看 code 只能得到
 * "unexpected"，而那正是最需要被认出来的一类。
 */
internal fun classifyPlaybackFailure(
    errorCodeName: String,
    stuck: StuckDetail?,
): PlaybackFailure {
    if (stuck != null) return PlaybackFailure(PlaybackFailureKind.STUCK, errorCodeName, stuck)

    val kind = when (errorCodeName) {
        "ERROR_CODE_DECODER_INIT_FAILED",
        "ERROR_CODE_DECODER_QUERY_FAILED",
        "ERROR_CODE_DECODING_FAILED",
        "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES",
        "ERROR_CODE_AUDIO_TRACK_INIT_FAILED",
        -> PlaybackFailureKind.DECODER

        "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED",
        "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",
        "ERROR_CODE_PARSING_CONTAINER_MALFORMED",
        "ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED",
        "ERROR_CODE_PARSING_MANIFEST_MALFORMED",
        -> PlaybackFailureKind.UNSUPPORTED

        "ERROR_CODE_IO_FILE_NOT_FOUND",
        "ERROR_CODE_IO_NO_PERMISSION",
        "ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE",
        "ERROR_CODE_IO_UNSPECIFIED",
        -> PlaybackFailureKind.UNREADABLE

        else -> PlaybackFailureKind.OTHER
    }
    return PlaybackFailure(kind, errorCodeName)
}

/**
 * 在 cause 链里找 [StuckPlayerException]。
 *
 * 必须走链而不是只看 `error.cause`：`ExoPlaybackException.createForUnexpected` 只包
 * 一层，但 Renderer 层可能再包一层，逐层判断比假设层数稳。深度上限是防御自引用
 * cause 的死循环，真实链条不会超过三层。
 */
@OptIn(UnstableApi::class)
internal fun stuckDetailOf(error: Throwable): StuckDetail? {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is StuckPlayerException) {
            return StuckDetail(type = current.stuckType, timeoutMs = current.timeoutMs)
        }
        current = current.cause
        depth++
    }
    return null
}

private const val MAX_CAUSE_DEPTH = 8
