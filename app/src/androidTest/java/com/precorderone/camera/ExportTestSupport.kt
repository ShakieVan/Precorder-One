package com.precorderone.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.os.SharedMemory
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Real encoders and ring buffers for the export tests, without camera and microphone. */
internal object ExportTestSupport {
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun ring(bytes: Int, retentionUs: Long): EncodedFrameRingBuffer {
        val shared = SharedMemory.create("export-test", bytes)
        val memory = shared.mapReadWrite()
        return EncodedFrameRingBuffer(memory, 4096, retentionUs) {
            SharedMemory.unmap(memory)
            shared.close()
        }
    }

    fun newClip(tag: String): Uri {
        // Public Downloads: the clip outlives the test app, which Gradle uninstalls after the run.
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "precorder_export_test_${tag}_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
    }

    /** 440 Hz sine as AAC, timestamps from 0 like the video, so the muxer aligns both. */
    fun encodeTone(ring: EncodedFrameRingBuffer, seconds: Int): MediaFormat {
        val sampleRate = 48_000
        val samplesPerChunk = 1024
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, samplesPerChunk * 2)
        }
        val chunks = seconds * sampleRate / samplesPerChunk
        return encode(MediaFormat.MIMETYPE_AUDIO_AAC, format, ring, chunks, { index -> index * samplesPerChunk * 1_000_000L / sampleRate }) { codec, inIndex, index ->
            val input: ByteBuffer = codec.getInputBuffer(inIndex)!!.order(ByteOrder.LITTLE_ENDIAN)
            input.clear()
            repeat(samplesPerChunk) { i ->
                val sample = sin(2 * PI * 440 * (index * samplesPerChunk + i) / sampleRate) * 12_000
                input.putShort(sample.toInt().toShort())
            }
            samplesPerChunk * 2
        }
    }

    /** Runs an encoder over [count] inputs and appends its output to [ring]; returns the output format. */
    fun encode(
        mime: String,
        format: MediaFormat,
        ring: EncodedFrameRingBuffer,
        count: Int,
        ptsUs: (Int) -> Long,
        fill: (MediaCodec, Int, Int) -> Int
    ): MediaFormat {
        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        var outputFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var queued = 0
        var done = false
        while (!done) {
            if (queued <= count) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    if (queued == count) {
                        codec.queueInputBuffer(inIndex, 0, 0, ptsUs(queued), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        codec.queueInputBuffer(inIndex, 0, fill(codec, inIndex, queued), ptsUs(queued), 0)
                    }
                    queued++
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outputFormat = codec.outputFormat
            if (outIndex >= 0) {
                val buffer = codec.getOutputBuffer(outIndex)!!
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (info.size > 0 && !isConfig) {
                    buffer.limit(info.offset + info.size)
                    buffer.position(info.offset)
                    ring.append(buffer, info.size, info.presentationTimeUs, info.flags)
                }
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                codec.releaseOutputBuffer(outIndex, false)
            }
        }
        codec.stop()
        codec.release()
        return outputFormat!!
    }
}
