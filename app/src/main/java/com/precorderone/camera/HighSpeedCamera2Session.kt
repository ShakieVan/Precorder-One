package com.precorderone.camera

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs

internal class HighSpeedCamera2Session(
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
    private var digitalZoom = 1f
    @Volatile
    private var running = false

    fun start(profile: Profile, previewView: SurfaceView, encoderSurface: Surface, torch: Boolean, zoom: Float) {
        stop()
        running = true
        openRequested = false
        activeProfile = profile
        activeEncoderSurface = encoderSurface
        activePreviewView = previewView
        torchEnabled = torch
        digitalZoom = zoom.coerceAtLeast(1f)
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

    fun updateControls(torch: Boolean, zoom: Float) {
        torchEnabled = torch
        digitalZoom = zoom.coerceAtLeast(1f)
        val session = captureSession ?: return
        runCatching {
            session.stopRepeating()
            startBurst(session)
        }
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
        val thread = HandlerThread("precorder-hs-camera2").apply { start() }
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
                    if (running) onError("High-Speed-Kamera getrennt", null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    if (running) onError("High-Speed-Kamerafehler: $error", null)
                }
            }, handler)
        }.onFailure { onError("Kamera konnte nicht geoeffnet werden", it) }
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
            camera.createConstrainedHighSpeedCaptureSession(
                listOf(surface, encoderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startBurst(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (running) onError("High-Speed-Session konnte nicht konfiguriert werden", null)
                    }
                },
                handler
            )
        }.onFailure { onError("High-Speed-Session Start fehlgeschlagen", it) }
    }

    private fun startBurst(session: CameraCaptureSession) {
        val handler = cameraHandler ?: return
        val profile = activeProfile ?: return
        val device = cameraDevice ?: return
        val preview = previewSurface ?: return
        val encoder = activeEncoderSurface ?: return
        val hsSession = session as? CameraConstrainedHighSpeedCaptureSession ?: return

        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
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
            computeCrop(profile.characteristics, digitalZoom)?.let { crop ->
                set(CaptureRequest.SCALER_CROP_REGION, crop)
            }
        }.build()

        val burst = hsSession.createHighSpeedRequestList(request)
        session.setRepeatingBurst(burst, null, handler)
    }

    private fun computeCrop(chars: CameraCharacteristics, zoom: Float): Rect? {
        val ratio = zoom.coerceAtLeast(1f)
        if (ratio <= 1.01f) return null
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val width = (active.width() / ratio).toInt().coerceAtLeast(2)
        val height = (active.height() / ratio).toInt().coerceAtLeast(2)
        val left = active.left + (active.width() - width) / 2
        val top = active.top + (active.height() - height) / 2
        return Rect(left, top, left + width, top + height)
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
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) return null
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val ranges = map.highSpeedVideoFpsRanges.filter { targetFps in it.lower..it.upper }
            if (ranges.isEmpty()) return null
            val chosenRange = ranges.firstOrNull { it.lower == targetFps && it.upper == targetFps }
                ?: ranges.minByOrNull { abs(it.upper - targetFps) + abs(it.lower - targetFps) }
                ?: return null
            val sizes = map.getHighSpeedVideoSizesFor(chosenRange)
            if (sizes.isEmpty()) return null
            val targetRatio = if (aspect == "4:3") 4f / 3f else 16f / 9f
            val chosenSize = sizes.sortedWith(
                compareBy<Size> {
                    val ratio = it.width.toFloat() / it.height.toFloat()
                    abs(ratio - targetRatio)
                }.thenByDescending { it.width * it.height }
            ).first()
            return Profile(cameraId, chars, chosenSize, chosenRange)
        }
    }
}
