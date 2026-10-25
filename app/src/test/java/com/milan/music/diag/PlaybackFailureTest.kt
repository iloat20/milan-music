package com.milan.music.diag

import androidx.media3.common.util.StuckPlayerException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡死必须优先于 error code：media3 把 `StuckPlayerException` 包成
 * `ERROR_CODE_UNEXPECTED` 的 `ExoPlaybackException`，只看 code 只能得到 "unexpected"。
 */
class PlaybackFailureTest {

    @Test
    fun `stuck cause wins over the unexpected error code`() {
        val failure = classifyPlaybackFailure(
            errorCodeName = "ERROR_CODE_UNEXPECTED",
            stuck = StuckDetail(type = 2, timeoutMs = 10_000),
        )
        assertEquals(PlaybackFailureKind.STUCK, failure.kind)
        assertEquals(
            "kind=stuck code=ERROR_CODE_UNEXPECTED stuckType=2 stuckTimeoutMs=10000",
            failure.describe(),
        )
    }

    @Test
    fun `stuck playback is never retried automatically`() {
        val stuck = classifyPlaybackFailure("ERROR_CODE_UNEXPECTED", StuckDetail(0, 600_000))
        assertFalse(stuck.autoRetryAllowed)
        val decoding = classifyPlaybackFailure("ERROR_CODE_DECODING_FAILED", null)
        assertTrue(decoding.autoRetryAllowed)
    }

    @Test
    fun `decoder failures are their own category`() {
        listOf(
            "ERROR_CODE_DECODER_INIT_FAILED",
            "ERROR_CODE_DECODER_QUERY_FAILED",
            "ERROR_CODE_DECODING_FAILED",
            "ERROR_CODE_AUDIO_TRACK_INIT_FAILED",
        ).forEach { code ->
            assertEquals(code, PlaybackFailureKind.DECODER, classifyPlaybackFailure(code, null).kind)
        }
    }

    @Test
    fun `missing files and revoked permission read as unreadable`() {
        listOf(
            "ERROR_CODE_IO_FILE_NOT_FOUND",
            "ERROR_CODE_IO_NO_PERMISSION",
            "ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE",
        ).forEach { code ->
            assertEquals(code, PlaybackFailureKind.UNREADABLE, classifyPlaybackFailure(code, null).kind)
        }
    }

    @Test
    fun `unsupported containers and codecs are not decoder failures`() {
        assertEquals(
            PlaybackFailureKind.UNSUPPORTED,
            classifyPlaybackFailure("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED", null).kind,
        )
        assertEquals(
            PlaybackFailureKind.UNSUPPORTED,
            classifyPlaybackFailure("ERROR_CODE_DECODING_FORMAT_UNSUPPORTED", null).kind,
        )
    }

    @Test
    fun `unknown codes stay other and keep the code in the log line`() {
        val failure = classifyPlaybackFailure("ERROR_CODE_UNSPECIFIED", null)
        assertEquals(PlaybackFailureKind.OTHER, failure.kind)
        assertEquals("kind=other code=ERROR_CODE_UNSPECIFIED", failure.describe())
    }

    @Test
    fun `stuck detail is found inside a wrapped cause chain`() {
        val error = IllegalStateException(StuckPlayerException(3, 60_000))
        assertEquals(StuckDetail(type = 3, timeoutMs = 60_000), stuckDetailOf(error))
    }

    @Test
    fun `ordinary failures have no stuck detail`() {
        assertNull(stuckDetailOf(IllegalStateException("exo: decoding failed")))
        assertNull(stuckDetailOf(IllegalStateException(RuntimeException("nested"))))
    }
}
