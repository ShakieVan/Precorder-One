package com.precorderone.camera

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A corner of the picture as it is shown. [key] is what the settings store. */
enum class Corner(val key: String, internal val top: Boolean, internal val left: Boolean) {
    TOP_LEFT("top_left", top = true, left = true),
    TOP_RIGHT("top_right", top = true, left = false),
    BOTTOM_LEFT("bottom_left", top = false, left = true),
    BOTTOM_RIGHT("bottom_right", top = false, left = false);

    companion object {
        /** Top left unless another corner was chosen. */
        fun of(key: String?): Corner = values().firstOrNull { it.key == key } ?: TOP_LEFT
    }
}

/**
 * What is burnt into a saved clip: the timecode in the corner [timecode] (null: none) and the speed label, e.g.
 * "Zeitlupe 8×" (null: none, as for every clip that plays at the speed it was recorded at).
 */
class ClipOverlay(val timecode: Corner?, speedLabel: String?) {
    val speedLabel: String? = speedLabel?.takeIf { it.isNotBlank() }

    val isEmpty: Boolean get() = timecode == null && speedLabel == null

    /** The speed label sits in an upper corner: top right, or top left when the timecode is top right. */
    val speedCorner: Corner get() = if (timecode == Corner.TOP_RIGHT) Corner.TOP_LEFT else Corner.TOP_RIGHT
}

/** The note that a clip does not play at the speed it was recorded at. Free of Android classes, tested on the PC. */
internal object SpeedLabel {
    /** The dot is shown for half a second and hidden for half a second, in the time of the clip as it plays. */
    private const val BLINK_HALF_PERIOD_US = 500_000L

    fun dotVisible(playbackUs: Long): Boolean = playbackUs.coerceAtLeast(0L) / BLINK_HALF_PERIOD_US % 2L == 0L

    /**
     * How many times slower or faster than recorded the clip plays, for "Zeitlupe 8×" or "Zeitraffer 2,5×":
     * two decimals at most, and none that are not needed.
     */
    fun factor(recordFps: Int, playbackFps: Int, decimalSeparator: Char): String {
        val faster = max(recordFps, playbackFps).coerceAtLeast(1)
        val slower = min(recordFps, playbackFps).coerceAtLeast(1)
        val hundredths = (faster * 100.0 / slower).roundToInt()
        val whole = hundredths / 100
        val fraction = hundredths % 100
        return when {
            fraction == 0 -> "$whole"
            fraction % 10 == 0 -> "$whole$decimalSeparator${fraction / 10}"
            else -> "$whole$decimalSeparator${fraction.toString().padStart(2, '0')}"
        }
    }

    /** Diameter of the dot next to text that is [glyphHeight] high. */
    fun dotSize(glyphHeight: Int): Int = (glyphHeight * 0.42f).roundToInt()
}

/**
 * Places boxes in the picture as it is shown and tells where they are in the stored frame.
 *
 * A clip is stored the way the sensor delivers it (landscape) and only turned by [orientationHint] degrees clockwise
 * when it is played. A box is therefore laid out in the upright picture and each of its corners is mapped back into
 * the stored frame, so it ends up upright and in its corner whichever way the phone was held.
 *
 * Corners come as x/y pairs in OpenGL's -1..1 of the stored frame, in the order top left, top right, bottom left,
 * bottom right of the upright box.
 */
internal class FramePlacement(private val frameWidth: Int, private val frameHeight: Int, private val orientationHint: Int) {
    private val turned = orientationHint == 90 || orientationHint == 270
    val displayWidth = if (turned) frameHeight else frameWidth
    val displayHeight = if (turned) frameWidth else frameHeight
    private val margin = (min(displayWidth, displayHeight) * 0.025f).roundToInt()

    /** Left edge of a box [boxWidth] wide that sits in [corner] of the upright picture. */
    fun left(corner: Corner, boxWidth: Int): Int = if (corner.left) margin else displayWidth - margin - boxWidth

    /** Upper edge of a box [boxHeight] high that sits in [corner] of the upright picture. */
    fun top(corner: Corner, boxHeight: Int): Int = if (corner.top) margin else displayHeight - margin - boxHeight

    /** The rectangle given in pixels of the upright picture (origin top left) as corners in the stored frame. */
    fun corners(left: Int, top: Int, right: Int, bottom: Int, out: FloatArray, offset: Int = 0) {
        toFrame(left, top, out, offset)
        toFrame(right, top, out, offset + 2)
        toFrame(left, bottom, out, offset + 4)
        toFrame(right, bottom, out, offset + 6)
    }

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

    companion object {
        /** Space between the edge of a box and its text, for text that is [glyphHeight] high. */
        fun padX(glyphHeight: Int): Int = (glyphHeight * 0.2f).roundToInt()
        fun padY(glyphHeight: Int): Int = (glyphHeight * 0.04f).roundToInt()
    }
}

/**
 * Where the speed label goes: a box in [corner] of the picture as it is shown, with the blinking dot ([dotSize]
 * across) in front of the text ([textWidth] wide). [box], [dot] and [text] hold corners as [FramePlacement] gives them.
 */
internal class SpeedLabelLayout(
    frameWidth: Int,
    frameHeight: Int,
    orientationHint: Int,
    corner: Corner,
    textWidth: Int,
    dotSize: Int,
    glyphHeight: Int
) {
    val box = FloatArray(8)
    val dot = FloatArray(8)
    val text = FloatArray(8)

    init {
        val placement = FramePlacement(frameWidth, frameHeight, orientationHint)
        val padX = FramePlacement.padX(glyphHeight)
        val padY = FramePlacement.padY(glyphHeight)
        val gap = padX
        val boxWidth = padX + dotSize + gap + textWidth + padX
        val boxHeight = glyphHeight + 2 * padY
        val left = placement.left(corner, boxWidth)
        val top = placement.top(corner, boxHeight)
        placement.corners(left, top, left + boxWidth, top + boxHeight, box)
        val dotLeft = left + padX
        val dotTop = top + padY + (glyphHeight - dotSize) / 2
        placement.corners(dotLeft, dotTop, dotLeft + dotSize, dotTop + dotSize, dot)
        val textLeft = dotLeft + dotSize + gap
        placement.corners(textLeft, top + padY, textLeft + textWidth, top + padY + glyphHeight, text)
    }
}
