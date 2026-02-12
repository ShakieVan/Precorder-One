package com.precorderone.data

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.preference.PreferenceManager

class SettingsRepository(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    fun load(): PrecorderSettings = PrecorderSettings(
        loopSeconds = prefs.getString(KEY_LOOP_SECONDS, "5")?.toIntOrNull()?.coerceIn(5, 10) ?: 5,
        targetFps = prefs.getString(KEY_TARGET_FPS, "60")?.toIntOrNull()?.coerceIn(24, 240) ?: 60,
        playbackFps = prefs.getString(KEY_PLAYBACK_FPS, "30")?.toIntOrNull()?.coerceIn(24, 120) ?: 30,
        outputFolderUri = prefs.getString(KEY_OUTPUT_URI, null),
        lensFacing = prefs.getString(KEY_LENS_FACING, "back")
            ?.let { if (it == "front") CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK }
            ?: CameraSelector.LENS_FACING_BACK,
        cameraId = prefs.getString(KEY_CAMERA_ID, null),
        torchEnabled = prefs.getBoolean(KEY_TORCH, false),
        digitalZoomRatio = prefs.getInt(KEY_DIGITAL_ZOOM, 1).toFloat().coerceAtLeast(1f),
        analogZoomRatio = prefs.getInt(KEY_ANALOG_ZOOM, 1).toFloat().coerceAtLeast(1f),
        triggerWithVolumeDown = prefs.getBoolean(KEY_VOLUME_TRIGGER, true)
    )

    fun save(settings: PrecorderSettings) {
        prefs.edit()
            .putString(KEY_LOOP_SECONDS, settings.loopSeconds.toString())
            .putString(KEY_TARGET_FPS, settings.targetFps.toString())
            .putString(KEY_PLAYBACK_FPS, settings.playbackFps.toString())
            .putString(KEY_OUTPUT_URI, settings.outputFolderUri)
            .putString(KEY_LENS_FACING, if (settings.lensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "back")
            .putString(KEY_CAMERA_ID, settings.cameraId)
            .putBoolean(KEY_TORCH, settings.torchEnabled)
            .putInt(KEY_DIGITAL_ZOOM, settings.digitalZoomRatio.toInt())
            .putInt(KEY_ANALOG_ZOOM, settings.analogZoomRatio.toInt())
            .putBoolean(KEY_VOLUME_TRIGGER, settings.triggerWithVolumeDown)
            .apply()
    }

    companion object {
        const val KEY_LOOP_SECONDS = "loop_seconds"
        const val KEY_TARGET_FPS = "target_fps"
        const val KEY_PLAYBACK_FPS = "playback_fps"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_LENS_FACING = "lens_facing"
        const val KEY_CAMERA_ID = "camera_id"
        const val KEY_TORCH = "torch"
        const val KEY_DIGITAL_ZOOM = "digital_zoom"
        const val KEY_ANALOG_ZOOM = "analog_zoom"
        const val KEY_VOLUME_TRIGGER = "volume_trigger"
    }
}
