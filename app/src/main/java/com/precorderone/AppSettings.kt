package com.precorderone

import android.content.Context
import android.os.Environment
import java.io.File

data class AppSettings(
    val loopSeconds: Int = 5,
    val storageDir: String = defaultStorageDir(),
    val cameraId: String? = null,
    val targetFps: Int = 30,
    val saveFps: Int = 30,
    val torchEnabled: Boolean = false,
    val zoomRatio: Float = 1f
) {
    companion object {
        fun defaultStorageDir(): String {
            return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Precorder-One").absolutePath
        }
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("precorder_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        loopSeconds = prefs.getInt("loopSeconds", 5),
        storageDir = prefs.getString("storageDir", AppSettings.defaultStorageDir()) ?: AppSettings.defaultStorageDir(),
        cameraId = prefs.getString("cameraId", null),
        targetFps = prefs.getInt("targetFps", 30),
        saveFps = prefs.getInt("saveFps", 30),
        torchEnabled = prefs.getBoolean("torchEnabled", false),
        zoomRatio = prefs.getFloat("zoomRatio", 1f)
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putInt("loopSeconds", settings.loopSeconds)
            .putString("storageDir", settings.storageDir)
            .putString("cameraId", settings.cameraId)
            .putInt("targetFps", settings.targetFps)
            .putInt("saveFps", settings.saveFps)
            .putBoolean("torchEnabled", settings.torchEnabled)
            .putFloat("zoomRatio", settings.zoomRatio)
            .apply()
    }
}
