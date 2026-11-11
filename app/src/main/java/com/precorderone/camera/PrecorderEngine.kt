package com.precorderone.camera

import android.Manifest
import android.app.ActivityManager
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LifecycleOwner
import com.precorderone.data.PrecorderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

@ExperimentalCamera2Interop
class PrecorderEngine(private val context: Context) {
    enum class CapturePipeline { CAMERAX, CAMERA2_NORMAL, CAMERA2_HIGHSPEED }

    private var activePipeline: CapturePipeline = CapturePipeline.CAMERAX
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var camera2Session: Camera2RecordSession? = null
    private var highSpeedSession: HighSpeedCamera2Session? = null
    private var activeCamera2Profile: Camera2RecordSession.Profile? = null
    private var activeHighSpeedProfile: HighSpeedCamera2Session.Profile? = null
    private var codec: MediaCodec? = null
    private var codecInputSurface: Surface? = null
    private var codecUsesSurfaceInput = false
    private var encoderOutputFormat: MediaFormat? = null
    @Volatile
    private var encoderBitRate = 0
    // Created with the encoder (size depends on its bitrate); written by the drain thread, read by the export.
    @Volatile
    private var ringBuffer: EncodedFrameRingBuffer? = null
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var onBufferFillChanged: ((Float) -> Unit)? = null
    var onMeasuredFpsChanged: ((Float) -> Unit)? = null
    var onDebugStatsChanged: ((Float, Float, Float, Float) -> Unit)? = null
    var onProfileFallback: ((String) -> Unit)? = null
    var onPipelineChanged: ((CapturePipeline, String?) -> Unit)? = null
    var onLensesChanged: ((List<LensOption>, LensOption?) -> Unit)? = null
    /** Physical lens behind a logical multi-camera that delivers frames right now; null when unknown. */
    var onActivePhysicalCameraChanged: ((String?) -> Unit)? = null
    /** Width / height of the upright preview of the Camera2 pipelines, to size the preview without distortion. */
    var onPreviewAspectChanged: ((Float) -> Unit)? = null

    private var retentionUs: Long = 5_000_000
    private var fillReferenceUs: Long = 5_000_000
    private var audioRecorder: AudioRingRecorder? = null
    private var formatWidth = 0
    private var formatHeight = 0
    private var formatReady = false

    private var lastSamplePtsUs: Long = -1L
    private var fpsWindowStartPtsUs: Long = -1L
    private var fpsWindowFrames: Int = 0

    private var sourceWindowStartUs: Long = -1L
    private var sourceWindowFrames: Int = 0
    private var sourceFps: Float = 0f

    private var inputWindowStartUs: Long = -1L
    private var inputWindowFrames: Int = 0
    private var inputFps: Float = 0f

    private var encodedWindowStartUs: Long = -1L
    private var encodedWindowFrames: Int = 0
    private var encodedFps: Float = 0f

    private var fallbackApplied = false
    private var bindStartMs: Long = 0L
    private var forceLowProfile = false
    private var boundOwner: LifecycleOwner? = null
    private var boundPreviewView: PreviewView? = null
    private var boundHighSpeedPreviewView: SurfaceView? = null
    private var boundSettings: PrecorderSettings? = null
    // Exposure compensation in EV; kept across rebinds like the zoom and converted to each camera's steps.
    private var exposureEv = 0f
    private var pendingFpsApply: Boolean = true
    private var lastFpsApplyMs: Long = 0L
    private var reusableYuvBuffer: ByteArray? = null
    private val codecLock = Any()
    private var manualSensorModeActive: Boolean = false
    private var unsupportedExactFpsNotified: Int? = null
    private var highSpeedDrainActive = false
    private var requestedTorchEnabled = false
    private var lensSet: LensSet = LensSet.EMPTY
    private var currentZoomRatio = 1f
    // Camera that actually delivers frames (may differ from settings.cameraId after a fallback); read by the export thread.
    @Volatile
    private var activeCameraId: String? = null
    private var lastBufferNotifyMs = 0L
    fun getActiveCameraId(): String? = activeCameraId
    fun getCurrentZoomRatio(): Float = currentZoomRatio
    fun getExposureEv(): Float = exposureEv

    /**
     * Exposure compensation via CONTROL_AE_EXPOSURE_COMPENSATION: auto exposure keeps working (also at 120/240 fps),
     * only its target brightness shifts. Returns the EV actually applied (snapped to the camera's step).
     */
    fun setExposureEv(ev: Float): Float {
        exposureEv = ev
        val index = exposureIndexFor(activeCameraId, ev)
        when (activePipeline) {
            CapturePipeline.CAMERA2_NORMAL -> camera2Session?.setExposureCompensation(index)
            CapturePipeline.CAMERA2_HIGHSPEED -> highSpeedSession?.setExposureCompensation(index)
            CapturePipeline.CAMERAX -> camera?.let { runCatching { it.cameraControl.setExposureCompensationIndex(index) } }
        }
        return index * exposureStep(activeCameraId)
    }

    private fun exposureStep(cameraId: String?): Float {
        val chars = cameraId?.let { id -> runCatching { cameraManager().getCameraCharacteristics(id) }.getOrNull() } ?: return 0f
        return chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
    }

    private fun exposureIndexFor(cameraId: String?, ev: Float): Int {
        val chars = cameraId?.let { id -> runCatching { cameraManager().getCameraCharacteristics(id) }.getOrNull() } ?: return 0
        val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: return 0
        val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return 0
        if (step <= 0f) return 0
        return Math.round(ev / step).coerceIn(range.lower, range.upper)
    }

