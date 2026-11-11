package com.precorderone.camera

import android.hardware.camera2.CameraCharacteristics

/** Recording frame rates a camera offers; shared by the settings screen, the quick settings sheet and the camera start. */
internal object FpsCatalog {
    private val CANDIDATES = listOf(24, 25, 30, 48, 50, 60, 90, 100, 120, 144, 240)

    /** Normal rates up to 60 fps plus the camera's high-speed rates, sorted. */
    fun supported(chars: CameraCharacteristics): List<Int> = supported(normal(chars), highSpeed(chars))

    fun supported(normal: List<Int>, highSpeed: List<Int>): List<Int> =
        (normal + highSpeed)
            .filter { it >= 24 }
            .distinct()
            .sorted()
            .ifEmpty { listOf(30, 60) }

    /**
     * The rate to record at when [wanted] is set: [wanted] where the camera offers it, otherwise the next lower rate
     * (the lowest one when there is none below). The preset of 60 fps is such a case on many phones.
     */
    fun closest(chars: CameraCharacteristics, wanted: Int): Int = closest(supported(chars), wanted)

    fun closest(offered: List<Int>, wanted: Int): Int =
        if (wanted in offered) wanted else offered.filter { it < wanted }.maxOrNull() ?: offered.minOrNull() ?: wanted

    /** Rates reachable through the AE target ranges of a normal capture session. */
    fun normal(chars: CameraCharacteristics): List<Int> =
        normal(chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().map { it.lower to it.upper })

    /** [aeRanges]: lowest and highest rate of each range. */
    fun normal(aeRanges: List<Pair<Int, Int>>): List<Int> {
        val values = mutableSetOf<Int>()
        aeRanges.forEach { (lower, upper) ->
            CANDIDATES.forEach { candidate ->
                if (candidate <= 60 && candidate in lower..upper) values.add(candidate)
                if (candidate > 60 && lower == candidate && upper == candidate) values.add(candidate)
            }
            if (upper <= 60) values.add(upper)
        }
        return values.toList()
    }

    /** Rates of constrained high-speed sessions (120/240 fps). */
    fun highSpeed(chars: CameraCharacteristics): List<Int> =
        highSpeed(
            chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.highSpeedVideoFpsRanges.orEmpty()
                .map { it.lower to it.upper }
        )

    /**
     * Only the upper end of a range is a rate to record at. The lower end (60 in 60-120) is how far the camera may
     * slow down in the dark: listing it offered 60 fps on phones that cannot record at 60 at all.
     */
    fun highSpeed(ranges: List<Pair<Int, Int>>): List<Int> = ranges.map { it.second }.distinct()
}
