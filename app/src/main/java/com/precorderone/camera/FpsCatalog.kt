package com.precorderone.camera

import android.hardware.camera2.CameraCharacteristics
import android.util.Range

/** Recording frame rates a camera offers; shared by the settings screen and the quick settings sheet. */
internal object FpsCatalog {
    private val CANDIDATES = listOf(24, 25, 30, 48, 50, 60, 90, 100, 120, 144, 240)

    /** Normal rates up to 60 fps plus the camera's high-speed rates, sorted. */
    fun supported(chars: CameraCharacteristics): List<Int> =
        (normal(chars) + highSpeed(chars))
            .filter { it >= 24 }
            .distinct()
            .sorted()
            .ifEmpty { listOf(30, 60) }

    /** Rates reachable through the AE target ranges of a normal capture session. */
    fun normal(chars: CameraCharacteristics): List<Int> {
        val values = mutableSetOf<Int>()
        chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.forEach { range -> values.addAll(valuesFromAeRange(range)) }
        return values.toList()
    }

    /** Rates of constrained high-speed sessions (120/240 fps). */
    fun highSpeed(chars: CameraCharacteristics): List<Int> {
        val values = mutableSetOf<Int>()
        chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.highSpeedVideoFpsRanges?.forEach { range ->
            values.add(range.upper)
            values.add(range.lower)
        }
        return values.toList()
    }

    private fun valuesFromAeRange(range: Range<Int>): List<Int> {
        val values = mutableListOf<Int>()
        CANDIDATES.forEach { candidate ->
            if (candidate <= 60 && candidate in range.lower..range.upper) values.add(candidate)
            if (candidate > 60 && range.lower == candidate && range.upper == candidate) values.add(candidate)
        }
        if (range.upper <= 60 && range.upper !in values) values.add(range.upper)
        return values
    }
}
