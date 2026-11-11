package com.precorderone.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipOverlayTest {
    @Test
    fun cornerIsTopLeftUnlessAnotherOneWasChosen() {
        assertEquals(Corner.TOP_LEFT, Corner.of(null))
        assertEquals(Corner.TOP_LEFT, Corner.of("somewhere"))
        Corner.values().forEach { assertEquals(it, Corner.of(it.key)) }
        assertEquals(Corner.BOTTOM_RIGHT, Corner.of("bottom_right"))
    }

    @Test
    fun speedLabelTakesAnUpperCornerWithoutTheTimecode() {
        assertEquals(Corner.TOP_RIGHT, ClipOverlay(Corner.TOP_LEFT, "Zeitlupe 8×").speedCorner)
        assertEquals(Corner.TOP_LEFT, ClipOverlay(Corner.TOP_RIGHT, "Zeitlupe 8×").speedCorner)
        assertEquals(Corner.TOP_RIGHT, ClipOverlay(Corner.BOTTOM_LEFT, "Zeitlupe 8×").speedCorner)
        assertEquals(Corner.TOP_RIGHT, ClipOverlay(Corner.BOTTOM_RIGHT, "Zeitlupe 8×").speedCorner)
        assertEquals(Corner.TOP_RIGHT, ClipOverlay(null, "Zeitlupe 8×").speedCorner)
    }

    @Test
    fun nothingToBurnWithoutTimecodeAndLabel() {
        assertTrue(ClipOverlay(null, null).isEmpty)
        assertTrue(ClipOverlay(null, " ").isEmpty)
        assertFalse(ClipOverlay(Corner.TOP_LEFT, null).isEmpty)
        assertFalse(ClipOverlay(null, "Zeitraffer 2×").isEmpty)
    }

    @Test
    fun factorShowsOnlyTheDecimalsItNeeds() {
        // Slow motion: recorded faster than played.
        assertEquals("8", SpeedLabel.factor(240, 30, ','))
        assertEquals("10", SpeedLabel.factor(240, 24, ','))
        assertEquals("2,5", SpeedLabel.factor(60, 24, ','))
        assertEquals("1,25", SpeedLabel.factor(30, 24, ','))
        assertEquals("3,33", SpeedLabel.factor(100, 30, ','))
        assertEquals("1,02", SpeedLabel.factor(61, 60, ','))
        // Time lapse: played faster than recorded; the factor is the same way round.
        assertEquals("2", SpeedLabel.factor(30, 60, ','))
        assertEquals("2.5", SpeedLabel.factor(24, 60, '.'))
    }

    @Test
    fun dotBlinksOncePerSecondOfPlayback() {
        assertTrue(SpeedLabel.dotVisible(0L))
        assertTrue(SpeedLabel.dotVisible(499_999L))
        assertFalse(SpeedLabel.dotVisible(500_000L))
        assertFalse(SpeedLabel.dotVisible(999_999L))
        assertTrue(SpeedLabel.dotVisible(1_000_000L))
        assertFalse(SpeedLabel.dotVisible(61_700_000L))
        assertTrue(SpeedLabel.dotVisible(-1L))
    }

    /** Landscape, top right: the box ends at the margin, with the dot in front of the text. */
    @Test
    fun labelBoxHoldsTheDotAndTheText() {
        val layout = SpeedLabelLayout(1920, 1080, 0, Corner.TOP_RIGHT, textWidth = 330, dotSize = 25, glyphHeight = 60)
        val (boxLeft, boxTop, boxRight, boxBottom) = bounds(layout.box)
        assertEquals(1f - 2f * 27 / 1920, boxRight, 1e-5f)
        assertEquals(1f - 2f * 27 / 1080, boxTop, 1e-5f)
        // 12 px at each side and between dot and text, 2 px above and below.
        assertEquals(2f * (12 + 25 + 12 + 330 + 12) / 1920, boxRight - boxLeft, 1e-5f)
        assertEquals(2f * 64 / 1080, boxTop - boxBottom, 1e-5f)

        val (dotLeft, dotTop, dotRight, dotBottom) = bounds(layout.dot)
        assertEquals(boxLeft + 2f * 12 / 1920, dotLeft, 1e-5f)
        assertEquals(2f * 25 / 1920, dotRight - dotLeft, 1e-5f)
        assertEquals(2f * 25 / 1080, dotTop - dotBottom, 1e-5f)
        // In the middle of the line, give or take the odd pixel.
        assertEquals((boxTop + boxBottom) / 2, (dotTop + dotBottom) / 2, 2f * 1 / 1080)

        val (textLeft, textTop, textRight, textBottom) = bounds(layout.text)
        assertEquals(dotRight + 2f * 12 / 1920, textLeft, 1e-5f)
        assertEquals(boxRight - 2f * 12 / 1920, textRight, 1e-5f)
        assertEquals(2f * 60 / 1080, textTop - textBottom, 1e-5f)
        // Upright: the first corner is the top left one.
        assertEquals(textLeft, layout.text[0], 1e-5f)
        assertEquals(textTop, layout.text[1], 1e-5f)
    }

    /**
     * Phone held in portrait, back camera: top right of the upright picture is the top left of the stored frame, and
     * the line runs up along the frame's left edge.
     */
    @Test
    fun labelIsTurnedIntoTheStoredFrameForPortraitClips() {
        val layout = SpeedLabelLayout(1920, 1080, 90, Corner.TOP_RIGHT, textWidth = 330, dotSize = 25, glyphHeight = 60)
        val (boxLeft, boxTop, boxRight, boxBottom) = bounds(layout.box)
        assertEquals(-1f + 2f * 27 / 1920, boxLeft, 1e-5f)
        assertEquals(1f - 2f * 27 / 1080, boxTop, 1e-5f)
        // The line is 391 px long and 64 px high; in the stored frame it stands on end.
        assertEquals(2f * 64 / 1920, boxRight - boxLeft, 1e-5f)
        assertEquals(2f * 391 / 1080, boxTop - boxBottom, 1e-5f)
        // The dot comes first when reading: at the lower end of the box in the stored frame, below the text.
        val dotTop = bounds(layout.dot)[1]
        val dotBottom = bounds(layout.dot)[3]
        val textBottom = bounds(layout.text)[3]
        assertTrue(dotBottom > boxBottom && dotTop < textBottom)
    }

    /** Timecode top left and label top right must not meet, even in the narrow portrait picture. */
    @Test
    fun timecodeAndLongestLabelFitSideBySideInPortrait() {
        // 1080 px wide upright picture: 54 px text, 33 px per character, as the phone's monospace font measures.
        val glyphHeight = 64
        val digitWidth = 33
        val timecode = TimecodeLayout(1920, 1080, 90, Corner.TOP_LEFT, digitWidth, 20, glyphHeight)
        val label = SpeedLabelLayout(
            1920, 1080, 90, Corner.TOP_RIGHT,
            textWidth = "Zeitraffer 1,25×".length * digitWidth, dotSize = SpeedLabel.dotSize(glyphHeight), glyphHeight = glyphHeight
        )
        // Upright x runs down the stored frame's y axis here: the timecode ends above the point where the label starts.
        val timecodeEnd = bounds(timecode.box)[1]
        val labelStart = bounds(label.box)[3]
        assertTrue("timecode ends at $timecodeEnd, label starts at $labelStart", timecodeEnd < labelStart)
    }

    /** left, top, right, bottom in OpenGL coordinates (top is the larger y). */
    private fun bounds(corners: FloatArray): List<Float> {
        val xs = corners.filterIndexed { index, _ -> index % 2 == 0 }
        val ys = corners.filterIndexed { index, _ -> index % 2 == 1 }
        return listOf(xs.min(), ys.max(), xs.max(), ys.min())
    }
}
