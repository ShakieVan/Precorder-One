package com.precorderone.camera

import android.content.ContentValues
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
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

@ExperimentalCamera2Interop
class PrecorderEngine(private val context: Context) {
    private enum class CapturePipeline { CAMERAX, CAMERA2_NORMAL, CAMERA2_HIGHSPEED }

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
    private var ringBuffer: EncodedFrameRingBuffer = EncodedFrameRingBuffer(5_000_000)
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var onBufferFillChanged: ((Float) -> Unit)? = null
    var onMeasuredFpsChanged: ((Float) -> Unit)? = null
    var onDebugStatsChanged: ((Float, Float, Float, Float) -> Unit)? = null
    var onProfileFallback: ((String) -> Unit)? = null
    var onPipelineChanged: ((String) -> Unit)? = null
    var onNativeZoomChanged: ((List<Float>, Float) -> Unit)? = null

    private var retentionUs: Long = 5_000_000
    private var currentConfigKey: String? = null
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
    private var manualExposurePercent: Int = 55
    private var pendingExposureApply: Boolean = true
    private var lastExposureApplyMs: Long = 0L
    private var pendingFpsApply: Boolean = true
    private var lastFpsApplyMs: Long = 0L
    private var reusableYuvBuffer: ByteArray? = null
    private val codecLock = Any()
    private val bufferLock = Any()
    private var manualSensorModeActive: Boolean = false
    private var unsupportedExactFpsNotified: Int? = null
    private var highSpeedDrainActive = false
    private var requestedTorchEnabled = false
    private var availableNativeZoomSteps: List<Float> = listOf(1f)
    private var currentNativeZoomRatio = 1f
    fun getManualExposurePercent(): Int = manualExposurePercent
    fun getNativeTeleZoomSteps(): List<Float> = availableNativeZoomSteps
    fun getCurrentNativeZoomRatio(): Float = currentNativeZoomRatio

    fun setManualExposurePercent(percent: Int) {
        manualExposurePercent = percent.coerceIn(20, 100)
        pendingExposureApply = true
        val cam = camera ?: return
        val settings = boundSettings ?: return
        maybeApplyRuntimeExposureOverride(cam, settings)
    }

