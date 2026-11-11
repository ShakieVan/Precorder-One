package com.precorderone.camera

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.precorderone.camera.ExportTestSupport.context
import com.precorderone.camera.ExportTestSupport.encode
import com.precorderone.camera.ExportTestSupport.encodeTone
import com.precorderone.camera.ExportTestSupport.newClip
import com.precorderone.camera.ExportTestSupport.ring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile

/**
 * The save path with timecode on real codecs and the real graphics chip: encoder -> ring -> real-time copy ->
 * TimecodeBurner -> MP4. The clips land in Download/precorder_export_test_timecode*.mp4 to look at them as well.
 *
 * The test picture never gets brighter than mid grey and has no colour, so anything close to white is burnt-in text
 * and anything red is the dot of the speed label.
 */
@RunWith(AndroidJUnit4::class)
class TimecodeBurnerTest {
    private val width = 640
    private val height = 360

    @Test
    fun burnsTheTimecodeBottomLeftAndKeepsEveryFrame() {
        val source = sourceClip(fps = 30, frameCount = 60)
        val uri = newClip("timecode_landscape")
        var lastProgress = 0f
        write(uri) { fd ->
            TimecodeBurner.burn(
                source, fd, orientationHint = 0, timeScale = 2.0, frameRate = 15, bitRate = 3_000_000,
                overlay = ClipOverlay(Corner.BOTTOM_LEFT, null)
            ) { lastProgress = it }
        }
        val original = decode(Uri.fromFile(source))
        val burned = decode(uri)
        source.delete()

        assertEquals(60, original.size)
        assertEquals(original.size, burned.size)
        assertEquals(1f, lastProgress, 0f)
        // Slow motion: the clip takes twice as long, frame by frame.
        burned.forEachIndexed { index, frame ->
            assertEquals("time of frame $index", (original[index].ptsUs - original[0].ptsUs) * 2.0, frame.ptsUs.toDouble(), 1_000.0)
        }

        // Box at the bottom left: 18 px text in a 360 px high picture.
        val box = Region(0, 300, 200, 360)
        burned.forEachIndexed { index, frame ->
            assertTrue("frame $index: timecode missing (${frame.white(box)})", frame.white(box) > 40)
            assertEquals("frame $index: white outside the box", 0, frame.whiteOutside(box))
            // The picture itself is the same as before, the right way up and with the same brightness.
            assertEquals("frame $index: brightness", original[index].mean(CENTER), frame.mean(CENTER), 8.0)
            assertTrue("frame $index: marker ${frame.mean(MARKER)}", frame.mean(MARKER) > 130)
            assertTrue("frame $index: corner ${frame.mean(OPPOSITE)}", frame.mean(OPPOSITE) < 110)
        }
        // It counts: 00:00:000 on the first frame, 00:01:967 on the last one.
        assertNotEquals(burned.first().pattern(box), burned.last().pattern(box))
    }

    /**
     * Phone held in portrait, timecode where it goes unless changed (top left): the stored frame stays landscape, the
     * text is turned to lie along its left edge.
     */
    @Test
    fun turnsTheTimecodeForPortraitClips() {
        val source = sourceClip(fps = 30, frameCount = 30)
        val uri = newClip("timecode_portrait")
        write(uri) { fd ->
            TimecodeBurner.burn(
                source, fd, orientationHint = 90, timeScale = 1.0, frameRate = 30, bitRate = 3_000_000,
                overlay = ClipOverlay(Corner.of(null), null)
            )
        }
        val burned = decode(uri)
        source.delete()

        assertEquals(30, burned.size)
        assertEquals(90, rotationOf(uri))
        // Top left of the upright picture is the bottom left of the stored frame.
        val box = Region(0, 230, 50, 360)
        burned.forEachIndexed { index, frame ->
            assertTrue("frame $index: timecode missing (${frame.white(box)})", frame.white(box) > 40)
            assertEquals("frame $index: white outside the box", 0, frame.whiteOutside(box))
            assertTrue("frame $index: marker ${frame.mean(MARKER)}", frame.mean(MARKER) > 130)
        }
    }

