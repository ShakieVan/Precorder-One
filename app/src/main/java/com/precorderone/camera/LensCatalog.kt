package com.precorderone.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * One button in the lens bar. A lens is either its own camera ID (Samsung exposes the ultra-wide as camera "2") or a
 * zoom ratio on a logical multi-camera whose HAL switches the physical lens itself (Pixel, newer flagships).
 *
 * [factor] is the field of view relative to the default camera of that facing, as the system camera labels it
 * (0.5x ultra-wide, 1x main, 3x tele).
 */
data class LensOption(val cameraId: String, val zoomRatio: Float, val factor: Float) {
    val label: String
        get() = when {
            factor < 0.95f -> String.format(Locale.ROOT, "%.1fx", factor)
            abs(factor - factor.roundToInt()) < 0.15f -> "${factor.roundToInt()}x"
            else -> String.format(Locale.ROOT, "%.1fx", factor)
        }
}

/** Lenses of one facing plus the 1x-relative factor of every camera ID of that facing. */
internal class LensSet(val options: List<LensOption>, private val cameraFactors: Map<String, Float>) {
    /** Lens in use: the option closest to the field of view of [cameraId] at [zoomRatio]. */
    fun current(cameraId: String?, zoomRatio: Float): LensOption? {
        val factor = (cameraFactors[cameraId ?: return null] ?: return null) * zoomRatio
        return options.minByOrNull { abs(it.factor - factor) }
            ?.takeIf { abs(it.factor - factor) / factor < LensCatalog.SAME_LENS_TOLERANCE }
    }

    companion object {
        val EMPTY = LensSet(emptyList(), emptyMap())
    }
}

internal object LensCatalog {
    // Two sources closer than this describe the same lens (e.g. the ultra-wide as own ID and as zoom ratio 0.6).
    const val SAME_LENS_TOLERANCE = 0.08f
    private const val MIN_TELE_FACTOR = 1.2f

    /** Native lenses with the same facing as [activeCameraId], sorted from wide to tele. Digital-only steps are left out. */
    fun lenses(manager: CameraManager, activeCameraId: String): LensSet {
        val chars = mutableMapOf<String, CameraCharacteristics?>()
        fun charsOf(id: String) = chars.getOrPut(id) { runCatching { manager.getCameraCharacteristics(id) }.getOrNull() }

        val facing = charsOf(activeCameraId)?.get(CameraCharacteristics.LENS_FACING) ?: return LensSet.EMPTY
        val sameFacing = manager.cameraIdList.filter { charsOf(it)?.get(CameraCharacteristics.LENS_FACING) == facing }
        // The first camera of a facing is the default one ("0" for back on nearly every phone): it defines 1x.
        val reference = sameFacing.firstOrNull()?.let { fovMetric(charsOf(it)) } ?: return LensSet.EMPTY

        val candidates = mutableListOf<LensOption>()
        val cameraFactors = mutableMapOf<String, Float>()
        for (id in sameFacing) {
            val c = charsOf(id) ?: continue
            val idFactor = (fovMetric(c) ?: continue) / reference
            cameraFactors[id] = idFactor
            candidates += LensOption(id, 1f, idFactor)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) continue
            val range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: continue
            // A zoom range below 1x means the HAL switches to the ultra-wide on its own.
            if (range.lower < 0.95f) candidates += LensOption(id, range.lower, idFactor * range.lower)
            // Tele lenses behind a logical multi-camera, reached through CONTROL_ZOOM_RATIO.
            val ownMetric = fovMetric(c) ?: continue
            for (physicalId in c.physicalCameraIds) {
                val ratio = (fovMetric(charsOf(physicalId)) ?: continue) / ownMetric
                if (ratio >= MIN_TELE_FACTOR && ratio <= range.upper) {
                    candidates += LensOption(id, ratio, idFactor * ratio)
                }
            }
        }

        // Several sources for one lens: prefer the active camera (no reopen), then the default camera.
        val preferred = candidates.sortedWith(
            compareByDescending<LensOption> { it.cameraId == activeCameraId }
                .thenByDescending { it.cameraId == sameFacing.first() }
                .thenBy { it.cameraId }
        )
        val result = mutableListOf<LensOption>()
        for (option in preferred) {
            if (result.none { abs(it.factor - option.factor) / option.factor < SAME_LENS_TOLERANCE }) result += option
        }
        return LensSet(result.sortedBy { it.factor }, cameraFactors)
    }

    /** Field of view measure: focal length per sensor diagonal (bigger = narrower view). */
    private fun fovMetric(chars: CameraCharacteristics?): Float? {
        val focal = chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
        val size = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val diagonal = hypot(size.width, size.height)
        if (focal <= 0f || diagonal <= 0f) return null
        return focal / diagonal
    }
}
