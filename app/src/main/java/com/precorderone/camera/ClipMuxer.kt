package com.precorderone.camera

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor

/** Writes frames of a ring buffer lease as MP4 without copying them. */
internal object ClipMuxer {
    private const val MIN_FRAMES = 8

    /** Frames from the first key frame on (a clip must start with one), or null if too few remain. */
    fun framesToExport(lease: EncodedFrameRingBuffer.Lease): List<EncodedFrameRingBuffer.Frame>? {
        val firstKeyIndex = lease.frames.indexOfFirst { it.isKeyFrame }
        if (firstKeyIndex < 0 || lease.frames.size - firstKeyIndex < MIN_FRAMES) return null
        return lease.frames.subList(firstKeyIndex, lease.frames.size)
    }

    /**
     * Muxes [frames] into [fd]. Slow motion follows the measured capture rate instead of the frame index: a dropped
     * frame keeps its gap instead of speeding the clip up, and a camera that delivers less than its target still plays
     * smoothly. Throws on failure.
     */
    fun write(
        lease: EncodedFrameRingBuffer.Lease,
        frames: List<EncodedFrameRingBuffer.Frame>,
        format: MediaFormat,
        fd: FileDescriptor,
        orientationHint: Int,
        playbackFps: Int,
        fallbackCaptureFps: Int
    ) {
        val firstPts = frames.first().presentationTimeUs
        val spanUs = frames.last().presentationTimeUs - firstPts
        val capturedFps = if (spanUs > 0) (frames.size - 1) * 1_000_000.0 / spanUs else fallbackCaptureFps.toDouble()
        val timeScale = capturedFps / playbackFps.coerceAtLeast(1)

        val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var wroteSamples = false
        try {
            muxer.setOrientationHint(orientationHint)
            val track = muxer.addTrack(format)
            muxer.start()
            started = true
            frames.forEach { frame ->
                val info = MediaCodec.BufferInfo().apply {
                    // Offset 0: lease.data() is a slice that starts at the frame (see EncodedFrameRingBuffer.Lease).
                    offset = 0
                    size = frame.size
                    presentationTimeUs = ((frame.presentationTimeUs - firstPts) * timeScale).toLong()
                    flags = if (frame.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                }
                muxer.writeSampleData(track, lease.data(frame), info)
                wroteSamples = true
            }
            if (!wroteSamples) error("No media samples written")
        } finally {
            if (started && wroteSamples) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}
