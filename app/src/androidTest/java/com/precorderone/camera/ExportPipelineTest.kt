package com.precorderone.camera

import android.content.ContentValues
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SharedMemory
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real encoder -> ring buffer in shared memory -> ClipMuxer, the same path as a saved clip, without the camera.
 * The ring is small on purpose so frames wrap around several times. The clip lands in Download/
 * (precorder_export_test_*.mp4) to check it with a decoder, e.g. ffmpeg -v error -i clip.mp4 -f null -.
 */
@RunWith(AndroidJUnit4::class)
class ExportPipelineTest {
    private val width = 640
    private val height = 360
    private val fps = 60
    private val frameCount = 240

    @Test
    fun encodedFramesSurviveRingBufferAndMuxer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shared = SharedMemory.create("export-test", 512 * 1024)
        val memory = shared.mapReadWrite()
        val ring = EncodedFrameRingBuffer(memory, 2048, 2_000_000L) {
            SharedMemory.unmap(memory)
            shared.close()
        }

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        var outputFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var queued = 0
        var appended = 0
        var done = false
        while (!done) {
            if (queued <= frameCount) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val ptsUs = queued * 1_000_000L / fps
                    if (queued == frameCount) {
                        codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        fillFrame(codec.getInputImage(inIndex)!!, queued)
                        codec.queueInputBuffer(inIndex, 0, width * height * 3 / 2, ptsUs, 0)
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
                    if (ring.append(buffer, info.size, info.presentationTimeUs, info.flags)) appended++
                }
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                codec.releaseOutputBuffer(outIndex, false)
            }
        }
        codec.stop()
        codec.release()

        // Public Downloads: the clip outlives the test app, which Gradle uninstalls after the run.
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "precorder_export_test_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        var exportedFrames = 0
        ring.beginRead()!!.use { lease ->
            val frames = ClipMuxer.framesToExport(lease)!!
            assertTrue("too few frames: ${frames.size}", frames.size >= 20)
            exportedFrames = frames.size
            context.contentResolver.openFileDescriptor(uri, "rw")!!.use { output ->
                ClipMuxer.write(lease, frames, outputFormat!!, output.fileDescriptor, 0, 30, fps)
            }
        }
        ring.close()
        assertTrue(appended >= frameCount - 2)

        // Decode the clip again: every exported frame must come back, and each one must differ
        // (a muxer reading the wrong bytes gave a still image or undecodable frames).
        val hashes = decodedFrameHashes(uri)
        assertEquals(exportedFrames, hashes.size)
        assertTrue("only ${hashes.toSet().size} distinct of ${hashes.size}", hashes.toSet().size >= hashes.size - 2)
    }

    private fun decodedFrameHashes(uri: android.net.Uri): List<Long> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
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
