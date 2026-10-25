package com.milan.music.diag

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two lines below are the only evidence that the audio path does what the player
 * configuration claims, and the only measurement behind any future buffer tuning.
 * The field names are a contract: `adb logcat -s AudioPipeline` is the filter.
 */
class AudioPipelineTest {

    @Test
    fun `underrun line carries all three numbers`() {
        // bufferSizeMs is what tells a 20 ms underrun (decoder hiccup) apart from a
        // 400 ms one (storage stall); without it the byte count alone is unreadable.
        assertEquals(
            "audio underrun buffer=8192 bufferMs=46 sinceFeedMs=120",
            underrunLine(bufferSizeBytes = 8192, bufferSizeMs = 46, elapsedSinceLastFeedMs = 120),
        )
    }

    @Test
    fun `float output is named`() {
        // setEnableAudioFloatOutput(true) is only a request. Seeing pcm=float here is
        // what proves 24-bit sources are not being truncated to 16-bit on this device.
        assertEquals(
            "audio input rate=96000 ch=2 pcm=float",
            audioInputLine(sampleRate = 96_000, channelCount = 2, pcmEncoding = C.ENCODING_PCM_FLOAT),
        )
    }

    @Test
    fun `integer encodings are named`() {
        assertEquals(
            "audio input rate=44100 ch=2 pcm=s16",
            audioInputLine(sampleRate = 44_100, channelCount = 2, pcmEncoding = C.ENCODING_PCM_16BIT),
        )
        assertEquals(
            "audio input rate=44100 ch=2 pcm=s24",
            audioInputLine(sampleRate = 44_100, channelCount = 2, pcmEncoding = C.ENCODING_PCM_24BIT),
        )
    }

    @Test
    fun `an unknown encoding keeps its raw value`() {
        // Never drop the number: an unmapped encoding is the interesting case, not the
        // one to hide behind a constant string.
        assertEquals(
            "audio input rate=44100 ch=1 pcm=unknown(7)",
            audioInputLine(sampleRate = 44_100, channelCount = 1, pcmEncoding = 7),
        )
    }
}
