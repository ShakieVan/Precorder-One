package com.precorderone.camera

import android.media.MediaCodecInfo
import android.media.MediaCodecList

/** What the video encoder of this phone takes. */
internal object EncoderLimits {
    private val capabilities = HashMap<String, MediaCodecInfo.VideoCapabilities?>()

    /**
     * Whether the encoder for [mime] encodes pictures of [width] x [height] at [fps]. Asks the first encoder in the
     * system's list, the one MediaCodec.createEncoderByType hands out. True as well when the phone does not tell.
     */
    @Synchronized
    fun manages(mime: String, width: Int, height: Int, fps: Int): Boolean {
        val caps = capabilities.getOrPut(mime) { runCatching { find(mime) }.getOrNull() } ?: return true
        return runCatching { caps.areSizeAndRateSupported(width, height, fps.toDouble()) }.getOrDefault(true)
    }

    private fun find(mime: String): MediaCodecInfo.VideoCapabilities? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { info -> info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            ?.getCapabilitiesForType(mime)
            ?.videoCapabilities
}
