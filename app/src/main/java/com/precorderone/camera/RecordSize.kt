package com.precorderone.camera

import kotlin.math.abs

/**
 * Picture size of the normal recording session: the biggest one in the wanted shape that the camera delivers at the
 * frame rate. A small picture is never a goal of its own; the camera's own figures say what it can keep up with.
 */
internal object RecordSize {
    /** [minFrameDurationNs]: shortest time between two pictures of this size, 0 when the camera does not say. */
    class Candidate(val width: Int, val height: Int, val minFrameDurationNs: Long)

    // From this rate on the encoder is asked as well: a camera that delivers 4K at 60 fps is of no use when the
    // encoder cannot keep up. Below it the biggest size the camera manages has always been taken and works.
    const val ASK_ENCODER_FROM_FPS = 60

    fun choose(
        candidates: List<Candidate>,
        targetRatio: Float,
        fps: Int,
        encoderManages: (Candidate) -> Boolean
    ): Candidate? {
        val frameNs = 1_000_000_000L / fps.coerceAtLeast(1)
        // 1 % slack: cameras round the duration, e.g. to 16 666 667 ns for 60 fps.
        val inTime = candidates.filter { it.minFrameDurationNs <= frameNs + frameNs / 100 }.ifEmpty { candidates }
        val usable = if (fps >= ASK_ENCODER_FROM_FPS) inTime.filter(encoderManages).ifEmpty { inTime } else inTime
        return usable.sortedWith(
            compareBy<Candidate> { abs(it.width.toFloat() / it.height - targetRatio) }
                .thenByDescending { it.width * it.height }
        ).firstOrNull()
    }
}
