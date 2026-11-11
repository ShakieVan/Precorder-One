package com.precorderone.camera

import kotlin.math.max
import kotlin.math.min

/** Text of the burnt-in timecode. Free of Android classes, so it is tested on the PC. */
internal object Timecode {
    /** "MM:SS:mmm" */
    const val LENGTH = 9

    /** Glyph index of the colon; 0..9 are the digits. */
    const val COLON = 10

    /**
     * [clipUs] of recording time since the first frame as glyphs of "MM:SS:mmm", rounded to the millisecond.
     * It is the time that really passed in front of the camera, so in slow motion it runs slower than the playback.
     */
    fun glyphs(clipUs: Long, out: IntArray = IntArray(LENGTH)): IntArray {
        val totalMs = (clipUs.coerceAtLeast(0L) + 500L) / 1000L
        val millis = (totalMs % 1000L).toInt()
        val seconds = (totalMs / 1000L % 60L).toInt()
        val minutes = (totalMs / 60_000L % 100L).toInt()
        out[0] = minutes / 10
        out[1] = minutes % 10
        out[2] = COLON
        out[3] = seconds / 10
        out[4] = seconds % 10
        out[5] = COLON
        out[6] = millis / 100
        out[7] = millis / 10 % 10
        out[8] = millis % 10
        return out
    }

    fun text(clipUs: Long): String = glyphs(clipUs).joinToString("") { if (it == COLON) ":" else it.toString() }

    /** Text size in pixels for a picture shown at [displayWidth] x [displayHeight]: readable, but out of the way. */
    fun textSize(displayWidth: Int, displayHeight: Int): Float = max(12f, min(displayWidth, displayHeight) * 0.05f)
}

/**
 * Where the timecode goes: a box in [corner] of the picture as it is shown, upright whichever way the phone was held.
 * [box] and [glyph] hold corners as [FramePlacement] gives them.
 */
internal class TimecodeLayout(
    frameWidth: Int,
    frameHeight: Int,
    orientationHint: Int,
    corner: Corner,
    digitWidth: Int,
    colonWidth: Int,
    glyphHeight: Int
) {
    private val placement = FramePlacement(frameWidth, frameHeight, orientationHint)
    val displayWidth get() = placement.displayWidth
    val displayHeight get() = placement.displayHeight

    val box = FloatArray(8)
    private val glyphs = FloatArray(Timecode.LENGTH * 8)

    init {
        val padX = FramePlacement.padX(glyphHeight)
        val padY = FramePlacement.padY(glyphHeight)
        val pattern = Timecode.glyphs(0L)
        val boxWidth = pattern.sumOf { if (it == Timecode.COLON) colonWidth else digitWidth } + 2 * padX
        val boxHeight = glyphHeight + 2 * padY
        val left = placement.left(corner, boxWidth)
        val top = placement.top(corner, boxHeight)
        placement.corners(left, top, left + boxWidth, top + boxHeight, box)
        var x = left + padX
        pattern.forEachIndexed { slot, glyph ->
            val width = if (glyph == Timecode.COLON) colonWidth else digitWidth
            placement.corners(x, top + padY, x + width, top + padY + glyphHeight, glyphs, slot * 8)
            x += width
        }
    }

    /** Copies the corners of character [slot] (0 until [Timecode.LENGTH]) to [out] at [offset]. */
    fun glyph(slot: Int, out: FloatArray, offset: Int = 0) = glyphs.copyInto(out, offset, slot * 8, slot * 8 + 8)
}
