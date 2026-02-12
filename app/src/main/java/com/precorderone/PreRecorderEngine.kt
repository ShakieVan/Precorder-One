package com.precorderone

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import androidx.core.content.getSystemService
import java.io.File

class PreRecorderEngine(private val context: Context) {
    private val cameraManager: CameraManager = context.getSystemService()!!
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var encoderInputSurface: Surface? = null
    private var captureHandlerThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var activeFormat: MediaFormat? = null
    private var encodedBuffer = EncodedSampleBuffer(5_000_000)

    fun cameraIds(): Array<String> = cameraManager.cameraIdList

    fun maxZoomRatio(cameraId: String): Float {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        return characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
    }

    fun start(surfaceTexture: SurfaceTexture, settings: AppSettings, onReady: (Boolean) -> Unit) {
        stop()
        encodedBuffer = EncodedSampleBuffer(settings.loopSeconds * 1_000_000L)
        captureHandlerThread = HandlerThread("precorder-camera").also { it.start() }
        captureHandler = Handler(captureHandlerThread!!.looper)

        val selectedCamera = settings.cameraId ?: cameraManager.cameraIdList.firstOrNull() ?: run {
            onReady(false); return
        }

        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            onReady(false)
            return
        }

        cameraManager.openCamera(selectedCamera, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                setupEncoder(settings)

                val previewSurface = Surface(surfaceTexture)
                val codecSurface = encoderInputSurface ?: run { onReady(false); return }
                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(previewSurface)
                    addTarget(codecSurface)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(settings.targetFps, settings.targetFps))
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    set(CaptureRequest.FLASH_MODE, if (settings.torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                    applyZoom(this, selectedCamera, settings.zoomRatio)
                }

                camera.createCaptureSession(listOf(previewSurface, codecSurface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        session = captureSession
                        captureSession.setRepeatingRequest(req.build(), null, captureHandler)
                        onReady(true)
                    }

                    override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                        onReady(false)
                    }
                }, captureHandler)
            }

            override fun onDisconnected(camera: CameraDevice) {
                onReady(false)
                stop()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                onReady(false)
                stop()
            }
        }, captureHandler)
    }

    private fun setupEncoder(settings: AppSettings) {
        val width = 1280
        val height = 720
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.saveFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderInputSurface = createInputSurface()
            setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null) encodedBuffer.add(buffer, info)
                    codec.releaseOutputBuffer(index, false)
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = Unit

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    activeFormat = format
                }
            })
            start()
        }
    }

    fun saveBuffer(settings: AppSettings): File? {
        val format = activeFormat ?: return null
        val outDir = File(settings.storageDir)
        if (!outDir.exists()) outDir.mkdirs()
        val file = File(outDir, "precorder_${System.currentTimeMillis()}.mp4")
        return if (encodedBuffer.writeToMp4(file, format)) file else null
    }

    fun stop() {
        session?.close()
        session = null
        cameraDevice?.close()
        cameraDevice = null
        encoder?.stop()
        encoder?.release()
        encoder = null
        encoderInputSurface?.release()
        encoderInputSurface = null
        activeFormat = null
        captureHandlerThread?.quitSafely()
        captureHandlerThread = null
        captureHandler = null
        encodedBuffer.clear()
    }

    private fun applyZoom(builder: CaptureRequest.Builder, cameraId: String, zoomRatio: Float) {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val zoom = zoomRatio.coerceIn(1f, maxZoom)

        val cropW = (sensorRect.width() / zoom).toInt()
        val cropH = (sensorRect.height() / zoom).toInt()
        val left = (sensorRect.width() - cropW) / 2
        val top = (sensorRect.height() - cropH) / 2
        val crop = Rect(left, top, left + cropW, top + cropH)
        builder.set(CaptureRequest.SCALER_CROP_REGION, crop)
    }
}
