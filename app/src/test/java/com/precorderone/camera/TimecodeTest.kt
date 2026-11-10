package com.precorderone.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimecodeTest {
    @Test
    fun formatsMinutesSecondsMilliseconds() {
        assertEquals("00:00:000", Timecode.text(0L))
        assertEquals("00:00:017", Timecode.text(16_667L))
        assertEquals("00:09:999", Timecode.text(9_999_000L))
        assertEquals("00:59:999", Timecode.text(59_999_499L))
        assertEquals("01:00:000", Timecode.text(59_999_500L))
        assertEquals("01:01:042", Timecode.text(61_042_000L))
        assertEquals("12:34:567", Timecode.text(754_567_000L))
    }

    @Test
    fun neverLeavesItsNineCharacters() {
        assertEquals("00:00:000", Timecode.text(-5L))
        // Minutes wrap instead of growing a third digit.
        assertEquals("00:00:000", Timecode.text(100 * 60_000_000L))
    }

    /** A landscape recording, held upright: no turn, so the box sits bottom left of the stored frame itself. */
    @Test
    fun landscapeBoxIsBottomLeftOfTheFrame() {
        val layout = TimecodeLayout(1920, 1080, 0, digitWidth = 30, colonWidth = 18, glyphHeight = 60)
        assertEquals(1920, layout.displayWidth)
        val (left, top, right, bottom) = bounds(layout.box)
        // 27 px margin; OpenGL's y points up, so the bottom of the picture is -1.
        assertEquals(-1f + 2f * 27 / 1920, left, 1e-5f)
        assertEquals(-1f + 2f * 27 / 1080, bottom, 1e-5f)
        assertTrue(right < 0f && top < 0f)
        // Top left corner of the upright box comes first.
        assertEquals(left, layout.box[0], 1e-5f)
        assertEquals(top, layout.box[1], 1e-5f)
    }

    /**
     * Phone held in portrait, back camera: the frame is stored in landscape and turned 90° clockwise on playback.
     * Bottom left of the portrait picture is the bottom right of the stored frame, and the text runs up along its
     * right edge.
     */
    @Test
    fun portraitBoxIsTurnedIntoTheStoredFrame() {
        val layout = TimecodeLayout(1920, 1080, 90, digitWidth = 30, colonWidth = 18, glyphHeight = 60)
        assertEquals(1080, layout.displayWidth)
        assertEquals(1920, layout.displayHeight)
        val (left, top, right, bottom) = bounds(layout.box)
        assertEquals(1f - 2f * 27 / 1920, right, 1e-5f)
        assertEquals(-1f + 2f * 27 / 1080, bottom, 1e-5f)
        assertTrue(left > 0f && top < 0f)
        // Upright top left (start of the text, upper edge) lies at the bottom of the frame, away from its right edge.
        assertEquals(left, layout.box[0], 1e-5f)
        assertEquals(bottom, layout.box[1], 1e-5f)
        // Upright bottom right: further up in the frame, at its right edge.
        assertEquals(right, layout.box[6], 1e-5f)
        assertEquals(top, layout.box[7], 1e-5f)
    }

    @Test
    fun upsideDownAndFrontCameraTurns() {
        val upsideDown = bounds(TimecodeLayout(1920, 1080, 180, 30, 18, 60).box)
        // Bottom left of the shown picture = top right of the stored frame.
        assertEquals(1f - 2f * 27 / 1920, upsideDown[2], 1e-5f)
        assertEquals(1f - 2f * 27 / 1080, upsideDown[1], 1e-5f)

        val front = bounds(TimecodeLayout(1920, 1080, 270, 30, 18, 60).box)
        // 270° clockwise: bottom left of the shown picture = top left of the stored frame.
        assertEquals(-1f + 2f * 27 / 1920, front[0], 1e-5f)
        assertEquals(1f - 2f * 27 / 1080, front[1], 1e-5f)
    }

    @Test
    fun charactersStandSideBySideInsideTheBox() {
        val layout = TimecodeLayout(1280, 720, 0, digitWidth = 20, colonWidth = 12, glyphHeight = 40)
        val (boxLeft, boxTop, boxRight, boxBottom) = bounds(layout.box)
        var previousRight = boxLeft
        val corners = FloatArray(8)
        for (slot in 0 until Timecode.LENGTH) {
            layout.glyph(slot, corners)
            val (left, top, right, bottom) = bounds(corners)
            assertTrue("slot $slot starts at $left after $previousRight", left >= previousRight - 1e-5f)
            assertTrue(right <= boxRight && top <= boxTop + 1e-5f && bottom >= boxBottom - 1e-5f)
            val expectedWidth = if (slot == 2 || slot == 5) 12 else 20
            assertEquals(2f * expectedWidth / 1280, right - left, 1e-5f)
            assertEquals(2f * 40 / 720, top - bottom, 1e-5f)
            previousRight = right
        }
        assertArrayEquals(intArrayOf(0, 0, Timecode.COLON, 0, 0, Timecode.COLON, 0, 0, 0), Timecode.glyphs(0L))
    }

    /** left, top, right, bottom in OpenGL coordinates (top is the larger y). */
    private fun bounds(corners: FloatArray): List<Float> {
        val xs = corners.filterIndexed { index, _ -> index % 2 == 0 }
        val ys = corners.filterIndexed { index, _ -> index % 2 == 1 }
        return listOf(xs.min(), ys.max(), xs.max(), ys.min())
    }
}
