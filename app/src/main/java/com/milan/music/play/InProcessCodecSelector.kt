package com.milan.music.play

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Decoder ranking that floats Android 17's in-process software audio decoders
 * (`c2.android.inproc.*`) to the front of the software tier.
 *
 * Background: before API 37 every software audio decoder ran in the separate
 * `mediaswcodec` process, so each decoded buffer crossed Binder twice and paid for
 * two scheduler wake-ups. Under load that queueing surfaces as buffer underrun —
 * audible as a click or a stall, not as a wrong sample. An in-process decoder drops
 * the hop, so the renderer thread feeds the sink directly.
 *
 * The platform list is *reordered*, never rebuilt. That list already carries the
 * capability filtering and the per-codec workarounds media3 depends on; the entries
 * we do not promote keep their original relative order, so a decoder that rejects a
 * format (multichannel AAC, odd sample rates) still falls back exactly where it used
 * to. The audio renderer walks the list and takes the first entry that reports the
 * format as supported, which makes this ranking a preference rather than a hard pin.
 *
 * Hardware decoders stay ahead of everything. Some SoCs expose a hardware AAC
 * decoder; promoting a software one past it would trade a DSP job for a CPU job and
 * cost power instead of saving it, so only the software tier is reordered.
 *
 * Offload is disabled in [PlaybackService], so this ranking is consulted on every
 * playback rather than on a fallback path: there is no DSP route left that would
 * bypass it. [PlaybackService] logs the decoder that was picked, which is the only
 * way to confirm on a device that the ranking took effect.
 */
@OptIn(UnstableApi::class)
object InProcessCodecSelector : MediaCodecSelector {

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val ranked =
            MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder,
            )

        // Secure and tunneling playback are hardware-only paths that an in-process
        // decoder cannot serve; leaving those lists untouched keeps DRM behaviour
        // on exactly the code path it has today.
        if (requiresSecureDecoder || requiresTunnelingDecoder) return ranked

        // The same selector instance is handed to the video renderers, and there is no
        // in-process video decoder to prefer, so scope the reorder to audio.
        if (!mimeType.startsWith(AUDIO_MIME_PREFIX)) return ranked

        val (hardware, software) = ranked.partition { it.hardwareAccelerated }
        val (inProcess, otherSoftware) = software.partition { it.name.contains(IN_PROCESS_MARKER) }

        // Both partitions preserve relative order, so a device that predates the new
        // decoders gets back a list identical to the default one.
        if (inProcess.isEmpty()) return ranked
        return hardware + inProcess + otherSoftware
    }

    private const val AUDIO_MIME_PREFIX = "audio/"
    private const val IN_PROCESS_MARKER = ".inproc."
}
