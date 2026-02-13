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
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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

@OptIn(ExperimentalCamera2Interop::class)
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

    private var retentionUs: Long = 5_000_000
    private var currentConfigKey: String? = null
    private var formatWidth = 0
    private var formatHeight = 0
    private var formatReady = false

    fun bind(owner: LifecycleOwner, previewView: PreviewView, settings: PrecorderSettings) {
        retentionUs = settings.loopSeconds * 1_000_000L
        val key = "${settings.cameraId}|${settings.lensFacing}|${settings.targetFps}|${settings.aspectRatio}"

        if (key != currentConfigKey) {
            resetEncodingState(clearBuffer = true)
            currentConfigKey = key
        } else {
            // Bei erneutem Binden trotzdem Puffer leeren, damit nur konsistente Frames enthalten sind.
            ringBuffer.clear()
            notifyBufferProgress(0f)
        }
        ringBuffer = EncodedFrameRingBuffer(retentionUs)

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

        selectFpsRange(settings.cameraId, settings.targetFps)?.let { fpsRange ->
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        }

        val preview = previewBuilder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        analysis = analysisBuilder.build().also { analyzer ->
            analyzer.setAnalyzer(analyzerExecutor) { image ->
                encodeImage(image, settings)
            }
        }

        camera = provider.bindToLifecycle(owner, selector, preview, analysis)
        toggleTorch(settings.torchEnabled)
        applyZoom(settings.digitalZoomRatio)
    }

    private fun Preview.Builder.applyAspect(aspect: String): Preview.Builder {
        when (aspect) {
            "4:3" -> setTargetAspectRatio(AspectRatio.RATIO_4_3)
            "16:10" -> setTargetResolution(Size(1280, 800))
            else -> setTargetAspectRatio(AspectRatio.RATIO_16_9)
        }
        return this
    }

    private fun ImageAnalysis.Builder.applyAspect(aspect: String): ImageAnalysis.Builder {
        when (aspect) {
            "4:3" -> setTargetAspectRatio(AspectRatio.RATIO_4_3)
            "16:10" -> setTargetResolution(Size(1280, 800))
            else -> setTargetAspectRatio(AspectRatio.RATIO_16_9)
        }
        return this
    }

    private fun selectFpsRange(cameraId: String?, targetFps: Int): Range<Int>? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cameraId ?: return null
        val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return null
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        if (ranges.isEmpty()) return null

        return ranges
            .filter { targetFps in it.lower..it.upper }
            .minByOrNull { abs(it.upper - targetFps) }
            ?: ranges.maxByOrNull { it.upper }
    }

    private fun ensureCodec(image: ImageProxy, settings: PrecorderSettings) {
        val width = image.width
        val height = image.height

        if (formatReady && (width != formatWidth || height != formatHeight)) {
            // Formatwechsel (z.B. 16:9 -> 4:3): Encoder+Puffer sauber neu aufbauen.
            resetEncodingState(clearBuffer = true)
            ringBuffer = EncodedFrameRingBuffer(retentionUs)
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
            ensureCodec(image, settings)
            val activeCodec = codec ?: return
            queueInput(activeCodec, image)
            drainCodec(activeCodec)
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
        codec.queueInputBuffer(inputIndex, 0, yuv.size, image.imageInfo.timestamp / 1_000, 0)
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
                        ringBuffer.append(
                            EncodedFrame(
                                data = bytes,
                                presentationTimeUs = info.presentationTimeUs,
                                flags = info.flags,
                                isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            )
                        )
                        notifyBufferProgress(getBufferFillRatio())
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
            val fillRatio = getBufferFillRatio()
            if (fillRatio < 0.98f) {
                onDone(null)
                return@launch
            }

            val frames = ringBuffer.snapshot().filterNot { it.isConfig }
            val format = encoderOutputFormat
            if (frames.size < 8 || format == null) {
                onDone(null)
                return@launch
            }

            val output = createOutputTarget(settings) ?: run {
                onDone(null)
                return@launch
            }

            val success = runCatching {
                val outputFd = output.fileDescriptor ?: error("No output file descriptor")
                MediaMuxer(outputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).useMuxer { muxer ->
                    muxer.setOrientationHint(computeOrientationHint(settings, deviceSurfaceRotation))
                    val track = muxer.addTrack(format)
                    muxer.start()

                    val stepUs = 1_000_000L / settings.playbackFps.coerceAtLeast(1)
                    frames.forEachIndexed { index, frame ->
                        val info = MediaCodec.BufferInfo().apply {
                            offset = 0
                            size = frame.data.size
                            presentationTimeUs = index * stepUs
                            flags = if ((frame.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        }
                        muxer.writeSampleData(track, frame.asByteBuffer(), info)
                    }
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
        resetEncodingState(clearBuffer = true)
    }

    private fun resetEncodingState(clearBuffer: Boolean) {
        if (clearBuffer) {
            ringBuffer.clear()
            notifyBufferProgress(0f)
        }
        encoderOutputFormat = null
        formatReady = false
        formatWidth = 0
        formatHeight = 0
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    private fun notifyBufferProgress(value: Float) {
        onBufferFillChanged?.invoke(value.coerceIn(0f, 1f))
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
        val out = ByteArray(ySize + uvSize)

        copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, image.width, image.height, out)

        val chromaHeight = image.height / 2
        val chromaWidth = image.width / 2
        var offset = ySize
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
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
        var offset = 0
        for (row in 0 until height) {
            for (col in 0 until width) {
                val index = row * rowStride + col * pixelStride
                out[offset++] = planeBuffer.get(index)
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

private inline fun MediaMuxer.useMuxer(block: (MediaMuxer) -> Unit) {
    try {
        block(this)
    } finally {
        runCatching { stop() }
        runCatching { release() }
    }
}
