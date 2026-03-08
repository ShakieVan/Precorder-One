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
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
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
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var codec: MediaCodec? = null
    private var encoderOutputFormat: MediaFormat? = null
    private var ringBuffer: EncodedFrameRingBuffer = EncodedFrameRingBuffer(5_000_000)
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var onBufferFillChanged: ((Float) -> Unit)? = null
    var onMeasuredFpsChanged: ((Float) -> Unit)? = null
    var onDebugStatsChanged: ((Float, Float, Float, Float) -> Unit)? = null
    var onProfileFallback: ((String) -> Unit)? = null

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


    fun getManualExposurePercent(): Int = manualExposurePercent

    fun setManualExposurePercent(percent: Int) {
        manualExposurePercent = percent.coerceIn(20, 100)
        pendingExposureApply = true
        val cam = camera ?: return
        val settings = boundSettings ?: return
        maybeApplyRuntimeExposureOverride(cam, settings)
    }

    fun bind(owner: LifecycleOwner, previewView: PreviewView, settings: PrecorderSettings) {
        boundOwner = owner
        boundPreviewView = previewView
        boundSettings = settings
        pendingExposureApply = true
        pendingFpsApply = true
        bindStartMs = System.currentTimeMillis()
        fallbackApplied = false
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

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindInternal(owner, previewView, settings)
        }, ContextCompat.getMainExecutor(context))
    }

    fun toggleTorch(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }

    fun applyZoom(digitalZoom: Float) {
        camera?.cameraControl?.setZoomRatio(digitalZoom)
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

    fun getBufferFillRatio(): Float {
        val snapshot = ringBuffer.snapshot()
        if (snapshot.size < 2) return 0f
        val duration = snapshot.last().presentationTimeUs - snapshot.first().presentationTimeUs
        return (duration.toFloat() / retentionUs.toFloat()).coerceIn(0f, 1f)
    }

    private fun bindInternal(owner: LifecycleOwner, previewView: PreviewView, settings: PrecorderSettings) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val selectorBuilder = CameraSelector.Builder().requireLensFacing(settings.lensFacing)
        settings.cameraId?.let { desiredId ->
            selectorBuilder.addCameraFilter { infos ->
                infos.filter { info -> Camera2CameraInfo.from(info).cameraId == desiredId }
            }
        }
        val selector = selectorBuilder.build()

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
        }.getOrElse {
            Log.w(TAG, "Selected camera could not be bound, falling back to lens facing only", it)
            val fallbackSelector = CameraSelector.Builder().requireLensFacing(settings.lensFacing).build()
            provider.bindToLifecycle(owner, fallbackSelector, preview, analysis)
        }
        toggleTorch(settings.torchEnabled)
        applyZoom(settings.digitalZoomRatio)
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

    private fun shouldUseManualSensorMode(targetFps: Int, chars: CameraCharacteristics): Boolean {
        return targetFps >= 120 && supportsManualSensor(chars)
    }

    private fun selectFpsRange(cameraId: String?, targetFps: Int): Range<Int>? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cameraId ?: return null
        val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return null
        return selectFpsRange(chars, targetFps)
    }

    private fun selectFpsRange(chars: CameraCharacteristics, targetFps: Int): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        if (ranges.isEmpty()) return null

        ranges.firstOrNull { it.lower == targetFps && it.upper == targetFps }?.let { return it }
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
        val targetFps = settings.targetFps
        val cameraId = settings.cameraId ?: return
        val chars = runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.getCameraCharacteristics(cameraId)
        }.getOrNull() ?: return

        val manualMode = shouldUseManualSensorMode(targetFps, chars)
        manualSensorModeActive = manualMode

        val previewExt = Camera2Interop.Extender(previewBuilder)
        val analysisExt = Camera2Interop.Extender(analysisBuilder)
        listOf(previewExt, analysisExt).forEach { ext ->
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }

        if (manualMode) {
            onProfileFallback?.invoke("FPS-Prioritaet aktiv: Belichtung manuell ueber Slider")
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

        val manualMode = shouldUseManualSensorMode(settings.targetFps, chars)
        if (manualMode) return true

        val fpsRange = selectFpsRange(chars, settings.targetFps) ?: return false
        val options = CaptureRequestOptions.Builder()
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

    private fun applyRuntimeExposureOverride(cam: Camera, settings: PrecorderSettings): Boolean {
        val cameraId = runCatching { Camera2CameraInfo.from(cam.cameraInfo).cameraId }
            .getOrElse { settings.cameraId }
            ?: return false
        val chars = runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.getCameraCharacteristics(cameraId)
        }.getOrNull() ?: return false
        if (!shouldUseManualSensorMode(settings.targetFps, chars)) return true

        val frameDurationNs = (1_000_000_000L / settings.targetFps.coerceAtLeast(1))
        val exposureNs = (frameDurationNs * manualExposurePercent.coerceIn(20, 100) / 100L).coerceIn(500_000L, frameDurationNs)
        val sensitivity = chooseIso(chars)

        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, sensitivity)
            .setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            .build()

        return runCatching {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options)
            true
        }.getOrElse {
            Log.w(TAG, "Exposure override deferred: ${it.message}")
            false
        }
    }

    private fun supportsManualSensor(chars: CameraCharacteristics): Boolean {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        return caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
    }

    private fun chooseIso(chars: CameraCharacteristics): Int {
        val range = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return 800
        return 800.coerceIn(range.lower, range.upper)
    }

    private fun ensureCodec(image: ImageProxy, settings: PrecorderSettings) {
        val width = image.width
        val height = image.height

        if (formatReady && (width != formatWidth || height != formatHeight)) {
            // Analyzer format changed: cleanly restart encoder state.
            formatReady = false
            formatWidth = 0
            formatHeight = 0
            encoderOutputFormat = null
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
            synchronized(bufferLock) {
                ringBuffer = EncodedFrameRingBuffer(retentionUs)
            }
            notifyBufferProgress(0f)
        }

        if (formatReady) return

        formatWidth = width
        formatHeight = height

        val mediaFormat = MediaFormat.createVideoFormat(MIME_TYPE, formatWidth, formatHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, estimateBitrate(settings.targetFps, formatWidth, formatHeight))
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.targetFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        codec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
            configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
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
                ensureCodec(image, settings)
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
        analysis?.clearAnalyzer()
        analyzerExecutor.shutdown()
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
            reusableYuvBuffer = null
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
        }
    }

    private fun notifyBufferProgress(value: Float) {
        onBufferFillChanged?.invoke(value.coerceIn(0f, 1f))
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
    }
}