    /**
     * Slow motion with the timecode top right: the speed label moves to the top left, and its dot is there for half
     * a second and gone for half a second of the clip as it plays.
     */
    @Test
    fun speedLabelTakesTheOtherUpperCornerAndItsDotBlinks() {
        val source = sourceClip(fps = 30, frameCount = 45)
        val uri = newClip("timecode_slowmo_label")
        write(uri) { fd ->
            TimecodeBurner.burn(
                source, fd, orientationHint = 0, timeScale = 2.0, frameRate = 15, bitRate = 3_000_000,
                overlay = ClipOverlay(Corner.TOP_RIGHT, "Zeitlupe 2×")
            )
        }
        val burned = decode(uri)
        source.delete()

        assertEquals(45, burned.size)
        val timecode = Region(440, 0, 640, 60)
        val label = Region(0, 0, 240, 60)
        val dot = Region(4, 6, 36, 38)
        var shown = 0
        var hidden = 0
        burned.forEachIndexed { index, frame ->
            assertTrue("frame $index: timecode missing (${frame.white(timecode)})", frame.white(timecode) > 40)
            assertTrue("frame $index: label missing (${frame.white(label)})", frame.white(label) > 40)
            assertEquals("frame $index: white outside the boxes", 0, frame.whiteOutside(timecode, label))
            assertEquals("frame $index: red outside the dot", 0, frame.redOutside(dot))
            // Right at the change the clip's own rounding of the time decides; those frames prove nothing.
            val phaseUs = frame.ptsUs % 500_000L
            val atTheChange = frame.ptsUs > 20_000L && (phaseUs < 20_000L || phaseUs > 480_000L)
            if (!atTheChange) {
                if (frame.ptsUs / 500_000L % 2L == 0L) {
                    assertTrue("frame $index at ${frame.ptsUs} us: dot missing (${frame.red(dot)})", frame.red(dot) >= 4)
                    shown++
                } else {
                    assertEquals("frame $index at ${frame.ptsUs} us: dot shown", 0, frame.red(dot))
                    hidden++
                }
            }
        }
        // 3 s of playback: on, off, on, off, on, off.
        assertTrue("dot shown in $shown frames, hidden in $hidden", shown >= 15 && hidden >= 15)
    }

    /** Time lapse without timecode: only the label, top right, and the clip runs twice as fast. */
    @Test
    fun speedLabelAloneGoesTopRight() {
        val source = sourceClip(fps = 30, frameCount = 30)
        val uri = newClip("timecode_timelapse_label")
        write(uri) { fd ->
            TimecodeBurner.burn(
                source, fd, orientationHint = 0, timeScale = 0.5, frameRate = 60, bitRate = 3_000_000,
                overlay = ClipOverlay(null, "Zeitraffer 2×")
            )
        }
        val original = decode(Uri.fromFile(source))
        val burned = decode(uri)
        source.delete()

        assertEquals(30, burned.size)
        val label = Region(400, 0, 640, 60)
        val dot = Region(456, 6, 500, 38)
        burned.forEachIndexed { index, frame ->
            assertEquals("time of frame $index", (original[index].ptsUs - original[0].ptsUs) * 0.5, frame.ptsUs.toDouble(), 1_000.0)
            assertTrue("frame $index: label missing (${frame.white(label)})", frame.white(label) > 40)
            assertEquals("frame $index: white outside the box", 0, frame.whiteOutside(label))
            // The whole clip plays within the first half second: the dot is there all the time.
            assertTrue("frame $index: dot missing (${frame.red(dot)})", frame.red(dot) >= 4)
            assertEquals("frame $index: red outside the dot", 0, frame.redOutside(dot))
            assertTrue("frame $index: marker ${frame.mean(MARKER)}", frame.mean(MARKER) > 130)
        }
    }

