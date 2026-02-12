package com.precorderone.data

data class PrecorderSettings(
    val loopSeconds: Int = 5,
    val targetFps: Int = 60,
    val playbackFps: Int = 30,
    val outputFolderUri: String? = null,
    val lensFacing: Int = androidx.camera.core.CameraSelector.LENS_FACING_BACK,
    val cameraId: String? = null,
    val torchEnabled: Boolean = false,
    val digitalZoomRatio: Float = 1f,
    val analogZoomRatio: Float = 1f,
    val triggerWithVolumeDown: Boolean = true
)
