package com.precorderone.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordSizeTest {
    private val wide = 16f / 9f

    // What the main camera of a Galaxy S21 lists (Android 15): the big sizes at 30 fps, the others at 60.
    private val slow = 33_331_760L
    private val fast = 16_665_880L
    private val s21 = listOf(
        size(4032, 3024, slow), size(4032, 2268, slow), size(4032, 1816, slow), size(3024, 3024, slow),
        size(3840, 2160, slow), size(2400, 1080, fast), size(1920, 1440, fast), size(1920, 1080, fast),
        size(1920, 864, fast), size(1440, 1080, fast), size(1088, 1088, fast), size(1280, 720, fast),
        size(960, 720, fast), size(720, 480, fast), size(640, 480, fast), size(640, 360, fast),
        size(352, 288, fast), size(320, 240, fast), size(256, 144, fast), size(176, 144, fast)
    )

    private fun size(width: Int, height: Int, minFrameDurationNs: Long) = RecordSize.Candidate(width, height, minFrameDurationNs)

    private fun choose(
        candidates: List<RecordSize.Candidate>,
        fps: Int,
        ratio: Float = wide,
        encoderManages: (RecordSize.Candidate) -> Boolean = { true }
    ): String? = RecordSize.choose(candidates, ratio, fps, encoderManages)?.let { "${it.width}x${it.height}" }

    @Test
    fun takesTheBiggestPictureTheCameraDeliversAtTheRate() {
        assertEquals("4032x2268", choose(s21, 30))
        assertEquals("4032x2268", choose(s21, 24))
        // Not the smallest one, 256x144.
        assertEquals("1920x1080", choose(s21, 60))
    }

    @Test
    fun keepsTheWantedShape() {
        assertEquals("4032x3024", choose(s21, 30, ratio = 4f / 3f))
        assertEquals("1920x1440", choose(s21, 60, ratio = 4f / 3f))
    }

    @Test
    fun asksTheEncoderFrom60FpsOn() {
        val upTo720p = { it: RecordSize.Candidate -> it.height <= 720 }
        assertEquals("1280x720", choose(s21, 60, encoderManages = upTo720p))
        // Below 60 fps the camera's word is enough, as it always was.
        assertEquals("4032x2268", choose(s21, 30, encoderManages = upTo720p))
        // An encoder that turns everything down is not believed: better a picture than none.
        assertEquals("1920x1080", choose(s21, 60, encoderManages = { false }))
    }

    @Test
    fun acceptsRoundedAndMissingFrameDurations() {
        val rounded = listOf(size(3840, 2160, 33_333_333L), size(1920, 1080, 16_666_667L), size(1280, 720, 16_666_667L))
        assertEquals("1920x1080", choose(rounded, 60))
        assertEquals("3840x2160", choose(rounded, 30))
        val unknown = listOf(size(1920, 1080, 0L), size(1280, 720, 0L))
        assertEquals("1920x1080", choose(unknown, 60))
    }

    @Test
    fun fallsBackToAllSizesWhenNoneIsFastEnough() {
        val onlySlow = listOf(size(3840, 2160, slow), size(1920, 1080, slow))
        assertEquals("3840x2160", choose(onlySlow, 60))
        assertNull(choose(emptyList(), 30))
    }
}