    @Test
    fun realTimeClipKeepsItsSound() {
        val source = sourceClip(fps = 30, frameCount = 60, toneSeconds = 2)
        val uri = newClip("timecode_sound")
        write(uri) { fd ->
            TimecodeBurner.burn(
                source, fd, orientationHint = 0, timeScale = 1.0, frameRate = 30, bitRate = 3_000_000,
                overlay = ClipOverlay(Corner.TOP_LEFT, null)
            )
        }
        val before = audioSamples(Uri.fromFile(source))
        val after = audioSamples(uri)
        val frames = decode(uri).size
        source.delete()

        assertEquals(60, frames)
        assertTrue("sound frames before: $before", before in 80..100)
        assertEquals(before, after)
    }

    /** What is saved when burning fails: the copy as it is, turned and stretched like the clip was meant to be. */
    @Test
    fun plainCopyKeepsFramesTurnAndSlowMotion() {
        val source = sourceClip(fps = 30, frameCount = 45)
        val uri = newClip("timecode_fallback")
        write(uri) { fd -> ClipMuxer.copy(source, fd, orientationHint = 90, timeScale = 4.0) }
        val original = decode(Uri.fromFile(source))
        val copied = decode(uri)
        source.delete()

        assertEquals(original.size, copied.size)
        assertEquals(90, rotationOf(uri))
        assertEquals((original.last().ptsUs - original[0].ptsUs) * 4.0, copied.last().ptsUs.toDouble(), 1_000.0)
        assertEquals(0, copied.first().whiteOutside(Region(0, 0, 0, 0)))
    }

    /** The real-time copy the engine makes of the ring before it burns the timecode in. */
    private fun sourceClip(fps: Int, frameCount: Int, toneSeconds: Int = 0): File {
        val videoRing = ring(8 * 1024 * 1024, retentionUs = 60_000_000L)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val videoFormat = encode(MediaFormat.MIMETYPE_VIDEO_AVC, format, videoRing, frameCount, { index -> index * 1_000_000L / fps }) { codec, inIndex, index ->
            fillFrame(codec.getInputImage(inIndex)!!, index)
            width * height * 3 / 2
        }
        val audioRing = if (toneSeconds > 0) ring(1024 * 1024, retentionUs = 60_000_000L) else null
        val audioFormat = audioRing?.let { encodeTone(it, toneSeconds) }
        val file = File(context.cacheDir, "timecode_test_${System.nanoTime()}.mp4")
        val audioLease = audioRing?.beginRead()
        videoRing.beginRead()!!.use { lease ->
            val audio = if (audioLease != null && audioFormat != null) ClipMuxer.AudioInput(audioLease, audioFormat) else null
            RandomAccessFile(file, "rw").use {
                ClipMuxer.write(lease, ClipMuxer.framesToExport(lease)!!, videoFormat, it.fd, 0, 1.0, audio)
            }
        }
        audioLease?.close()
        videoRing.close()
        audioRing?.close()
        return file
    }

    private fun write(uri: Uri, block: (FileDescriptor) -> Unit) {
        context.contentResolver.openFileDescriptor(uri, "rw")!!.use { block(it.fileDescriptor) }
    }

    private fun rotationOf(uri: Uri): Int {
        val extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
        val format = extractor.getTrackFormat(TimecodeBurner.trackIndex(extractor, "video/"))
        val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
        extractor.release()
        return rotation
    }

    private fun audioSamples(uri: Uri): Int {
        val extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
        val track = TimecodeBurner.trackIndex(extractor, "audio/")
        assertTrue("no sound track", track >= 0)
        extractor.selectTrack(track)
        var count = 0
        while (extractor.sampleTime >= 0) {
            count++
            extractor.advance()
        }
        extractor.release()
        return count
    }

