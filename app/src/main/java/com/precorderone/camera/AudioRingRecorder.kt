package com.precorderone.camera

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log

/**
 * Microphone -> AAC encoder -> its own ring buffer, on its own thread beside the video pipeline (no shared locks,
 * no work on the video path). Timestamps use the camera's clock ([bootTimeClock] for SENSOR_INFO_TIMESTAMP_SOURCE
 * REALTIME), taken from AudioRecord.getTimestamp so sound and picture stay in sync also after hours of buffering.
 */
internal class AudioRingRecorder(private val retentionUs: Long, private val bootTimeClock: Boolean) {
    @Volatile
    var ring: EncodedFrameRingBuffer? = null
        private set

    @Volatile
    var outputFormat: MediaFormat? = null
        private set

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** Caller checks the RECORD_AUDIO permission. Returns false if the microphone or encoder is unavailable. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val bufferSize = minBuffer * 4
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        }.getOrNull() ?: return false
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
        }
        val codec = runCatching {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        }.getOrElse {
            Log.w(TAG, "AAC encoder unavailable", it)
            record.release()
            return false
        }
        ring = EncodedFrameRingBuffer.create(BIT_RATE, AAC_FRAMES_PER_SECOND, retentionUs) ?: run {
            codec.release()
            record.release()
            return false
        }
        running = true
        record.startRecording()
        thread = Thread({ loop(record, codec) }, "precorder-audio").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        ring?.close()
        ring = null
        outputFormat = null
    }

    private fun loop(record: AudioRecord, codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val timestamp = AudioTimestamp()
        val timebase = if (bootTimeClock) AudioTimestamp.TIMEBASE_BOOTTIME else AudioTimestamp.TIMEBASE_MONOTONIC
        var framesRead = 0L
        var lastPtsUs = -1L
        var anchorUs = -1L
        try {
            while (running) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val input = codec.getInputBuffer(inIndex)!!
                    input.clear()
                    val read = record.read(input, input.remaining())
                    val bytes = read.coerceAtLeast(0)
                    if (anchorUs < 0) anchorUs = nowUs() - (bytes / BYTES_PER_FRAME) * 1_000_000L / SAMPLE_RATE
                    // Clock time of the chunk's first sample: the device maps a frame position to its clock. Until it
                    // reports one (right after start), count samples from the first read.
                    var ptsUs = if (record.getTimestamp(timestamp, timebase) == AudioRecord.SUCCESS) {
                        timestamp.nanoTime / 1000 + (framesRead - timestamp.framePosition) * 1_000_000L / SAMPLE_RATE
                    } else {
                        anchorUs + framesRead * 1_000_000L / SAMPLE_RATE
                    }
                    if (ptsUs <= lastPtsUs) ptsUs = lastPtsUs + 1
                    lastPtsUs = ptsUs
                    framesRead += bytes / BYTES_PER_FRAME
                    codec.queueInputBuffer(inIndex, 0, bytes, ptsUs, 0)
                }
                drain(codec, info)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio recording stopped", e)
        } finally {
            runCatching { record.stop() }
            record.release()
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 0)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                outIndex >= 0 -> {
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0 && !isConfig) {
                        buffer.limit(info.offset + info.size)
                        buffer.position(info.offset)
                        ring?.append(buffer, info.size, info.presentationTimeUs, info.flags)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
                else -> return
            }
        }
    }

    private fun nowUs(): Long =
        (if (bootTimeClock) android.os.SystemClock.elapsedRealtimeNanos() else System.nanoTime()) / 1000

    private companion object {
        const val TAG = "AudioRingRecorder"
        const val SAMPLE_RATE = 48_000
        const val BIT_RATE = 128_000
        const val BYTES_PER_FRAME = 2
        const val AAC_FRAMES_PER_SECOND = SAMPLE_RATE / 1024 + 1
    }
}