    private fun cameraManager() = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun bind(
        owner: LifecycleOwner,
        previewView: PreviewView,
        highSpeedPreviewView: SurfaceView,
        settings: PrecorderSettings,
        initialZoomRatio: Float = 1f
    ) {
        currentZoomRatio = initialZoomRatio
        boundOwner = owner
        boundPreviewView = previewView
        boundHighSpeedPreviewView = highSpeedPreviewView
        boundSettings = settings
        pendingFpsApply = true
        unsupportedExactFpsNotified = null
        requestedTorchEnabled = settings.torchEnabled
        bindStartMs = System.currentTimeMillis()
        fallbackApplied = false
        // Low-profile tweaks are only relevant on CameraX fallback paths.
        forceLowProfile = false

        // The ring also has to hold the post-roll; "full" (ready to trigger) only needs the part before the trigger.
        fillReferenceUs = (settings.loopSeconds + 1) * 1_000_000L
        retentionUs = fillReferenceUs + settings.postRollSeconds * 1_000_000L
        // A new binding always starts with a fresh encoder and buffer: frames of two configurations never mix.
        stopHighSpeedSession()
        stopAudio()
        resetEncodingState()

        if (settings.targetFps >= 120) {
            val profile = HighSpeedCamera2Session.chooseProfile(
                context = context,
                targetFps = settings.targetFps,
                lensFacing = settings.lensFacing,
                preferredCameraId = settings.cameraId,
                aspect = settings.aspectRatio
            )
            if (profile != null) {
                activePipeline = CapturePipeline.CAMERA2_HIGHSPEED
                manualSensorModeActive = true
                activeHighSpeedProfile = profile
                activeCameraId = profile.cameraId
                configureLenses(profile.cameraId)
                announcePipeline(CapturePipeline.CAMERA2_HIGHSPEED, profile.cameraId, settings.targetFps)
                bindHighSpeed(profile, previewView, highSpeedPreviewView, settings)
                return
            }
            onProfileFallback?.invoke("High-Speed mit ${settings.targetFps} fps gibt es auf dieser Kamera nicht. Aufnahme mit der höchsten normalen Bildrate.")
        }

        // Guardrail: regular live capture must stay on Camera2 surface pipeline to avoid CPU-heavy YUV copies.
        val camera2Profile = Camera2RecordSession.chooseProfile(
            context = context,
            targetFps = settings.targetFps,
            lensFacing = settings.lensFacing,
            preferredCameraId = settings.cameraId,
            aspect = settings.aspectRatio
        )
        if (camera2Profile != null) {
            activePipeline = CapturePipeline.CAMERA2_NORMAL
            manualSensorModeActive = true
            activeCamera2Profile = camera2Profile
            activeCameraId = camera2Profile.cameraId
            configureLenses(camera2Profile.cameraId)
            announcePipeline(CapturePipeline.CAMERA2_NORMAL, camera2Profile.cameraId, settings.targetFps)
            bindCamera2Normal(camera2Profile, previewView, highSpeedPreviewView, settings)
            return
        }
        onProfileFallback?.invoke("Camera2 Surface-Pfad nicht verfuegbar. Fallback auf CameraX.")

        activePipeline = CapturePipeline.CAMERAX
        manualSensorModeActive = false
        activeCamera2Profile = null
        activeHighSpeedProfile = null
        configureLenses(null)
        stopHighSpeedSession()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            highSpeedPreviewView.visibility = View.GONE
            previewView.visibility = View.VISIBLE
            bindInternal(owner, previewView, settings)
        }, ContextCompat.getMainExecutor(context))
    }

    fun toggleTorch(enabled: Boolean) {
        requestedTorchEnabled = enabled
        if (activePipeline == CapturePipeline.CAMERA2_NORMAL) {
            camera2Session?.updateControls(requestedTorchEnabled)
            return
        }
        if (activePipeline == CapturePipeline.CAMERA2_HIGHSPEED) {
            highSpeedSession?.updateControls(requestedTorchEnabled)
            return
        }
        camera?.cameraControl?.enableTorch(enabled)
    }

    /** Zoom on the active camera; switching to another camera ID is a rebind with [bind]'s initialZoomRatio. */
    fun setZoomRatio(ratio: Float): Boolean {
        val applied = when (activePipeline) {
            CapturePipeline.CAMERA2_NORMAL -> camera2Session?.setZoomRatio(ratio) ?: false
            CapturePipeline.CAMERA2_HIGHSPEED -> highSpeedSession?.setZoomRatio(ratio) ?: false
            CapturePipeline.CAMERAX -> {
                val cam = camera ?: return false
                runCatching {
                    cam.cameraControl.setZoomRatio(ratio)
                    true
                }.getOrDefault(false)
            }
        }
        if (!applied) return false
        currentZoomRatio = ratio
        notifyLensesChanged()
        return true
    }

    fun focusAt(previewView: PreviewView, x: Float, y: Float): Boolean {
        if (manualSensorModeActive) return false
        val cam = camera ?: return false
        runCatching { cam.cameraControl.cancelFocusAndMetering() }
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).disableAutoCancel().build()
        if (!cam.cameraInfo.isFocusMeteringSupported(action)) return false
        return runCatching {
            cam.cameraControl.startFocusAndMetering(action)
            true
        }.getOrDefault(false)
    }

    fun clearFocusLock(): Boolean {
        if (activePipeline == CapturePipeline.CAMERA2_NORMAL) return camera2Session?.clearFocusLock() ?: false
        if (activePipeline == CapturePipeline.CAMERA2_HIGHSPEED) return highSpeedSession?.clearFocusLock() ?: false
        val cam = camera ?: return false
        return runCatching {
            cam.cameraControl.cancelFocusAndMetering()
            true
        }.getOrDefault(false)
    }

    /** Tap on the Camera2 preview; [displayX]/[displayY] are 0..1 within the upright, letterboxed preview. */
    fun focusAtPreview(displayX: Float, displayY: Float): Boolean {
        val chars = activeCameraId?.let { id -> runCatching { cameraManager().getCameraCharacteristics(id) }.getOrNull() }
            ?: return false
        val sensor = MeteringMath.displayToSensor(displayX, displayY, chars)
        return when (activePipeline) {
            CapturePipeline.CAMERA2_NORMAL -> camera2Session?.focusAt(sensor.x, sensor.y) ?: false
            CapturePipeline.CAMERA2_HIGHSPEED -> highSpeedSession?.focusAt(sensor.x, sensor.y) ?: false
            CapturePipeline.CAMERAX -> false
        }
    }

    fun getBufferFillRatio(): Float {
        val duration = ringBuffer?.durationUs() ?: 0L
        return (duration.toFloat() / fillReferenceUs.toFloat()).coerceIn(0f, 1f)
    }

    private fun bindInternal(owner: LifecycleOwner, previewView: PreviewView, settings: PrecorderSettings): Boolean {
        val provider = cameraProvider ?: return false
        provider.unbindAll()

        val selector = settings.cameraId?.let { desiredId ->
            CameraSelector.Builder()
                .addCameraFilter { infos ->
                    infos.filter { info -> Camera2CameraInfo.from(info).cameraId == desiredId }
                }
                .build()
        } ?: CameraSelector.Builder().requireLensFacing(settings.lensFacing).build()

        val previewBuilder = Preview.Builder().applyAspect(settings.aspectRatio)
        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .applyAspect(settings.aspectRatio)

        applyFrameRatePriorityControls(settings, previewBuilder, analysisBuilder)

        val preview = previewBuilder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        analysis = analysisBuilder.build().also { analyzer ->
            analyzer.setAnalyzer(analyzerExecutor) { image ->
                updateSourceStats(image.imageInfo.timestamp / 1_000)
                encodeImage(image, settings)
            }
        }

        camera = runCatching {
            provider.bindToLifecycle(owner, selector, preview, analysis)
        }.getOrElse { primaryError ->
            Log.w(TAG, "Selected camera could not be bound, falling back to lens facing only", primaryError)
            val fallbackSelector = CameraSelector.Builder().requireLensFacing(settings.lensFacing).build()
            runCatching {
                provider.bindToLifecycle(owner, fallbackSelector, preview, analysis)
            }.getOrElse { fallbackError ->
                Log.e(TAG, "CameraX bind failed (primary + fallback)", fallbackError)
                onProfileFallback?.invoke("Kamera gerade nicht verfügbar. Bitte kurz warten und die App erneut öffnen.")
                null
            }
        }
        val cam = camera ?: return false
        val resolvedCameraId = runCatching { Camera2CameraInfo.from(cam.cameraInfo).cameraId }.getOrNull()
        activeCameraId = resolvedCameraId
        configureLenses(resolvedCameraId)
        startAudio(settings, resolvedCameraId)
        announcePipeline(CapturePipeline.CAMERAX, resolvedCameraId, settings.targetFps)
        toggleTorch(settings.torchEnabled)
        if (currentZoomRatio != 1f) runCatching { cam.cameraControl.setZoomRatio(currentZoomRatio) }
        if (exposureEv != 0f) runCatching { cam.cameraControl.setExposureCompensationIndex(exposureIndexFor(resolvedCameraId, exposureEv)) }
        return true
    }

    private fun bindHighSpeed(
        profile: HighSpeedCamera2Session.Profile,
        previewView: PreviewView,
        highSpeedPreviewView: SurfaceView,
        settings: PrecorderSettings
    ) {
        runCatching { cameraProvider?.unbindAll() }
        analysis?.clearAnalyzer()
        analysis = null
        camera = null

        previewView.visibility = View.GONE
        highSpeedPreviewView.visibility = View.VISIBLE

        val inputSurface = createEncoderSurface(profile.size, settings)
        if (inputSurface == null) {
            onProfileFallback?.invoke("High-Speed Encoder Surface konnte nicht erstellt werden. Fallback auf CameraX.")
            fallbackToCameraX(settings)
            return
        }

        stopHighSpeedSession()
        val session = highSpeedSession ?: HighSpeedCamera2Session(
            context = context,
            onError = { message, throwable ->
                Log.e(TAG, message, throwable)
                onProfileFallback?.invoke("$message Fallback auf CameraX.")
                fallbackToCameraX(settings)
            }
        ).also { highSpeedSession = it }
        session.start(
            profile = profile,
            previewView = highSpeedPreviewView,
            encoderSurface = inputSurface,
            torch = requestedTorchEnabled,
            initialZoomRatio = currentZoomRatio,
            exposureIndex = exposureIndexFor(profile.cameraId, exposureEv)
        )
        onPreviewAspectChanged?.invoke(MeteringMath.displayAspect(profile.characteristics, profile.size))
        startHighSpeedDrainLoop()
        startAudio(settings, profile.cameraId)
    }

    private fun bindCamera2Normal(
        profile: Camera2RecordSession.Profile,
        previewView: PreviewView,
        highSpeedPreviewView: SurfaceView,
        settings: PrecorderSettings
    ) {
        runCatching { cameraProvider?.unbindAll() }
        analysis?.clearAnalyzer()
        analysis = null
        camera = null

        previewView.visibility = View.GONE
        highSpeedPreviewView.visibility = View.VISIBLE

        val inputSurface = createEncoderSurface(profile.size, settings)
        if (inputSurface == null) {
            onProfileFallback?.invoke("Camera2 Encoder Surface konnte nicht erstellt werden. Fallback auf CameraX.")
            fallbackToCameraX(settings)
            return
        }

        stopHighSpeedSession()
        val session = camera2Session ?: Camera2RecordSession(
            context = context,
            onError = { message, throwable ->
                Log.e(TAG, message, throwable)
                onProfileFallback?.invoke("$message Fallback auf CameraX.")
                fallbackToCameraX(settings)
            },
            onActivePhysicalCamera = { id ->
                Log.i(TAG, "Active physical camera: $id")
                onActivePhysicalCameraChanged?.invoke(id)
            }
        ).also { camera2Session = it }
        session.start(
            profile = profile,
            previewView = highSpeedPreviewView,
            encoderSurface = inputSurface,
            torch = requestedTorchEnabled,
            initialZoomRatio = currentZoomRatio,
            exposureIndex = exposureIndexFor(profile.cameraId, exposureEv)
        )
        onPreviewAspectChanged?.invoke(MeteringMath.displayAspect(profile.characteristics, profile.size))
        startHighSpeedDrainLoop()
        startAudio(settings, profile.cameraId)
    }

    /**
     * Microphone ring beside the video, only when sound can end up in the clip: enabled, permitted and real-time
     * playback (recording fps = playback fps). Uses the clock of the camera's timestamps so both line up.
     */
    private fun startAudio(settings: PrecorderSettings, cameraId: String?) {
        stopAudio()
        if (!settings.recordAudio || settings.targetFps != settings.playbackFps) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        // The sound needs the clock of the video timestamps. On the surface pipelines that is always CLOCK_MONOTONIC:
        // the camera service converts the timestamps of frames it hands to a video encoder, also when the sensor
        // itself counts in boot time (measured on S10/Android 12 and S21/Android 15). Only CameraX' ImageAnalysis
        // passes the sensor's clock through. The two clocks differ by the time the phone has slept since booting,
        // soon hours; sound stamped with the wrong one misses the clip altogether.
        val sensorBootTime = cameraId?.let { id ->
            runCatching {
                cameraManager().getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            }.getOrNull()
        } ?: true
        val bootTime = activePipeline == CapturePipeline.CAMERAX && sensorBootTime
        audioRecorder = AudioRingRecorder(retentionUs, bootTime).takeIf { it.start() }
        if (audioRecorder == null) onProfileFallback?.invoke("Mikrofon nicht verfügbar – Clips werden ohne Ton gespeichert.")
    }

    private fun stopAudio() {
        audioRecorder?.stop()
        audioRecorder = null
    }

    // Encoders reject some sizes a camera offers (e.g. 8K for AVC); configure() then throws instead of returning an error.
    private fun createEncoderSurface(size: Size, settings: PrecorderSettings): Surface? {
        return runCatching {
            synchronized(codecLock) {
                ensureCodec(size.width, size.height, settings, useSurfaceInput = true)
            }
            codecInputSurface
        }.onFailure {
            Log.e(TAG, "Encoder configuration failed for ${size.width}x${size.height}", it)
        }.getOrNull()
    }

    private fun fallbackToCameraX(settings: PrecorderSettings) {
        stopHighSpeedSession()
        activePipeline = CapturePipeline.CAMERAX
        manualSensorModeActive = false
        activeCamera2Profile = null
        activeHighSpeedProfile = null
        val owner = boundOwner ?: return
        val preview = boundPreviewView ?: return
        val hsPreview = boundHighSpeedPreviewView ?: return
        ContextCompat.getMainExecutor(context).execute {
            hsPreview.visibility = View.GONE
            preview.visibility = View.VISIBLE
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                val provider = runCatching { providerFuture.get() }.getOrElse { error ->
                    Log.e(TAG, "CameraX provider init failed after HS fallback", error)
                    onProfileFallback?.invoke("Kamera-Initialisierung fehlgeschlagen. Bitte erneut starten.")
                    return@addListener
                }
                cameraProvider = provider
                val bound = bindInternal(owner, preview, settings)
                if (!bound) {
                    // Transient camera teardown after Camera2 errors is common; retry once.
                    preview.postDelayed({
                        val retryBound = bindInternal(owner, preview, settings)
                        if (!retryBound) {
                            onProfileFallback?.invoke("Kamera konnte nicht erneut gebunden werden.")
                        }
                    }, 700L)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    /** Lens bar for [cameraId] (empty while no camera is bound); also clamps the zoom to what that camera supports. */
    private fun configureLenses(cameraId: String?) {
        onActivePhysicalCameraChanged?.invoke(null)
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val highSpeed = activePipeline == CapturePipeline.CAMERA2_HIGHSPEED
        lensSet = cameraId?.let { id ->
            runCatching { LensCatalog.lenses(manager, id, zoomRatioLenses = !highSpeed) }.getOrNull()
        } ?: LensSet.EMPTY
        if (cameraId != null) {
            Log.i(TAG, "LENSES camera=$cameraId: " + lensSet.options.joinToString { "${it.label}=${it.cameraId}@${it.zoomRatio}" })
        }
        val range = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && cameraId != null) {
            runCatching { manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) }.getOrNull()
        } else {
            null
        }
        currentZoomRatio = when {
            cameraId == null -> currentZoomRatio
            // High-speed sessions ignore the zoom ratio; anything but 1x would mark the wrong lens as active.
            highSpeed -> 1f
            range != null -> currentZoomRatio.coerceIn(range.lower, range.upper)
            else -> 1f
        }
        notifyLensesChanged()
    }

    private fun notifyLensesChanged() {
        onLensesChanged?.invoke(lensSet.options, lensSet.current(activeCameraId, currentZoomRatio))
    }

    private fun startHighSpeedDrainLoop() {
        if (highSpeedDrainActive) return
        highSpeedDrainActive = true
        analyzerExecutor.execute {
            while (highSpeedDrainActive) {
                synchronized(codecLock) {
                    codec?.let { drainCodec(it) }
                }
                Thread.sleep(2)
            }
        }
    }

    private fun stopHighSpeedSession() {
        highSpeedDrainActive = false
        runCatching { camera2Session?.stop() }
        runCatching { highSpeedSession?.stop() }
        activeCamera2Profile = null
        activeHighSpeedProfile = null
    }

    @Suppress("DEPRECATION")
    private fun Preview.Builder.applyAspect(aspect: String): Preview.Builder {
        if (forceLowProfile) {
            when (aspect) {
                "4:3" -> setTargetResolution(Size(640, 480))
                else -> setTargetResolution(Size(640, 360))
            }
            return this
        }
        when (aspect) {
            "4:3" -> setTargetAspectRatio(AspectRatio.RATIO_4_3)
            else -> setTargetAspectRatio(AspectRatio.RATIO_16_9)
        }
        return this
    }

    @Suppress("DEPRECATION")
    private fun ImageAnalysis.Builder.applyAspect(aspect: String): ImageAnalysis.Builder {
        if (forceLowProfile) {
            when (aspect) {
                "4:3" -> setTargetResolution(Size(640, 480))
                else -> setTargetResolution(Size(640, 360))
            }
            return this
        }
        when (aspect) {
            "4:3" -> setTargetAspectRatio(AspectRatio.RATIO_4_3)
            else -> setTargetAspectRatio(AspectRatio.RATIO_16_9)
        }
        return this
    }

    private fun selectFpsRange(cameraId: String?, targetFps: Int, requireExact: Boolean = false): Range<Int>? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cameraId ?: return null
        val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return null
        return selectFpsRange(chars, targetFps, requireExact)
    }

    private fun selectFpsRange(chars: CameraCharacteristics, targetFps: Int, requireExact: Boolean = false): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        if (ranges.isEmpty()) return null

        ranges.firstOrNull { it.lower == targetFps && it.upper == targetFps }?.let { return it }
        if (requireExact) return null

        ranges.filter { it.upper == targetFps }.minByOrNull { it.lower }?.let { return it }

        val containing = ranges.filter { targetFps in it.lower..it.upper }
        containing.minByOrNull { abs(it.upper - targetFps) + abs(it.lower - targetFps) }?.let { return it }
        return ranges.maxByOrNull { it.upper }
    }

    private fun applyFrameRatePriorityControls(
        settings: PrecorderSettings,
        previewBuilder: Preview.Builder,
        analysisBuilder: ImageAnalysis.Builder
    ) {
        settings.cameraId ?: return

        manualSensorModeActive = false

        val previewExt = Camera2Interop.Extender(previewBuilder)
        val analysisExt = Camera2Interop.Extender(analysisBuilder)
        listOf(previewExt, analysisExt).forEach { ext ->
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }
    }

    private fun maybeApplyRuntimeFpsOverride(cam: Camera, settings: PrecorderSettings) {
        if (!pendingFpsApply) return
        val now = System.currentTimeMillis()
        if (now - lastFpsApplyMs < 500L) return
        lastFpsApplyMs = now
        pendingFpsApply = !applyRuntimeFpsOverride(cam, settings)
    }

    private fun applyRuntimeFpsOverride(cam: Camera, settings: PrecorderSettings): Boolean {
        val cameraId = runCatching { Camera2CameraInfo.from(cam.cameraInfo).cameraId }
            .getOrElse { settings.cameraId }
            ?: return false
        val chars = runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.getCameraCharacteristics(cameraId)
        }.getOrNull() ?: return false

        val requireExact = settings.targetFps >= 120
        val fpsRange = selectFpsRange(chars, settings.targetFps, requireExact = requireExact)
        if (fpsRange == null) {
            if (requireExact && unsupportedExactFpsNotified != settings.targetFps) {
                unsupportedExactFpsNotified = settings.targetFps
                val advertised = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    .orEmpty()
                    .joinToString { "[${it.lower},${it.upper}]" }
                onProfileFallback?.invoke(
                    "Exaktes ${settings.targetFps} fps wird hier nicht angeboten. Verfuegbare AE-Ranges: $advertised"
                )
            }
            return false
        }
        unsupportedExactFpsNotified = null

        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            .build()

        return runCatching {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options)
            true
        }.getOrElse {
            Log.w(TAG, "FPS override deferred: ${it.message}")
            false
        }
    }

    private fun ensureCodec(width: Int, height: Int, settings: PrecorderSettings, useSurfaceInput: Boolean) {

        if (formatReady && (width != formatWidth || height != formatHeight || codecUsesSurfaceInput != useSurfaceInput)) {
            // Analyzer format changed: cleanly restart encoder state.
            formatReady = false
            formatWidth = 0
            formatHeight = 0
            encoderOutputFormat = null
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { codecInputSurface?.release() }
            codecInputSurface = null
            codec = null
            ringBuffer?.close()
            ringBuffer = null
            notifyBufferProgress(0f)
        }

        if (formatReady) return

        formatWidth = width
        formatHeight = height
        codecUsesSurfaceInput = useSurfaceInput

        // A long buffer keeps its full bitrate and takes the memory it needs for that (a minute at 80 Mbit/s is a
        // good gigabyte). Only where the phone does not have that much to give does the bitrate give way, as the
        // last resort before a buffer that is shorter than set.
        val ringBytes = ringBudgetBytes()
        val wantedBitRate = estimateBitrate(settings.targetFps, formatWidth, formatHeight)
        val bitRate = min(wantedBitRate, EncodedFrameRingBuffer.maxBitRate(retentionUs, ringBytes))
        Log.i(TAG, "ENCODER ${formatWidth}x$formatHeight @${settings.targetFps} fps, $bitRate bit/s (wanted $wantedBitRate), ring up to ${ringBytes shr 20} MB")
        encoderBitRate = bitRate
        val mediaFormat = MediaFormat.createVideoFormat(MIME_TYPE, formatWidth, formatHeight).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                if (useSurfaceInput) MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.targetFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val newCodec = MediaCodec.createEncoderByType(MIME_TYPE)
        try {
            newCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            if (useSurfaceInput) {
                codecInputSurface = newCodec.createInputSurface()
            }
            newCodec.start()
        } catch (e: Exception) {
            runCatching { codecInputSurface?.release() }
            codecInputSurface = null
            newCodec.release()
            throw e
        }
        codec = newCodec
        formatReady = true
        // Sized for this encoder's bitrate. A ring of an earlier configuration is freed once its export has finished.
        ringBuffer?.close()
        ringBuffer = EncodedFrameRingBuffer.create(bitRate, settings.targetFps, retentionUs, ringBytes)
    }

    /**
     * Memory the video ring may take: up to a quarter of the device's RAM. From about 5 GB of RAM on that covers
     * every setting ([EncodedFrameRingBuffer.MAX_BYTES]), so the bitrate is never touched there.
     */
    private fun ringBudgetBytes(): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return (info.totalMem / 4).coerceIn(MIN_RING_BUDGET_BYTES, EncodedFrameRingBuffer.MAX_BYTES)
    }

    private fun encodeImage(image: ImageProxy, settings: PrecorderSettings) {
        try {
            camera?.let {
                maybeApplyRuntimeFpsOverride(it, settings)
            }
            synchronized(codecLock) {
                ensureCodec(image.width, image.height, settings, useSurfaceInput = false)
                val activeCodec = codec ?: return
                queueInput(activeCodec, image)
                drainCodec(activeCodec)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encoding error", e)
        } finally {
            image.close()
        }
    }

    private fun queueInput(codec: MediaCodec, image: ImageProxy) {
        val inputIndex = codec.dequeueInputBuffer(0)
        if (inputIndex < 0) return
        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
        inputBuffer.clear()

        val yuv = yuv420888ToNv12(image)
        inputBuffer.put(yuv)
        val ptsUs = image.imageInfo.timestamp / 1_000
        codec.queueInputBuffer(inputIndex, 0, yuv.size, ptsUs, 0)
        updateQueuedStats(ptsUs)
    }

    private fun drainCodec(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 0)
            when {
                outIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outIndex)
                    // Codec config (SPS/PPS) reaches the muxer through encoderOutputFormat, not through the ring.
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (buffer != null && info.size > 0 && !isConfig) {
                        buffer.limit(info.offset + info.size)
                        buffer.position(info.offset)
                        ringBuffer?.append(buffer, info.size, info.presentationTimeUs, info.flags)
                        // The UI only needs ~10 updates per second, not one per frame (up to 240/s).
                        val nowMs = SystemClock.elapsedRealtime()
                        if (nowMs - lastBufferNotifyMs >= BUFFER_NOTIFY_INTERVAL_MS) {
                            lastBufferNotifyMs = nowMs
                            notifyBufferProgress(getBufferFillRatio())
                        }
                        updateEncodedStats(info.presentationTimeUs)
                        if (activePipeline == CapturePipeline.CAMERA2_HIGHSPEED) {
                            updateSourceStats(info.presentationTimeUs)
                            updateQueuedStats(info.presentationTimeUs)
                        } else if (activePipeline == CapturePipeline.CAMERA2_NORMAL) {
                            updateSourceStats(info.presentationTimeUs)
                            updateQueuedStats(info.presentationTimeUs)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }

                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> encoderOutputFormat = codec.outputFormat
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> return
            }
        }
    }

    /** A saved clip, or [uri] null when saving failed. [overlayMissing]: saved, but without the overlay asked for. */
    class ExportResult(val uri: Uri?, val overlayMissing: Boolean = false)

    /**
     * Saves the buffer as MP4. The leases are taken right here on the caller's thread, so pausing right after the
     * call (app sent to background during the post-roll) cannot reset the buffers under the running export.
     *
     * With an [overlay] the clip is re-encoded with the timecode and the speed label in the picture, which takes a
     * while: [onProgress] reports 0..1 of that. [onDone] is always called, on a background thread.
     */
    fun exportClip(
        settings: PrecorderSettings,
        deviceSurfaceRotation: Int,
        overlay: ClipOverlay,
        onProgress: (Float) -> Unit = {},
        onDone: (ExportResult) -> Unit
    ) {
        // Read pointer: the lease freezes the frames to export while recording continues around them.
        // Nothing is swapped out, so the buffer is still full right after the save.
        val lease = ringBuffer?.beginRead()
        val format = encoderOutputFormat
        val bitRate = encoderBitRate
        if (lease == null || format == null) {
            lease?.close()
            onDone(ExportResult(null))
            return
        }
        val audio = audioRecorder?.let { recorder ->
            val audioFormat = recorder.outputFormat
            val audioLease = recorder.ring?.beginRead()
            if (audioFormat != null && audioLease != null) {
                ClipMuxer.AudioInput(audioLease, audioFormat)
            } else {
                audioLease?.close()
                null
            }
        }
        val orientationHint = computeOrientationHint(settings, deviceSurfaceRotation)
        val releaseLeases = {
            lease.close()
            audio?.lease?.close()
            Unit
        }
        ioScope.launch {
            val result = try {
                if (!overlay.isEmpty) {
                    exportWithOverlay(lease, format, settings, orientationHint, audio, bitRate, overlay, releaseLeases, onProgress)
                } else {
                    ExportResult(exportFrames(lease, format, settings, orientationHint, audio))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                ExportResult(null)
            } finally {
                releaseLeases()
            }
            onDone(result)
        }
    }

    // Slow-motion factor as set (120 -> 30 fps = 4x); real time stays real time even when the camera delivers fewer
    // frames than its target.
    private fun timeScale(settings: PrecorderSettings) = settings.targetFps.toDouble() / settings.playbackFps.coerceAtLeast(1)

    /** Writes the leased frames straight into the clip, as they are. */
    private fun exportFrames(
        lease: EncodedFrameRingBuffer.Lease,
        format: MediaFormat,
        settings: PrecorderSettings,
        orientationHint: Int,
        audio: ClipMuxer.AudioInput?
    ): Uri? {
        val frames = ClipMuxer.framesToExport(lease) ?: return null
        return writeClip(settings) { fd ->
            ClipMuxer.write(lease, frames, format, fd, orientationHint, timeScale(settings), audio)
        }
    }

    /**
     * Saves the clip with [overlay] burnt in. Re-encoding takes far longer than the ring may be held, so the frames
     * first go into a real-time copy in the cache as they are, which is quick. That frees the ring ([releaseLeases])
     * and recording goes on without a hole while [TimecodeBurner] works from the copy. If burning fails the clip is
     * still saved, without the overlay.
     */
    private fun exportWithOverlay(
        lease: EncodedFrameRingBuffer.Lease,
        format: MediaFormat,
        settings: PrecorderSettings,
        orientationHint: Int,
        audio: ClipMuxer.AudioInput?,
        liveBitRate: Int,
        overlay: ClipOverlay,
        releaseLeases: () -> Unit,
        onProgress: (Float) -> Unit
    ): ExportResult {
        val frames = ClipMuxer.framesToExport(lease) ?: return ExportResult(null)
        val copy = runCatching { writeRealTimeCopy(lease, frames, format, audio) }
            .onFailure { Log.w(TAG, "No room for the copy to burn the overlay into", it) }
            .getOrNull()
            ?: return ExportResult(exportFrames(lease, format, settings, orientationHint, audio), overlayMissing = true)
        releaseLeases()
        try {
            val timeScale = timeScale(settings)
            // Bits per picture as recorded plus a reserve, so that encoding a second time costs no visible quality.
            val bitRate = (liveBitRate.toLong() * settings.playbackFps / settings.targetFps.coerceAtLeast(1) * 13 / 10)
                .coerceIn(MIN_BIT_RATE.toLong(), MAX_BIT_RATE.toLong()).toInt()
            val startMs = SystemClock.elapsedRealtime()
            val burned = writeClip(settings) { fd ->
                TimecodeBurner.burn(copy, fd, orientationHint, timeScale, settings.playbackFps, bitRate, overlay, onProgress)
            }
            if (burned != null) {
                Log.i(
                    TAG,
                    "OVERLAY burnt into ${frames.size} frames in ${SystemClock.elapsedRealtime() - startMs} ms at $bitRate bit/s: " +
                        "timecode ${overlay.timecode?.key ?: "off"}, label ${overlay.speedLabel ?: "off"}"
                )
                return ExportResult(burned)
            }
            val plain = writeClip(settings) { fd -> ClipMuxer.copy(copy, fd, orientationHint, timeScale) }
            return ExportResult(plain, overlayMissing = true)
        } finally {
            copy.delete()
        }
    }

    /** The leased frames as a real-time MP4 in the cache: no turn, no slow motion; [TimecodeBurner] adds both. */
    private fun writeRealTimeCopy(
        lease: EncodedFrameRingBuffer.Lease,
        frames: List<EncodedFrameRingBuffer.Frame>,
        format: MediaFormat,
        audio: ClipMuxer.AudioInput?
    ): File {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        // Left behind when the app was killed while saving.
        val staleBefore = System.currentTimeMillis() - STALE_COPY_MS
        dir.listFiles()?.filter { it.lastModified() < staleBefore }?.forEach { it.delete() }
        val file = File(dir, "clip_${System.nanoTime()}.mp4")
        try {
            RandomAccessFile(file, "rw").use { ClipMuxer.write(lease, frames, format, it.fd, 0, 1.0, audio) }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return file
    }

    /** Creates the clip's file and lets [write] fill it; a failed attempt leaves no file behind. */
    private fun writeClip(settings: PrecorderSettings, write: (FileDescriptor) -> Unit): Uri? {
        val output = createOutputTarget(settings) ?: return null
        return runCatching {
            write(output.fileDescriptor ?: error("No output file descriptor"))
            output.markCompleted()
            output.uri
        }.onFailure {
            output.cleanupOnFailure()
            Log.e(TAG, "Export failed", it)
        }.getOrNull()
    }

    fun release() {
        pauseSession()
        runCatching { camera2Session?.shutdown() }
        camera2Session = null
        runCatching { highSpeedSession?.shutdown() }
        highSpeedSession = null
        analyzerExecutor.shutdown()
        resetEncodingState()
    }

    fun pauseSession() {
        analysis?.clearAnalyzer()
        analysis = null
        camera = null
        runCatching { cameraProvider?.unbindAll() }
        stopHighSpeedSession()
        stopAudio()
        activeHighSpeedProfile = null
        // Keep currentZoomRatio: returning from the settings restores the lens that was in use.
        lensSet = LensSet.EMPTY
        notifyLensesChanged()
        resetEncodingState()
    }

    private fun resetEncodingState() {
        synchronized(codecLock) {
            ringBuffer?.close()
            ringBuffer = null
            notifyBufferProgress(0f)
            encoderOutputFormat = null
            lastSamplePtsUs = -1L
            fpsWindowStartPtsUs = -1L
            fpsWindowFrames = 0
            onMeasuredFpsChanged?.invoke(0f)
            onDebugStatsChanged?.invoke(0f, 0f, 0f, 0f)
            sourceWindowStartUs = -1L
            sourceWindowFrames = 0
            sourceFps = 0f
            inputWindowStartUs = -1L
            inputWindowFrames = 0
            inputFps = 0f
            encodedWindowStartUs = -1L
            encodedWindowFrames = 0
            encodedFps = 0f
            pendingFpsApply = true
            manualSensorModeActive = false
            formatReady = false
            formatWidth = 0
            formatHeight = 0
            codecUsesSurfaceInput = false
            reusableYuvBuffer = null
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { codecInputSurface?.release() }
            codec = null
            codecInputSurface = null
        }
    }

    private fun notifyBufferProgress(value: Float) {
        onBufferFillChanged?.invoke(value.coerceIn(0f, 1f))
    }

    private fun announcePipeline(pipeline: CapturePipeline, cameraId: String?, fps: Int) {
        Log.i(TAG, "PIPELINE=$pipeline, cameraId=${cameraId ?: "unknown"}, fps=$fps")
        onPipelineChanged?.invoke(pipeline, cameraId)
    }


    private fun updateSourceStats(currentPtsUs: Long) {
        if (sourceWindowStartUs < 0L) {
            sourceWindowStartUs = currentPtsUs
            sourceWindowFrames = 0
            return
        }

        sourceWindowFrames += 1
        val elapsedUs = currentPtsUs - sourceWindowStartUs
        if (elapsedUs >= 1_000_000L) {
            sourceFps = sourceWindowFrames * 1_000_000f / elapsedUs.toFloat()
            sourceWindowStartUs = currentPtsUs
            sourceWindowFrames = 0
            onMeasuredFpsChanged?.invoke(sourceFps)
            publishDebugStats()
        }
    }

    private fun updateQueuedStats(currentPtsUs: Long) {
        if (lastSamplePtsUs > 0 && currentPtsUs <= lastSamplePtsUs) return
        lastSamplePtsUs = currentPtsUs

        if (inputWindowStartUs < 0L) {
            inputWindowStartUs = currentPtsUs
            inputWindowFrames = 0
            return
        }

        inputWindowFrames += 1
        val elapsedUs = currentPtsUs - inputWindowStartUs
        if (elapsedUs >= 1_000_000L) {
            inputFps = inputWindowFrames * 1_000_000f / elapsedUs.toFloat()
            inputWindowStartUs = currentPtsUs
            inputWindowFrames = 0
            publishDebugStats()
            maybeAutoFallback()
        }
    }

    private fun updateEncodedStats(currentPtsUs: Long) {
        if (encodedWindowStartUs < 0L) {
            encodedWindowStartUs = currentPtsUs
            encodedWindowFrames = 0
            return
        }

        encodedWindowFrames += 1
        val elapsedUs = currentPtsUs - encodedWindowStartUs
        if (elapsedUs >= 1_000_000L) {
            encodedFps = encodedWindowFrames * 1_000_000f / elapsedUs.toFloat()
            encodedWindowStartUs = currentPtsUs
            encodedWindowFrames = 0
            publishDebugStats()
        }
    }

    private fun publishDebugStats() {
        val dropPercent = if (sourceFps <= 0.1f) 0f else ((sourceFps - encodedFps) / sourceFps * 100f).coerceIn(0f, 100f)
        onDebugStatsChanged?.invoke(sourceFps, inputFps, encodedFps, dropPercent)
    }

    private fun maybeAutoFallback() {
        // Disabled on purpose: keep selected FPS (e.g. 120) for diagnostics/testing.
    }

    private fun computeOrientationHint(settings: PrecorderSettings, deviceSurfaceRotation: Int): Int {
        val deviceDegrees = when (deviceSurfaceRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        // Use the camera that actually recorded the frames; its own LENS_FACING decides the formula, not the lens setting.
        val chars = runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = activeCameraId ?: settings.cameraId ?: return@runCatching null
            manager.getCameraCharacteristics(id)
        }.getOrNull()
        val sensorOrientation = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val isFront = chars?.get(CameraCharacteristics.LENS_FACING)?.let { it == CameraCharacteristics.LENS_FACING_FRONT }
            ?: (settings.lensFacing == CameraSelector.LENS_FACING_FRONT)

        // See CameraCharacteristics.SENSOR_ORIENTATION: front cameras add the device rotation, back cameras subtract it.
        return if (isFront) {
            (sensorOrientation + deviceDegrees) % 360
        } else {
            (sensorOrientation - deviceDegrees + 360) % 360
        }
    }

    private fun createOutputTarget(settings: PrecorderSettings): OutputTarget? {
        val fileName = "precorder_${System.currentTimeMillis()}.mp4"
        val resolver = context.contentResolver

        val configuredFolder = settings.outputFolderUri
        if (!configuredFolder.isNullOrBlank()) {
            try {
                val folder = DocumentFile.fromTreeUri(context, configuredFolder.toUri())
                val file = folder?.createFile("video/mp4", fileName)
                val pfd = file?.let { resolver.openFileDescriptor(it.uri, "w") }
                if (file != null && pfd != null) {
                    return OutputTarget(uri = file.uri, fileDescriptor = pfd.fileDescriptor, closeable = pfd, cleanup = { file.delete() })
                }
            } catch (e: Exception) {
                Log.w(TAG, "Configured folder unavailable, fallback to MediaStore", e)
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Precorder-One")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: return null
        return OutputTarget(
            uri = uri,
            fileDescriptor = pfd.fileDescriptor,
            closeable = pfd,
            finalize = {
                val publish = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(uri, publish, null, null)
            },
            cleanup = {
                resolver.delete(uri, null, null)
            }
        )
    }

    private fun yuv420888ToNv12(image: ImageProxy): ByteArray {
        require(image.format == ImageFormat.YUV_420_888)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val ySize = image.width * image.height
        val uvSize = ySize / 2
        val requiredSize = ySize + uvSize
        val out = if (reusableYuvBuffer?.size == requiredSize) {
            reusableYuvBuffer!!
        } else {
            ByteArray(requiredSize).also { reusableYuvBuffer = it }
        }

        copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, image.width, image.height, out)

        val chromaHeight = image.height / 2
        val chromaWidth = image.width / 2
        var offset = ySize
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        for (row in 0 until chromaHeight) {
            val uRowOffset = row * uPlane.rowStride
            val vRowOffset = row * vPlane.rowStride
            for (col in 0 until chromaWidth) {
                val uIndex = uRowOffset + col * uPlane.pixelStride
                val vIndex = vRowOffset + col * vPlane.pixelStride
                out[offset++] = uBuffer.get(uIndex)
                out[offset++] = vBuffer.get(vIndex)
            }
        }
        return out
    }

    private fun copyPlane(
        planeBuffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        out: ByteArray
    ) {
        var outOffset = 0
        if (pixelStride == 1) {
            val buffer = planeBuffer.duplicate()
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(out, outOffset, width)
                outOffset += width
            }
            return
        }

        for (row in 0 until height) {
            val rowOffset = row * rowStride
            for (col in 0 until width) {
                val index = rowOffset + col * pixelStride
                out[outOffset++] = planeBuffer.get(index)
            }
        }
    }

    private fun estimateBitrate(fps: Int, width: Int, height: Int): Int {
        val bpp = 0.14f
        return (bpp * fps * width * height).toInt().coerceIn(MIN_BIT_RATE, MAX_BIT_RATE)
    }

    private class OutputTarget(
        val uri: Uri,
        val fileDescriptor: java.io.FileDescriptor? = null,
        val closeable: AutoCloseable? = null,
        val finalize: (() -> Unit)? = null,
        val cleanup: (() -> Unit)? = null
    ) {
        fun markCompleted() {
            closeable?.close()
            finalize?.invoke()
        }

        fun cleanupOnFailure() {
            runCatching { closeable?.close() }
            runCatching { cleanup?.invoke() }
        }
    }

    companion object {
        private const val TAG = "PrecorderEngine"
        private const val MIME_TYPE = "video/avc"
        private const val BUFFER_NOTIFY_INTERVAL_MS = 100L
        private const val MIN_BIT_RATE = 4_000_000
        private const val MAX_BIT_RATE = 80_000_000
        private const val MIN_RING_BUDGET_BYTES = 128L shl 20
        private const val STALE_COPY_MS = 10 * 60_000L
    }
}
