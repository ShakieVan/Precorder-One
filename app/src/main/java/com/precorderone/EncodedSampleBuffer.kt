package com.precorderone

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque

data class EncodedSample(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val flags: Int
)

class EncodedSampleBuffer(private val maxDurationUs: Long) {
    private val samples = ArrayDeque<EncodedSample>()

    @Synchronized
    fun add(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.size <= 0 || (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return
        val out = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.get(out)
        samples.addLast(EncodedSample(out, info.presentationTimeUs, info.flags))
        trimToDuration()
    }

    @Synchronized
    fun clear() = samples.clear()

    @Synchronized
    fun writeToMp4(file: File, format: MediaFormat): Boolean {
        if (samples.isEmpty()) return false
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val trackIndex = muxer.addTrack(format)
        muxer.start()

        val list = samples.toList()
        val firstKeyframe = list.indexOfFirst { it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 }
        if (firstKeyframe < 0) {
            muxer.stop()
            muxer.release()
            return false
        }

        list.drop(firstKeyframe).forEach { sample ->
            val info = MediaCodec.BufferInfo().apply {
                offset = 0
                size = sample.data.size
                presentationTimeUs = sample.presentationTimeUs
                flags = sample.flags
            }
            muxer.writeSampleData(trackIndex, ByteBuffer.wrap(sample.data), info)
        }
        muxer.stop()
        muxer.release()
        return true
    }

    @Synchronized
    fun size(): Int = samples.size

    private fun trimToDuration() {
        while (samples.size > 1) {
            val first = samples.first()
            val last = samples.last()
            if (last.presentationTimeUs - first.presentationTimeUs <= maxDurationUs) break
            samples.removeFirst()
        }
    }
}
