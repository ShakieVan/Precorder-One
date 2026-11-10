package com.precorderone.camera

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer

/** Writes frames of ring buffer leases as MP4 without copying them. */
internal object ClipMuxer {
    private const val MIN_FRAMES = 8

    /** Audio to mux beside the video: its own lease and the AAC encoder's output format. */
    class AudioInput(val lease: EncodedFrameRingBuffer.Lease, val format: MediaFormat)

    /** Frames from the first key frame on (a clip must start with one), or null if too few remain. */
    fun framesToExport(lease: EncodedFrameRingBuffer.Lease): List<EncodedFrameRingBuffer.Frame>? {
        val firstKeyIndex = lease.frames.indexOfFirst { it.isKeyFrame }
        if (firstKeyIndex < 0 || lease.frames.size - firstKeyIndex < MIN_FRAMES) return null
        return lease.frames.subList(firstKeyIndex, lease.frames.size)
    }

    /**
     * Muxes [frames] into [fd]. Timestamps keep their real spacing, stretched by [timeScale]
     * (recording fps / playback fps): 120 -> 30 fps plays four times slower, and a dropped frame keeps its gap.
     * [audio] is only written at real-time speed (timeScale 1), cut to the video's time span. Throws on failure.
     */
    fun write(
        lease: EncodedFrameRingBuffer.Lease,
        frames: List<EncodedFrameRingBuffer.Frame>,
        format: MediaFormat,
        fd: FileDescriptor,
        orientationHint: Int,
        timeScale: Double,
        audio: AudioInput? = null
    ) {
        val firstPts = frames.first().presentationTimeUs
        val lastPts = frames.last().presentationTimeUs
        val audioFrames = audio?.takeIf { timeScale == 1.0 }?.lease?.frames
            ?.filter { it.presentationTimeUs in firstPts..lastPts }
            .orEmpty()

        val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var wroteSamples = false
        try {
            muxer.setOrientationHint(orientationHint)
            val videoTrack = muxer.addTrack(format)
            val audioTrack = if (audioFrames.isNotEmpty()) muxer.addTrack(audio!!.format) else -1
            muxer.start()
            started = true

            // Interleave by time, as a player reads the file.
            val samples = frames.map { Sample(it, lease, videoTrack, ((it.presentationTimeUs - firstPts) * timeScale).toLong()) } +
                audioFrames.map { Sample(it, audio!!.lease, audioTrack, it.presentationTimeUs - firstPts) }
            samples.sortedBy { it.outputPtsUs }.forEach { sample ->
                val info = MediaCodec.BufferInfo().apply {
                    // Offset 0: lease.data() is a slice that starts at the frame (see EncodedFrameRingBuffer.Lease).
                    offset = 0
                    size = sample.frame.size
                    presentationTimeUs = sample.outputPtsUs
                    flags = if (sample.frame.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                }
                muxer.writeSampleData(sample.track, sample.lease.data(sample.frame), info)
                wroteSamples = true
            }
            if (!wroteSamples) error("No media samples written")
        } finally {
            if (started && wroteSamples) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    /**
     * Copies the finished clip [input] into [fd] without touching its frames, stretched by [timeScale] like [write].
     * Sound stays only at real-time speed. Used when a real-time copy exists but re-encoding it failed.
     */
    fun copy(input: File, fd: FileDescriptor, orientationHint: Int, timeScale: Double) {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var wroteSamples = false
        try {
            extractor.setDataSource(input.absolutePath)
            muxer.setOrientationHint(orientationHint)
            var maxSize = 1 shl 20
            var firstVideoUs = -1L
            // Extractor track -> muxer track.
            val tracks = HashMap<Int, Int>()
            val videoTracks = HashSet<Int>()
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                val isVideo = mime.startsWith("video/")
                if (!isVideo && !(mime.startsWith("audio/") && timeScale == 1.0)) continue
                // The turn is set for the whole file above.
                if (isVideo) {
                    format.setInteger(MediaFormat.KEY_ROTATION, 0)
                    videoTracks += index
                }
                tracks[index] = muxer.addTrack(format)
                extractor.selectTrack(index)
                maxSize = TimecodeBurner.maxInputSize(format, maxSize)
            }
            check(videoTracks.isNotEmpty()) { "No video track" }
            muxer.start()
            started = true

            val buffer = ByteBuffer.allocateDirect(maxSize)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val index = extractor.sampleTrackIndex
                val isVideo = index in videoTracks
                if (isVideo && firstVideoUs < 0) firstVideoUs = extractor.sampleTime
                val ptsUs = if (isVideo) ((extractor.sampleTime - firstVideoUs) * timeScale).toLong() else extractor.sampleTime
                val flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, ptsUs.coerceAtLeast(0L), flags)
                muxer.writeSampleData(tracks.getValue(index), buffer, info)
                wroteSamples = true
                extractor.advance()
            }
            if (!wroteSamples) error("No media samples written")
        } finally {
            if (started && wroteSamples) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { extractor.release() }
        }
    }

    private class Sample(
        val frame: EncodedFrameRingBuffer.Frame,
        val lease: EncodedFrameRingBuffer.Lease,
        val track: Int,
        val outputPtsUs: Long
    )
}
