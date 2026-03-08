package com.precorderone.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs

internal class Camera2RecordSession(
    private val context: Context,
    private val onError: (String, Throwable?) -> Unit
) {
    data class Profile(
        val cameraId: String,
        val characteristics: CameraCharacteristics,
        val size: Size,
        val fpsRange: Range<Int>
    )

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var activeProfile: Profile? = null
    private var activeEncoderSurface: Surface? = null
    private var activePreviewView: SurfaceView? = null
    private var previewHolderCallback: SurfaceHolder.Callback? = null
    private var openRequested = false
    private var torchEnabled = false
    private var focusLockEnabled = false
    private var focusNormX = 0.5f
    private var focusNormY = 0.5f
    private var zoomRatio = 1f
    @Volatile
    private var running = false

    fun start(profile: Profile, previewView: SurfaceView, encoderSurface: Surface, torch: Boolean, initialZoomRatio: Float) {
        stop()
        running = true
        openRequested = false
        activeProfile = profile
        activeEncoderSurface = encoderSurface
        activePreviewView = previewView
        torchEnabled = torch
        zoomRatio = clampZoomRatio(profile, initialZoomRatio)
        ensureCameraThread()
        val holder = previewView.holder
        holder.setFixedSize(profile.size.width, profile.size.height)
        if (holder.surface?.isValid == true) {
            openCamera(previewView)
            return
        }
        previewHolderCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                holder.setFixedSize(profile.size.width, profile.size.height)
                openCamera(previewView)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                holder.setFixedSize(profile.size.width, profile.size.height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        }.also { holder.addCallback(it) }
    }

    fun updateControls(torch: Boolean) {
        torchEnabled = torch
        val session = captureSession ?: return
        val handler = cameraHandler ?: return
        val request = buildRequest(afTriggerStart = false) ?: return
        runCatching { session.setRepeatingRequest(request, null, handler) }
    }

    fun setZoomRatio(requestedZoomRatio: Float): Boolean {
        val profile = activeProfile ?: return false
        zoomRatio = clampZoomRatio(profile, requestedZoomRatio)
        val session = captureSession ?: return false
        val handler = cameraHandler ?: return false
        val request = buildRequest(afTriggerStart = false) ?: return false
        return runCatching {
            session.setRepeatingRequest(request, null, handler)
            true
        }.getOrDefault(false)
    }

    fun focusAt(normX: Float, normY: Float): Boolean {
        focusNormX = normX.coerceIn(0f, 1f)
        focusNormY = normY.coerceIn(0f, 1f)
        focusLockEnabled = true
        val session = captureSession ?: return false
        val handler = cameraHandler ?: return false
        val trigger = buildRequest(afTriggerStart = true) ?: return false
        return runCatching {
            session.capture(trigger, null, handler)
            val repeating = buildRequest(afTriggerStart = false) ?: return@runCatching false
            session.setRepeatingRequest(repeating, null, handler)
            true
        }.getOrDefault(false)
    }

    fun clearFocusLock(): Boolean {
        focusLockEnabled = false
        val session = captureSession ?: return false
        val handler = cameraHandler ?: return false
        val request = buildRequest(afTriggerStart = false) ?: return false
        return runCatching {
            session.setRepeatingRequest(request, null, handler)
            true
        }.getOrDefault(false)
    }

    fun stop() {
        running = false
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }
        captureSession = null
        previewSurface = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        activeEncoderSurface = null
        activeProfile = null
        openRequested = false
        activePreviewView?.holder?.let { holder ->
            previewHolderCallback?.let { cb -> runCatching { holder.removeCallback(cb) } }
        }
        previewHolderCallback = null
        activePreviewView = null
    }

    fun shutdown() {
        stop()
        cameraHandler = null
        cameraThread?.quitSafely()
        cameraThread = null
    }

    private fun ensureCameraThread() {
        if (cameraThread != null) return
        val thread = HandlerThread("precorder-camera2").apply { start() }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)
    }

    private fun openCamera(previewView: SurfaceView) {
        if (openRequested) return
        openRequested = true
        val handler = cameraHandler ?: return
        val profile = activeProfile ?: return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        runCatching {
            manager.openCamera(profile.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession(previewView, camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    if (running) onError("Camera2 getrennt", null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    if (running) onError("Camera2-Fehler: $error", null)
                }
            }, handler)
        }.onFailure { onError("Camera2 konnte nicht geoeffnet werden", it) }
    }

    private fun createSession(previewView: SurfaceView, camera: CameraDevice) {
        val handler = cameraHandler ?: return
        val profile = activeProfile ?: return
        val encoderSurface = activeEncoderSurface ?: return
        val holder = previewView.holder
        holder.setFixedSize(profile.size.width, profile.size.height)
        val surface = holder.surface ?: return
        if (!surface.isValid) return
        previewSurface = surface

        runCatching {
            camera.createCaptureSession(
                listOf(surface, encoderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val request = buildRequest(afTriggerStart = false) ?: return
                        session.setRepeatingRequest(request, null, handler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (running) onError("Camera2-Session konnte nicht konfiguriert werden", null)
                    }
                },
                handler
            )
        }.onFailure { onError("Camera2-Session Start fehlgeschlagen", it) }
    }

    private fun buildRequest(afTriggerStart: Boolean): CaptureRequest? {
        val profile = activeProfile ?: return null
        val device = cameraDevice ?: return null
        val preview = previewSurface ?: return null
        val encoder = activeEncoderSurface ?: return null
        return device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(preview)
            addTarget(encoder)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, profile.fpsRange)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            if (torchEnabled) {
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }

            val afRegions = meteringRectangles(profile.characteristics, focusNormX, focusNormY)
            if (focusLockEnabled && afRegions != null) {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_REGIONS, afRegions)
                set(CaptureRequest.CONTROL_AE_REGIONS, afRegions)
                set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    if (afTriggerStart) CaptureRequest.CONTROL_AF_TRIGGER_START else CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                )
            } else {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                set(CaptureRequest.CONTROL_ZOOM_RATIO, clampZoomRatio(profile, zoomRatio))
            }
        }.build()
    }

    private fun clampZoomRatio(profile: Profile, requestedZoomRatio: Float): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 1f
        val range = profile.characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: return 1f
        return requestedZoomRatio.coerceIn(range.lower, range.upper)
    }

    private fun meteringRectangles(chars: CameraCharacteristics, normX: Float, normY: Float): Array<MeteringRectangle>? {
        val maxAf = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        if (maxAf <= 0) return null
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val x = active.left + (active.width() * normX).toInt()
        val y = active.top + (active.height() * normY).toInt()
        val boxW = (active.width() * 0.15f).toInt().coerceAtLeast(80)
        val boxH = (active.height() * 0.15f).toInt().coerceAtLeast(80)
        val left = (x - boxW / 2).coerceIn(active.left, active.right - boxW)
        val top = (y - boxH / 2).coerceIn(active.top, active.bottom - boxH)
        return arrayOf(MeteringRectangle(left, top, boxW, boxH, MeteringRectangle.METERING_WEIGHT_MAX))
    }

    companion object {
        fun chooseProfile(context: Context, targetFps: Int, lensFacing: Int, preferredCameraId: String?, aspect: String): Profile? {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = preferredCameraId ?: manager.cameraIdList.firstOrNull { id ->
                val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return@firstOrNull false
                val lens = chars.get(CameraCharacteristics.LENS_FACING)
                lens == if (lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            } ?: return null

            val chars = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull() ?: return null
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val sizes = map.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
            if (sizes.isEmpty()) return null

            val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            if (fpsRanges.isEmpty()) return null
            val chosenRange = fpsRanges.firstOrNull { it.lower == targetFps && it.upper == targetFps }
                ?: fpsRanges.filter { targetFps in it.lower..it.upper }.minByOrNull { abs(it.upper - targetFps) + abs(it.lower - targetFps) }
                ?: fpsRanges.maxByOrNull { it.upper }
                ?: return null

            val targetRatio = if (aspect == "4:3") 4f / 3f else 16f / 9f
            val frameDurationBudgetNs = 1_000_000_000L / targetFps.coerceAtLeast(1)

            // Prefer sizes that can realistically sustain target fps according to camera metadata.
            val fpsSafeSizes = sizes.filter { size ->
                val minDuration = runCatching {
                    map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
                }.getOrDefault(0L)
                minDuration <= 0L || minDuration <= frameDurationBudgetNs
            }

            val candidateSizes = if (fpsSafeSizes.isNotEmpty()) fpsSafeSizes else sizes
            val chosenSize = candidateSizes.sortedWith(
                compareBy<Size> {
                    val ratio = it.width.toFloat() / it.height.toFloat()
                    abs(ratio - targetRatio)
                }.thenBy { size ->
                    // For high fps, prioritize smaller resolutions first to reduce ISP/encoder pressure.
                    if (targetFps >= 60) size.width * size.height else -(size.width * size.height)
                }
            ).first()
            return Profile(cameraId, chars, chosenSize, chosenRange)
        }
    }
}
