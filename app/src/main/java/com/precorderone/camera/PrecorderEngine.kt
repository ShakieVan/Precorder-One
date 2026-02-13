package com.precorderone.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.precorderone.data.PrecorderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PrecorderEngine(private val context: Context) {
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var codec: MediaCodec? = null
    private var ringBuffer: EncodedFrameRingBuffer = EncodedFrameRingBuffer(5_000_000)
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var formatWidth = 1280
    private var formatHeight = 720
    private var formatReady = false

    fun bind(owner: LifecycleOwner, previewView: PreviewView, settings: PrecorderSettings) {
        ringBuffer = EncodedFrameRingBuffer(settings.loopSeconds * 1_000_000L)
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

    private fun bindInternal(owner: LifecycleOwner, previewView: PreviewView, settings: PrecorderSettings) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build().also { analyzer ->
                analyzer.setAnalyzer(analyzerExecutor) { image ->
                    encodeImage(image, settings)
                }
            }

        val selectorBuilder = CameraSelector.Builder().requireLensFacing(settings.lensFacing)
        settings.cameraId?.let { desiredId ->
            selectorBuilder.addCameraFilter { infos ->
                infos.filter { info ->
                    runCatching { Camera2CameraInfo.from(info).cameraId == desiredId }.getOrDefault(false)
                }
            }
        }
        val selector = selectorBuilder.build()

        camera = provider.bindToLifecycle(owner, selector, preview, analysis)
        toggleTorch(settings.torchEnabled)
        applyZoom(settings.digitalZoomRatio)
    }

    private fun ensureCodec(image: ImageProxy, settings: PrecorderSettings) {
        if (formatReady) return
        formatWidth = image.width
        formatHeight = image.height

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
        codec.queueInputBuffer(
            inputIndex,
            0,
            yuv.size,
            image.imageInfo.timestamp / 1_000,
            0
        )
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
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }

                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> return
                else -> return
            }
        }
    }

    fun exportClip(settings: PrecorderSettings, onDone: (Uri?) -> Unit) {
        ioScope.launch {
            val frames = ringBuffer.snapshot()
            if (frames.isEmpty()) {
                onDone(null)
                return@launch
            }
            val uri = createOutputUri() ?: run {
                onDone(null)
                return@launch
            }

            val path = uriToPath(uri)
            if (path == null) {
                onDone(null)
                return@launch
            }
            runCatching {
                val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val outputFormat = MediaFormat.createVideoFormat(MIME_TYPE, formatWidth, formatHeight).apply {
                    setInteger(MediaFormat.KEY_FRAME_RATE, settings.playbackFps)
                    setInteger(MediaFormat.KEY_BIT_RATE, estimateBitrate(settings.playbackFps, formatWidth, formatHeight))
                }
                val track = muxer.addTrack(outputFormat)
                muxer.start()

                val firstPts = frames.first().presentationTimeUs
                frames.forEach { frame ->
                    if (frame.isConfig) return@forEach
                    val info = MediaCodec.BufferInfo().apply {
                        offset = 0
                        size = frame.data.size
                        presentationTimeUs = frame.presentationTimeUs - firstPts
                        flags = if ((frame.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    }
                    muxer.writeSampleData(track, frame.asByteBuffer(), info)
                }
                muxer.stop()
                muxer.release()
            }.onFailure {
                Log.e(TAG, "Export failed", it)
            }
            onDone(uri)
        }
    }

    fun release() {
        analysis?.clearAnalyzer()
        analyzerExecutor.shutdown()
        codec?.stop()
        codec?.release()
    }

    private fun createOutputUri(): Uri? {
        val resolver = context.contentResolver
        val fileName = "precorder_${System.currentTimeMillis()}.mp4"

        val relativePath = "DCIM/Precorder-One"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
        }

        return resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }

    private fun uriToPath(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        val projection = arrayOf(MediaStore.Video.Media.DATA)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
            if (idx >= 0 && cursor.moveToFirst()) {
                return cursor.getString(idx)
            }
        }

        val fallbackDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Precorder-One")
        if (!fallbackDir.exists()) fallbackDir.mkdirs()
        return File(fallbackDir, "precorder_${System.currentTimeMillis()}.mp4").absolutePath
    }

    private fun yuv420888ToNv12(image: ImageProxy): ByteArray {
        require(image.format == ImageFormat.YUV_420_888)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val ySize = image.width * image.height
        val uvSize = ySize / 2
        val out = ByteArray(ySize + uvSize)

        // Copy Y
        copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, image.width, image.height, out, 0)

        // Interleave UV (NV12)
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
        out: ByteArray,
        outOffset: Int
    ) {
        var offset = outOffset
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

    companion object {
        private const val TAG = "PrecorderEngine"
        private const val MIME_TYPE = "video/avc"
    }
}