    private class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
    }

    /** One decoded frame: brightness, one byte per pixel, and the red part of its colour, one byte per 2x2 pixels. */
    private inner class Frame(val ptsUs: Long, private val luma: ByteArray, private val redness: ByteArray) {
        private fun at(x: Int, y: Int) = luma[y * width + x].toInt() and 0xFF
        private fun isRed(x: Int, y: Int) = (redness[y * (width / 2) + x].toInt() and 0xFF) > RED

        /** Red colour samples of [region], which is given in pixels like all regions. */
        fun red(region: Region): Int {
            var count = 0
            for (y in region.top / 2 until region.bottom / 2) for (x in region.left / 2 until region.right / 2) if (isRed(x, y)) count++
            return count
        }

        fun redOutside(region: Region): Int {
            var count = 0
            for (y in 0 until height / 2) for (x in 0 until width / 2) if (!region.contains(x * 2, y * 2) && isRed(x, y)) count++
            return count
        }

        fun mean(region: Region): Double {
            var sum = 0L
            for (y in region.top until region.bottom) for (x in region.left until region.right) sum += at(x, y)
            return sum.toDouble() / ((region.right - region.left) * (region.bottom - region.top))
        }

        fun white(region: Region): Int {
            var count = 0
            for (y in region.top until region.bottom) for (x in region.left until region.right) if (at(x, y) > WHITE) count++
            return count
        }

        fun whiteOutside(vararg regions: Region): Int {
            var count = 0
            for (y in 0 until height) for (x in 0 until width) if (regions.none { it.contains(x, y) } && at(x, y) > WHITE) count++
            return count
        }

        /** Which pixels of [region] are white: the shape of the digits. */
        fun pattern(region: Region): List<Boolean> {
            val pixels = ArrayList<Boolean>()
            for (y in region.top until region.bottom) for (x in region.left until region.right) pixels += at(x, y) > WHITE
            return pixels
        }
    }

    private fun decode(uri: Uri): List<Frame> {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = TimecodeBurner.trackIndex(extractor, "video/")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        // The frames as stored; a turn in the format is not applied to byte buffers anyway.
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val frames = mutableListOf<Frame>()
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
                    decoder.getOutputImage(outIndex)?.use { image -> frames += Frame(info.presentationTimeUs, lumaOf(image), rednessOf(image)) }
                }
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                decoder.releaseOutputBuffer(outIndex, false)
            }
        }
        decoder.stop()
        decoder.release()
        extractor.release()
        return frames
    }

    private fun lumaOf(image: Image): ByteArray {
        val plane = image.planes[0]
        val out = ByteArray(width * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                out[row * width + col] = plane.buffer.get(row * plane.rowStride + col * plane.pixelStride)
            }
        }
        return out
    }

    /** The V plane: 128 where the picture has no colour, far above that where it is red. */
    private fun rednessOf(image: Image): ByteArray {
        val plane = image.planes[2]
        val out = ByteArray(width / 2 * (height / 2))
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                out[row * (width / 2) + col] = plane.buffer.get(row * plane.rowStride + col * plane.pixelStride)
            }
        }
        return out
    }

    /**
     * Moving stripes on a background that changes from frame to frame, at most mid grey, and a brighter block at
     * the top left that tells a flipped or turned picture from a correct one.
     */
    private fun fillFrame(image: Image, index: Int) {
        val luma = image.planes[0]
        val base = 30 + index % 40
        for (row in 0 until image.height) {
            for (col in 0 until image.width) {
                val value = if (row < 80 && col < 80) 150 else base + ((col + row + index * 6) / 24) % 2 * 30
                luma.buffer.put(row * luma.rowStride + col * luma.pixelStride, value.toByte())
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

    private companion object {
        const val WHITE = 200
        const val RED = 165
        val CENTER = Region(240, 100, 400, 260)
        val MARKER = Region(10, 10, 70, 70)
        val OPPOSITE = Region(570, 10, 630, 70)
    }
}
