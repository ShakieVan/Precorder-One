package com.precorderone.camera

import android.content.ContentValues
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SharedMemory
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Real encoders -> ring buffers in shared memory -> ClipMuxer, the same path as a saved clip, without camera and
 * microphone. Clips land in Download/precorder_export_test_*.mp4 to check them with a decoder as well,
 * e.g. ffmpeg -v error -i clip.mp4 -f null -.
 */
@RunWith(AndroidJUnit4::class)
class ExportPipelineTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val width = 640
    private val height = 360

    @Test
    fun slowMotionClipSurvivesWrappingRing() {
        // Small ring on purpose: the frames wrap around several times, like a long recording.
        val ring = ring(512 * 1024, retentionUs = 2_000_000L)
        val format = encodeVideo(ring, fps = 60, frameCount = 240)
        val uri = newClip("slowmo")
        var exportedFrames = 0
        ring.beginRead()!!.use { lease ->
            val frames = ClipMuxer.framesToExport(lease)!!
            assertTrue("too few frames: ${frames.size}", frames.size >= 20)
            exportedFrames = frames.size
            context.contentResolver.openFileDescriptor(uri, "rw")!!.use {
                ClipMuxer.write(lease, frames, format, it.fileDescriptor, 0, timeScale = 2.0)
            }
        }
        ring.close()

        // Every exported frame must decode, and each one must differ (a muxer reading the wrong bytes gave a
        // still image or undecodable frames).
        val hashes = decodedFrameHashes(uri)
        assertEquals(exportedFrames, hashes.size)
        assertTrue("only ${hashes.toSet().size} distinct of ${hashes.size}", hashes.toSet().size >= hashes.size - 2)
    }

    @Test
    fun realTimeClipGetsSoundTrack() {
        val videoRing = ring(4 * 1024 * 1024, retentionUs = 10_000_000L)
        val audioRing = ring(1024 * 1024, retentionUs = 10_000_000L)
        val videoFormat = encodeVideo(videoRing, fps = 30, frameCount = 90)
        val audioFormat = encodeTone(audioRing, seconds = 3)
        val uri = newClip("audio")
        videoRing.beginRead()!!.use { video ->
            audioRing.beginRead()!!.use { audio ->
                val frames = ClipMuxer.framesToExport(video)!!
                context.contentResolver.openFileDescriptor(uri, "rw")!!.use {
                    ClipMuxer.write(video, frames, videoFormat, it.fileDescriptor, 0, timeScale = 1.0,
                        audio = ClipMuxer.AudioInput(audio, audioFormat))
                }
            }
        }
        videoRing.close()
        audioRing.close()

        val extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
        val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!! }
        assertTrue("tracks: $mimes", mimes.any { it.startsWith("video/") } && mimes.any { it.startsWith("audio/") })
        val audioTrack = mimes.indexOfFirst { it.startsWith("audio/") }
        extractor.selectTrack(audioTrack)
        var audioSamples = 0
        var lastAudioUs = 0L
        while (extractor.sampleTime >= 0) {
            audioSamples++
            lastAudioUs = extractor.sampleTime
            extractor.advance()
        }
        extractor.release()
        // ~47 AAC frames per second; the sound is cut to the video's span (about 3 s).
        assertTrue("audio frames: $audioSamples", audioSamples in 100..160)
        assertTrue("audio ends at $lastAudioUs", lastAudioUs in 2_500_000L..3_100_000L)
        assertEquals(90, decodedFrameHashes(uri).size)
    }

    private fun ring(bytes: Int, retentionUs: Long): EncodedFrameRingBuffer {
        val shared = SharedMemory.create("export-test", bytes)
        val memory = shared.mapReadWrite()
        return EncodedFrameRingBuffer(memory, 4096, retentionUs) {
            SharedMemory.unmap(memory)
            shared.close()
        }
    }

    private fun newClip(tag: String): Uri {
        // Public Downloads: the clip outlives the test app, which Gradle uninstalls after the run.
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "precorder_export_test_${tag}_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
    }

    private fun encodeVideo(ring: EncodedFrameRingBuffer, fps: Int, frameCount: Int): MediaFormat {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        return encode(MediaFormat.MIMETYPE_VIDEO_AVC, format, ring, frameCount, { index -> index * 1_000_000L / fps }) { codec, inIndex, index ->
            fillFrame(codec.getInputImage(inIndex)!!, index)
            width * height * 3 / 2
        }
    }

    /** 440 Hz sine as AAC, timestamps from 0 like the video, so the muxer aligns both. */
    private fun encodeTone(ring: EncodedFrameRingBuffer, seconds: Int): MediaFormat {
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
    private fun encode(
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

    private fun decodedFrameHashes(uri: Uri): List<Long> {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val hashes = mutableListOf<Long>()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val size = extractor.readSampleData(decoder.getInputBuffer(inIndex)!!, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
            if (outIndex >= 0) {
                if (info.size > 0) {
                    decoder.getOutputImage(outIndex)?.use { image -> hashes += lumaSum(image) }
                }
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                decoder.releaseOutputBuffer(outIndex, false)
            }
        }
        decoder.stop()
        decoder.release()
        extractor.release()
        return hashes
    }

    private fun lumaSum(image: Image): Long {
        val luma = image.planes[0]
        var sum = 0L
        for (row in 0 until image.height step 8) {
            for (col in 0 until image.width step 8) {
                sum += luma.buffer.get(row * luma.rowStride + col * luma.pixelStride).toInt() and 0xFF
            }
        }
        return sum
    }

    /** Moving stripes on a background that brightens every frame, so no two frames look alike. */
    private fun fillFrame(image: Image, index: Int) {
        val luma = image.planes[0]
        val base = 30 + index % 100
        for (row in 0 until image.height) {
            for (col in 0 until image.width) {
                val stripe = ((col + row + index * 6) / 24) % 2
                luma.buffer.put(row * luma.rowStride + col * luma.pixelStride, (base + stripe * 100).toByte())
            }
        }
        for (planeIndex in 1..2) {
            val plane = image.planes[planeIndex]
            for (row in 0 until image.height / 2) {
                for (col in 0 until image.width / 2) {
                    plane.buffer.put(row * plane.rowStride + col * plane.pixelStride, 128.toByte())
                }
            }
        }
    }
}
