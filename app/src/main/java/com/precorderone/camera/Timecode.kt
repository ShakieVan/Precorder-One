package com.precorderone.camera

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
 * Where the timecode box goes: at the bottom left of the picture as it is shown.
 *
 * A clip is stored the way the sensor delivers it (landscape) and only turned by [orientationHint] degrees clockwise
 * when it is played. The box is therefore laid out in the upright picture and each corner is mapped back into the
 * stored frame, so it ends up upright and bottom left whichever way the phone was held.
 *
 * Corners come as x/y pairs in OpenGL's -1..1 of the stored frame, in the order top left, top right, bottom left,
 * bottom right of the upright box.
 */
internal class TimecodeLayout(
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val orientationHint: Int,
    digitWidth: Int,
    colonWidth: Int,
    glyphHeight: Int
) {
    private val turned = orientationHint == 90 || orientationHint == 270
    val displayWidth = if (turned) frameHeight else frameWidth
    val displayHeight = if (turned) frameWidth else frameHeight

    val box = FloatArray(8)
    private val glyphs = FloatArray(Timecode.LENGTH * 8)

    init {
        val margin = (min(displayWidth, displayHeight) * 0.025f).roundToInt()
        val padX = (glyphHeight * 0.2f).roundToInt()
        val padY = (glyphHeight * 0.04f).roundToInt()
        val pattern = Timecode.glyphs(0L)
        val textWidth = pattern.sumOf { if (it == Timecode.COLON) colonWidth else digitWidth }
        val left = margin
        val bottom = displayHeight - margin
        val top = bottom - glyphHeight - 2 * padY
        corners(left, top, left + textWidth + 2 * padX, bottom, box, 0)
        var x = left + padX
        pattern.forEachIndexed { slot, glyph ->
            val width = if (glyph == Timecode.COLON) colonWidth else digitWidth
            corners(x, top + padY, x + width, top + padY + glyphHeight, glyphs, slot * 8)
            x += width
        }
    }

    /** Copies the corners of character [slot] (0 until [Timecode.LENGTH]) to [out] at [offset]. */
    fun glyph(slot: Int, out: FloatArray, offset: Int = 0) = glyphs.copyInto(out, offset, slot * 8, slot * 8 + 8)

    private fun corners(left: Int, top: Int, right: Int, bottom: Int, out: FloatArray, offset: Int) {
        toFrame(left, top, out, offset)
        toFrame(right, top, out, offset + 2)
        toFrame(left, bottom, out, offset + 4)
        toFrame(right, bottom, out, offset + 6)
    }

    /** Pixel of the upright picture (origin top left) -> position in the stored frame. */
    private fun toFrame(displayX: Int, displayY: Int, out: FloatArray, offset: Int) {
        val frameX: Int
        val frameY: Int
        when (orientationHint) {
            90 -> {
                frameX = displayY
                frameY = frameHeight - displayX
            }
            180 -> {
                frameX = frameWidth - displayX
                frameY = frameHeight - displayY
            }
            270 -> {
                frameX = frameWidth - displayY
                frameY = displayX
            }
            else -> {
                frameX = displayX
                frameY = displayY
            }
        }
        out[offset] = 2f * frameX / frameWidth - 1f
        out[offset + 1] = 1f - 2f * frameY / frameHeight
    }
}
