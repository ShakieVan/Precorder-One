package com.precorderone.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
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
    private var focusLockEnabled = false
    private var focusNormX = 0.5f
    private var focusNormY = 0.5f
    private var zoomRatio = 1f
    private var exposureIndex = 0
    @Volatile
    private var running = false
    // Bumped by stop(): a camera opened for an earlier start() gets closed instead of used.
    @Volatile
    private var generation = 0
    @Volatile
    private var openAttempts = 0

    fun start(profile: Profile, previewView: SurfaceView, encoderSurface: Surface, torch: Boolean, initialZoomRatio: Float, exposureIndex: Int) {
        stop()
        running = true
        openRequested = false
        openAttempts = 0
        activeProfile = profile
        activeEncoderSurface = encoderSurface
        activePreviewView = previewView
        torchEnabled = torch
        this.exposureIndex = exposureIndex
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
        runCatching {
            session.stopRepeating()
            startBurst(session)
        }
    }

    fun setZoomRatio(requestedZoomRatio: Float): Boolean {
        val profile = activeProfile ?: return false
        zoomRatio = clampZoomRatio(profile, requestedZoomRatio)
        val session = captureSession ?: return false
        return runCatching {
            session.stopRepeating()
            startBurst(session)
            true
        }.getOrDefault(false)
    }

    fun setExposureCompensation(index: Int): Boolean {
        exposureIndex = index
        val session = captureSession ?: return false
        return runCatching {
            session.stopRepeating()
            startBurst(session)
            true
        }.getOrDefault(false)
    }
    /** [normX]/[normY] are sensor coordinates (0..1), see MeteringMath.displayToSensor. */
    fun focusAt(normX: Float, normY: Float): Boolean {
        focusNormX = normX.coerceIn(0f, 1f)
        focusNormY = normY.coerceIn(0f, 1f)
        focusLockEnabled = true
        val session = captureSession ?: return false
        val handler = cameraHandler ?: return false
        val hsSession = session as? CameraConstrainedHighSpeedCaptureSession ?: return false
        val trigger = buildRequest(afTriggerStart = true) ?: return false
        return runCatching {
            val burst = hsSession.createHighSpeedRequestList(trigger)
            session.captureBurst(burst, null, handler)
            session.stopRepeating()
            startBurst(session)
            true
        }.getOrDefault(false)
    }

    fun clearFocusLock(): Boolean {
        focusLockEnabled = false
        val session = captureSession ?: return false
        return runCatching {
            session.stopRepeating()
            startBurst(session)
            true
        }.getOrDefault(false)
    }

    fun stop() {
        running = false
        generation++
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }
        captureSession = null
        previewSurface = null
        cameraDevice?.let { CameraCloseGate.close(it) }
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
        val gen = generation
        // Open only once a camera closed before (lens switch, other pipeline) is really released.
        CameraCloseGate.whenClear(handler) {
            if (gen == generation && running) openCameraNow(previewView, profile, handler, gen)
        }
    }

    private fun openCameraNow(previewView: SurfaceView, profile: Profile, handler: Handler, gen: Int) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        runCatching {
            manager.openCamera(profile.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    // stop() ran while this camera was opening: it belongs to no session, so release it.
                    if (gen != generation) {
                        CameraCloseGate.close(camera)
                        return
                    }
                    cameraDevice = camera
                    createSession(previewView, camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    CameraCloseGate.close(camera)
                    if (gen != generation) return
                    cameraDevice = null
                    if (running && !retryOpen(previewView)) onError("High-Speed-Kamera getrennt", null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    CameraCloseGate.close(camera)
                    if (gen != generation) return
                    cameraDevice = null
                    val busy = error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE
                    if (running && !(busy && retryOpen(previewView))) onError("High-Speed-Kamerafehler: $error", null)
                }

                override fun onClosed(camera: CameraDevice) {
                    CameraCloseGate.closed(camera)
                }
            }, handler)
        }.onFailure { if (!retryOpen(previewView)) onError("Kamera konnte nicht geoeffnet werden", it) }
    }

    // Closing a camera is asynchronous. Opening another one right after (lens switch) can find the HAL still busy,
    // so try again shortly before giving up.
    private fun retryOpen(previewView: SurfaceView): Boolean {
        val handler = cameraHandler ?: return false
        if (!running || openAttempts >= MAX_OPEN_RETRIES) return false
        openAttempts++
        val gen = generation
        handler.postDelayed({
            if (gen == generation && running) {
                openRequested = false
                openCamera(previewView)
            }
        }, OPEN_RETRY_DELAY_MS)
        return true
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
        val hsSession = session as? CameraConstrainedHighSpeedCaptureSession ?: return

        val request = buildRequest(afTriggerStart = false) ?: return

        val burst = hsSession.createHighSpeedRequestList(request)
        session.setRepeatingBurst(burst, null, handler)
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
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureIndex)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            if (torchEnabled) {
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }

            val afRegions = MeteringMath.meteringRegion(profile.characteristics, profile.size, focusNormX, focusNormY)
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


    companion object {
        private const val MAX_OPEN_RETRIES = 3
        private const val OPEN_RETRY_DELAY_MS = 300L

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