    fun bind(owner: LifecycleOwner, previewView: PreviewView, highSpeedPreviewView: SurfaceView, settings: PrecorderSettings) {
        boundOwner = owner
        boundPreviewView = previewView
        boundHighSpeedPreviewView = highSpeedPreviewView
        boundSettings = settings
        pendingExposureApply = true
        pendingFpsApply = true
        unsupportedExactFpsNotified = null
        requestedTorchEnabled = settings.torchEnabled
        bindStartMs = System.currentTimeMillis()
        fallbackApplied = false
        // Low-profile tweaks are only relevant on CameraX fallback paths.
        forceLowProfile = false

        retentionUs = (settings.loopSeconds + 1) * 1_000_000L
        val key = "${settings.cameraId}|${settings.lensFacing}|${settings.targetFps}|${settings.aspectRatio}"

        if (key != currentConfigKey) {
            resetEncodingState()
            currentConfigKey = key
        } else {
            // Bei erneutem Binden trotzdem Puffer leeren, damit nur konsistente Frames enthalten sind.
            synchronized(bufferLock) {
                ringBuffer.clear()
            }
            notifyBufferProgress(0f)
        }
        synchronized(bufferLock) {
            ringBuffer = EncodedFrameRingBuffer(retentionUs)
        }

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
                configureNativeZoomForCharacteristics(profile.characteristics)
                announcePipeline("PIPELINE=CAMERA2_HIGHSPEED, cameraId=${profile.cameraId}, fps=${settings.targetFps}")
                bindHighSpeed(profile, previewView, highSpeedPreviewView, settings)
                return
            }
            onProfileFallback?.invoke("Camera2 Performance-Pfad fuer ${settings.targetFps} fps nicht verfuegbar. Fallback auf CameraX.")
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
            configureNativeZoomForCharacteristics(camera2Profile.characteristics)
            announcePipeline("PIPELINE=CAMERA2_NORMAL, cameraId=${camera2Profile.cameraId}, fps=${settings.targetFps}")
            bindCamera2Normal(camera2Profile, previewView, highSpeedPreviewView, settings)
            return
        }
        onProfileFallback?.invoke("Camera2 Surface-Pfad nicht verfuegbar. Fallback auf CameraX.")

        activePipeline = CapturePipeline.CAMERAX
        manualSensorModeActive = false
        activeCamera2Profile = null
        activeHighSpeedProfile = null
        configureNativeZoomFallback()
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

    fun setNativeTeleZoomRatio(requestedRatio: Float): Boolean {
        val snapped = nearestZoomStep(requestedRatio)
        val applied = when (activePipeline) {
            CapturePipeline.CAMERA2_NORMAL -> camera2Session?.setZoomRatio(snapped) ?: false
            CapturePipeline.CAMERA2_HIGHSPEED -> highSpeedSession?.setZoomRatio(snapped) ?: false
            CapturePipeline.CAMERAX -> {
                val cam = camera ?: return false
                runCatching {
                    cam.cameraControl.setZoomRatio(snapped)
                    true
                }.getOrDefault(false)
            }
        }
        if (!applied) return false
        currentNativeZoomRatio = snapped
        notifyNativeZoomChanged()
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

    fun focusAtHighSpeed(normalizedX: Float, normalizedY: Float): Boolean {
        if (activePipeline == CapturePipeline.CAMERA2_NORMAL) {
            return camera2Session?.focusAt(normalizedX, normalizedY) ?: false
        }
        if (activePipeline != CapturePipeline.CAMERA2_HIGHSPEED) return false
        return highSpeedSession?.focusAt(normalizedX, normalizedY) ?: false
    }

    fun getBufferFillRatio(): Float {
        val snapshot = ringBuffer.snapshot()
        if (snapshot.size < 2) return 0f
        val duration = snapshot.last().presentationTimeUs - snapshot.first().presentationTimeUs
        return (duration.toFloat() / retentionUs.toFloat()).coerceIn(0f, 1f)
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
                onProfileFallback?.invoke("Kamera aktuell nicht verfuegbar. Bitte kurz warten und erneut oeffnen.")
                null
            }
        }
        val cam = camera ?: return false
        val resolvedCameraId = runCatching { Camera2CameraInfo.from(cam.cameraInfo).cameraId }.getOrNull()
        configureNativeZoomFallback()
        announcePipeline("PIPELINE=CAMERAX_FALLBACK, cameraId=${resolvedCameraId ?: "unknown"}, fps=${settings.targetFps}")
        toggleTorch(settings.torchEnabled)
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

        synchronized(codecLock) {
            ensureCodec(profile.size.width, profile.size.height, settings, useSurfaceInput = true)
        }
        val inputSurface = codecInputSurface
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
            initialZoomRatio = currentNativeZoomRatio
        )
        startHighSpeedDrainLoop()
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

        synchronized(codecLock) {
            ensureCodec(profile.size.width, profile.size.height, settings, useSurfaceInput = true)
        }
        val inputSurface = codecInputSurface
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
            }
        ).also { camera2Session = it }
        session.start(
            profile = profile,
            previewView = highSpeedPreviewView,
            encoderSurface = inputSurface,
            torch = requestedTorchEnabled,
            initialZoomRatio = currentNativeZoomRatio
        )
        startHighSpeedDrainLoop()
    }

    private fun fallbackToCameraX(settings: PrecorderSettings) {
        stopHighSpeedSession()
        activePipeline = CapturePipeline.CAMERAX
        manualSensorModeActive = false
        activeCamera2Profile = null
        activeHighSpeedProfile = null
        configureNativeZoomFallback()
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

    private fun configureNativeZoomForCharacteristics(characteristics: CameraCharacteristics) {
        availableNativeZoomSteps = computeNativeZoomSteps(characteristics)
        currentNativeZoomRatio = nearestZoomStep(currentNativeZoomRatio)
        notifyNativeZoomChanged()
    }

    private fun configureNativeZoomFallback() {
        availableNativeZoomSteps = listOf(1f)
        currentNativeZoomRatio = 1f
        notifyNativeZoomChanged()
    }

    private fun computeNativeZoomSteps(characteristics: CameraCharacteristics): List<Float> {
        val preferred = PREFERRED_NATIVE_ZOOM_STEPS
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return listOf(1f)
        val range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: return listOf(1f)
        val filtered = preferred.filter { it in range.lower..range.upper }
        return when {
            filtered.isEmpty() -> listOf(1f.coerceIn(range.lower, range.upper))
            filtered.any { abs(it - 1f) < ZOOM_EPSILON } -> filtered
            else -> (filtered + 1f.coerceIn(range.lower, range.upper)).distinct().sorted()
        }
    }

    private fun nearestZoomStep(requested: Float): Float {
        return availableNativeZoomSteps.minByOrNull { abs(it - requested) } ?: 1f
    }

    private fun notifyNativeZoomChanged() {
        onNativeZoomChanged?.invoke(availableNativeZoomSteps, currentNativeZoomRatio)
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

    private fun maybeApplyRuntimeExposureOverride(cam: Camera, settings: PrecorderSettings) {
        if (!pendingExposureApply) return
        val now = System.currentTimeMillis()
        if (now - lastExposureApplyMs < 250L) return
        lastExposureApplyMs = now
        pendingExposureApply = !applyRuntimeExposureOverride(cam, settings)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun applyRuntimeExposureOverride(cam: Camera, settings: PrecorderSettings): Boolean {
        // Keep AE active so exposure can still auto-correct (especially overexposure) at high FPS.
        return true
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
            synchronized(bufferLock) {
                ringBuffer = EncodedFrameRingBuffer(retentionUs)
            }
            notifyBufferProgress(0f)
        }

        if (formatReady) return

        formatWidth = width
        formatHeight = height
        codecUsesSurfaceInput = useSurfaceInput

        val mediaFormat = MediaFormat.createVideoFormat(MIME_TYPE, formatWidth, formatHeight).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                if (useSurfaceInput) MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, estimateBitrate(settings.targetFps, formatWidth, formatHeight))
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.targetFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        codec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
            configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            if (useSurfaceInput) {
                codecInputSurface = createInputSurface()
            }
            start()
        }
        formatReady = true
    }

    private fun encodeImage(image: ImageProxy, settings: PrecorderSettings) {
        try {
            camera?.let {
                maybeApplyRuntimeFpsOverride(it, settings)
                maybeApplyRuntimeExposureOverride(it, settings)
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
                    if (buffer != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(bytes)
                        synchronized(bufferLock) {
                            ringBuffer.append(
                                EncodedFrame(
                                    data = bytes,
                                    presentationTimeUs = info.presentationTimeUs,
                                    flags = info.flags,
                                    isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                )
                            )
                        }
                        notifyBufferProgress(getBufferFillRatio())
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

    fun exportClip(settings: PrecorderSettings, deviceSurfaceRotation: Int, onDone: (Uri?) -> Unit) {
        ioScope.launch {
            // 2-buffer approach: swap out the active ring in O(1), continue recording immediately.
            val exportBuffer = synchronized(bufferLock) {
                val frozen = ringBuffer
                ringBuffer = EncodedFrameRingBuffer(retentionUs)
                frozen
            }
            notifyBufferProgress(0f)

            val allFrames = exportBuffer.snapshot().filterNot { it.isConfig }
            val format = encoderOutputFormat
            if (allFrames.size < 8 || format == null) {
                onDone(null)
                return@launch
            }


            val firstKeyIndex = allFrames.indexOfFirst { (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 }
            if (firstKeyIndex < 0) {
                onDone(null)
                return@launch
            }
            val frames = allFrames.subList(firstKeyIndex, allFrames.size)

            val output = createOutputTarget(settings) ?: run {
                onDone(null)
                return@launch
            }

            val success = runCatching {
                val outputFd = output.fileDescriptor ?: error("No output file descriptor")
                val muxer = MediaMuxer(outputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var muxerStarted = false
                var wroteSamples = false
                try {
                    muxer.setOrientationHint(computeOrientationHint(settings, deviceSurfaceRotation))
                    val track = muxer.addTrack(format)
                    muxer.start()
                    muxerStarted = true

                    val stepUs = 1_000_000L / settings.playbackFps.coerceAtLeast(1)
                    frames.forEachIndexed { index, frame ->
                        val info = MediaCodec.BufferInfo().apply {
                            offset = 0
                            size = frame.data.size
                            presentationTimeUs = index * stepUs
                            flags = if ((frame.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        }
                        muxer.writeSampleData(track, frame.asByteBuffer(), info)
                        wroteSamples = true
                    }
                    if (!wroteSamples) error("No media samples written")
                } finally {
                    if (muxerStarted && wroteSamples) runCatching { muxer.stop() }
                    runCatching { muxer.release() }
                }
                output.markCompleted()
                true
            }.onFailure {
                output.cleanupOnFailure()
                Log.e(TAG, "Export failed", it)
            }.getOrDefault(false)

            onDone(if (success) output.uri else null)
        }
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
        activeHighSpeedProfile = null
        configureNativeZoomFallback()
        resetEncodingState()
    }

    private fun resetEncodingState() {
        synchronized(codecLock) {
            synchronized(bufferLock) {
                ringBuffer.clear()
            }
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
            pendingExposureApply = true
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

    private fun announcePipeline(message: String) {
        Log.i(TAG, message)
        onPipelineChanged?.invoke(message)
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

        val sensorOrientation = runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = settings.cameraId ?: return@runCatching 90
            val chars = manager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        }.getOrDefault(90)

        return if (settings.lensFacing == CameraSelector.LENS_FACING_FRONT) {
            (360 - ((sensorOrientation + deviceDegrees) % 360)) % 360
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
                    return OutputTarget(uri = file.uri, fileDescriptor = pfd.fileDescriptor, closeable = pfd)
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
        return (bpp * fps * width * height).toInt().coerceIn(4_000_000, 80_000_000)
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
        private val PREFERRED_NATIVE_ZOOM_STEPS = listOf(0.6f, 1f, 3f, 5f)
        private const val ZOOM_EPSILON = 0.001f
    }
}
