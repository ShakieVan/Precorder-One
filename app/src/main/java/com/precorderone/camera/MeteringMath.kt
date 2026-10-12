package com.precorderone.camera

import android.graphics.PointF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.MeteringRectangle
import android.util.Size

/** Maps taps on the preview to 3A regions of the sensor. */
internal object MeteringMath {
    private const val REGION_FRACTION = 0.15f

    /**
     * Upright preview coordinates (0..1, portrait screen) to sensor coordinates (0..1, in sensor orientation).
     * The preview is the sensor image rotated by SENSOR_ORIENTATION and, for front cameras, mirrored like a mirror.
     */
    fun displayToSensor(displayX: Float, displayY: Float, chars: CameraCharacteristics): PointF {
        val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val x = (if (front) 1f - displayX else displayX).coerceIn(0f, 1f)
        val y = displayY.coerceIn(0f, 1f)
        return when (chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) {
            90 -> PointF(y, 1f - x)
            180 -> PointF(1f - x, 1f - y)
            270 -> PointF(1f - y, x)
            else -> PointF(x, y)
        }
    }

    /**
     * Metering region around sensor point ([u], [v]) of an output stream of [streamSize].
     *
     * The stream shows a centered crop of the active array with its own aspect ratio (16:9 out of a 4:3 sensor).
     * With CONTROL_ZOOM_RATIO the region coordinates refer to the zoomed field of view, so the same mapping holds
     * at 5x as at 1x.
     */
    fun meteringRegion(chars: CameraCharacteristics, streamSize: Size, u: Float, v: Float): Array<MeteringRectangle>? {
        val maxAf = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        if (maxAf <= 0) return null
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val streamAspect = streamSize.width.toFloat() / streamSize.height
        val activeAspect = active.width().toFloat() / active.height()
        val cropW = if (streamAspect > activeAspect) active.width().toFloat() else active.height() * streamAspect
        val cropH = if (streamAspect > activeAspect) active.width() / streamAspect else active.height().toFloat()
        val x = active.left + (active.width() - cropW) / 2f + u.coerceIn(0f, 1f) * cropW
        val y = active.top + (active.height() - cropH) / 2f + v.coerceIn(0f, 1f) * cropH
        val boxW = (cropW * REGION_FRACTION).toInt().coerceAtLeast(80)
        val boxH = (cropH * REGION_FRACTION).toInt().coerceAtLeast(80)
        val left = (x.toInt() - boxW / 2).coerceIn(active.left, active.right - boxW)
        val top = (y.toInt() - boxH / 2).coerceIn(active.top, active.bottom - boxH)
        return arrayOf(MeteringRectangle(left, top, boxW, boxH, MeteringRectangle.METERING_WEIGHT_MAX))
    }

    /** Width / height of the preview as seen upright on a portrait screen. */
    fun displayAspect(chars: CameraCharacteristics, streamSize: Size): Float {
        val rotated = (chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) % 180 != 0
        return if (rotated) streamSize.height.toFloat() / streamSize.width else streamSize.width.toFloat() / streamSize.height
    }
}
