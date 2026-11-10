package com.precorderone.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Burns the running time into a saved clip: decoder -> OpenGL (picture plus timecode) -> encoder -> MP4.
 *
 * This happens when a clip is saved, never while recording: the live path (camera -> encoder surface -> ring) stays
 * as lean as it is. The picture never leaves the graphics memory; only the compressed frames pass through the CPU.
 */
internal object TimecodeBurner {
    private const val MIME_TYPE = "video/avc"
    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val FRAME_WAIT_MS = 5_000L
    private const val STALL_TIMEOUT_MS = 15_000L

    /**
     * Re-encodes [input] (a real-time clip as [ClipMuxer.write] makes it) into [output] with the timecode at the
     * bottom left of the picture as it is shown with [orientationHint].
     *
     * The timecode counts the recording time from the first frame. The clip itself is stretched by [timeScale]
     * (recording fps / playback fps), so in slow motion the timecode runs slower than the playback. Sound is copied
     * unchanged, and only at real-time speed. Throws on failure; [output] is then incomplete.
     */
    fun burn(
        input: File,
        output: FileDescriptor,
        orientationHint: Int,
        timeScale: Double,
        frameRate: Int,
        bitRate: Int,
        onProgress: (Float) -> Unit = {}
    ) {
        val extractor = MediaExtractor()
        var audioExtractor: MediaExtractor? = null
        var decoderRef: MediaCodec? = null
        var encoderRef: MediaCodec? = null
        var encoderSurfaceRef: Surface? = null
        var stageRef: GlStage? = null
        var muxerRef: MediaMuxer? = null
        var failed = false
        var muxerStarted = false
        var wroteVideo = false
        try {
            extractor.setDataSource(input.absolutePath)
            val videoIndex = trackIndex(extractor, "video/")
            check(videoIndex >= 0) { "No video track" }
            extractor.selectTrack(videoIndex)
            val sourceFormat = extractor.getTrackFormat(videoIndex)
            val width = sourceFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = sourceFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val durationUs = if (sourceFormat.containsKey(MediaFormat.KEY_DURATION)) sourceFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            val audioIndex = if (timeScale == 1.0) trackIndex(extractor, "audio/") else -1
            val audioFormat = if (audioIndex >= 0) extractor.getTrackFormat(audioIndex) else null
            if (audioIndex >= 0) {
                audioExtractor = MediaExtractor().apply {
                    setDataSource(input.absolutePath)
                    selectTrack(audioIndex)
                }
            }

            val encoder = MediaCodec.createEncoderByType(MIME_TYPE).also { encoderRef = it }
            encoder.configure(encoderFormat(sourceFormat, width, height, frameRate, bitRate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderSurface = encoder.createInputSurface().also { encoderSurfaceRef = it }
            encoder.start()

            val stage = GlStage(encoderSurface, width, height, orientationHint).also { stageRef = it }

            // With a rotation in its format the decoder would turn the frames on their way to the surface. They have
            // to stay as stored; the turn remains a note in the new file, as in the original.
            sourceFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
            val decoder = MediaCodec.createDecoderByType(sourceFormat.getString(MediaFormat.KEY_MIME)!!).also { decoderRef = it }
            decoder.configure(sourceFormat, stage.decoderSurface, null, 0)
            decoder.start()

            val muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxerRef = it }
            muxer.setOrientationHint(orientationHint)
            var videoTrack = -1
            var audioTrack = -1

            val info = MediaCodec.BufferInfo()
            val audioInfo = MediaCodec.BufferInfo()
            val audioBuffer = audioFormat?.let { ByteBuffer.allocateDirect(maxInputSize(it, 64 * 1024)) }
            val glyphs = IntArray(Timecode.LENGTH)
            var firstPtsUs = -1L
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var lastPercent = -1
            var lastActivityMs = SystemClock.elapsedRealtime()

            // Sound up to the picture that is written next, so both tracks lie side by side in the file.
            fun writeAudioUntil(videoPtsUs: Long) {
                val source = audioExtractor ?: return
                val buffer = audioBuffer ?: return
                while (true) {
                    val sampleTimeUs = source.sampleTime
                    if (sampleTimeUs < 0) return
                    val ptsUs = (sampleTimeUs - firstPtsUs.coerceAtLeast(0L)).coerceAtLeast(0L)
                    if (ptsUs > videoPtsUs) return
                    val size = source.readSampleData(buffer, 0)
                    if (size < 0) return
                    val flags = if ((source.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    audioInfo.set(0, size, ptsUs, flags)
                    muxer.writeSampleData(audioTrack, buffer, audioInfo)
                    source.advance()
                }
            }

            while (!encoderDone) {
                var active = false

                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(inIndex)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        active = true
                    }
                }

                if (!decoderDone) {
                    val outIndex = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                    if (outIndex >= 0) {
                        val render = info.size > 0
                        val ptsUs = info.presentationTimeUs
                        val endOfStream = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(outIndex, render)
                        if (render) {
                            if (firstPtsUs < 0) firstPtsUs = ptsUs
                            val clipUs = ptsUs - firstPtsUs
                            stage.awaitFrame()
                            stage.draw(Timecode.glyphs(clipUs, glyphs))
                            stage.present((clipUs * timeScale).toLong() * 1000L)
                            if (durationUs > 0) {
                                val percent = (clipUs * 100 / durationUs).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent / 100f)
                                }
                            }
                        }
                        if (endOfStream) {
                            encoder.signalEndOfInputStream()
                            decoderDone = true
                        }
                        active = true
                    }
                }

                // Without waiting while pictures still come in; the encoder must never run full.
                while (true) {
                    val encIndex = encoder.dequeueOutputBuffer(info, if (decoderDone) DEQUEUE_TIMEOUT_US else 0L)
                    if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!muxerStarted) { "Encoder changed its format twice" }
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        if (audioFormat != null) audioTrack = muxer.addTrack(audioFormat)
                        muxer.start()
                        muxerStarted = true
                        active = true
                    } else if (encIndex >= 0) {
                        val data = encoder.getOutputBuffer(encIndex)!!
                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (info.size > 0 && !isConfig) {
                            check(muxerStarted) { "Encoder delivered a frame before its format" }
                            writeAudioUntil(info.presentationTimeUs)
                            muxer.writeSampleData(videoTrack, data, info)
                            wroteVideo = true
                        }
                        val endOfStream = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        encoder.releaseOutputBuffer(encIndex, false)
                        active = true
                        if (endOfStream) {
                            encoderDone = true
                            break
                        }
                    } else {
                        break
                    }
                }

                val nowMs = SystemClock.elapsedRealtime()
                if (active) lastActivityMs = nowMs else check(nowMs - lastActivityMs < STALL_TIMEOUT_MS) { "Codecs stalled" }
            }
            check(wroteVideo) { "No picture written" }
            writeAudioUntil(Long.MAX_VALUE)
            onProgress(1f)
        } catch (e: Throwable) {
            failed = true
            throw e
        } finally {
            runCatching { decoderRef?.stop() }
            runCatching { decoderRef?.release() }
            runCatching { stageRef?.release() }
            runCatching { encoderRef?.stop() }
            runCatching { encoderRef?.release() }
            runCatching { encoderSurfaceRef?.release() }
            // stop() throws when nothing was written; the caller sees the first error instead.
            val stopError = if (muxerStarted && wroteVideo) runCatching { muxerRef?.stop() }.exceptionOrNull() else null
            runCatching { muxerRef?.release() }
            runCatching { extractor.release() }
            runCatching { audioExtractor?.release() }
            if (stopError != null && !failed) throw stopError
        }
    }

    private fun encoderFormat(source: MediaFormat, width: Int, height: Int, frameRate: Int, bitRate: Int): MediaFormat =
        MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate.coerceAtLeast(1))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Same colours as the original: otherwise decoder and encoder may each assume another standard.
            listOf(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_TRANSFER).forEach { key ->
                if (source.containsKey(key)) setInteger(key, source.getInteger(key))
            }
        }

    internal fun trackIndex(extractor: MediaExtractor, mimePrefix: String): Int =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true
        } ?: -1

    internal fun maxInputSize(format: MediaFormat, fallback: Int): Int =
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(fallback) else fallback

    /** Glyphs 0..9 and the colon side by side in one texture, plus a filled cell for the box behind the text. */
    private class GlyphAtlas(textSize: Float) {
        val digitWidth: Int
        val colonWidth: Int
        val glyphHeight: Int
        val bitmap: Bitmap
        val left = FloatArray(Timecode.COLON + 1)
        val right = FloatArray(Timecode.COLON + 1)
        val solid: Float

        init {
            // Monospace: every digit is equally wide, so the text does not wobble while it counts. It is also the one
            // typeface a user's system font setting does not replace.
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                this.textSize = textSize
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
            }
            val metrics = paint.fontMetricsInt
            glyphHeight = metrics.descent - metrics.ascent
            digitWidth = ceil((0..9).maxOf { paint.measureText(it.toString()) }).toInt()
            colonWidth = (digitWidth * 0.6f).roundToInt()
            val width = 10 * (digitWidth + GAP) + colonWidth + GAP + SOLID_WIDTH
            bitmap = Bitmap.createBitmap(width, glyphHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            var x = 0
            for (glyph in 0..Timecode.COLON) {
                val cell = if (glyph == Timecode.COLON) colonWidth else digitWidth
                canvas.drawText(if (glyph == Timecode.COLON) ":" else glyph.toString(), x + cell / 2f, -metrics.ascent.toFloat(), paint)
                left[glyph] = x / width.toFloat()
                right[glyph] = (x + cell) / width.toFloat()
                x += cell + GAP
            }
            canvas.drawRect(x.toFloat(), 0f, (x + SOLID_WIDTH).toFloat(), glyphHeight.toFloat(), paint)
            solid = (x + SOLID_WIDTH / 2f) / width
        }

        private companion object {
            const val GAP = 2
            const val SOLID_WIDTH = 4
        }
    }

    /**
     * The OpenGL step between decoder and encoder, bound to the thread that creates it: [decoderSurface] takes the
     * decoded frames, [draw] paints the newest one with the timecode on the encoder's surface, [present] hands it over.
     */
    private class GlStage(encoderSurface: Surface, private val width: Int, private val height: Int, orientationHint: Int) {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private val callbackThread = HandlerThread("precorder-timecode").apply { start() }
        private val lock = ReentrantLock()
        private val frameArrived = lock.newCondition()
        private var frameAvailable = false
        private var surfaceTexture: SurfaceTexture? = null
        var decoderSurface: Surface? = null
            private set

        private var videoProgram = 0
        private var overlayProgram = 0
        private var videoTexture = 0
        private var atlasTexture = 0
        private val texMatrix = FloatArray(16)
        private val videoVertices = floatBuffer(
            floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
            )
        )
        private val overlayVertices = floatBuffer(FloatArray((Timecode.LENGTH + 1) * VERTICES_PER_QUAD * FLOATS_PER_VERTEX))
        private val quad = FloatArray(VERTICES_PER_QUAD * FLOATS_PER_VERTEX)
        private val corners = FloatArray(8)
        private lateinit var atlas: GlyphAtlas
        private lateinit var layout: TimecodeLayout

        init {
            try {
                setUpEgl(encoderSurface)
                setUpGl(orientationHint)
            } catch (e: Exception) {
                release()
                throw e
            }
        }

        private fun setUpEgl(encoderSurface: Surface) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL init failed" }
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No EGL config for recording" }
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "No EGL context" }
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "No EGL surface on the encoder" }
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "EGL makeCurrent failed" }
        }

        private fun setUpGl(orientationHint: Int) {
            videoProgram = program(VIDEO_VERTEX_SHADER, VIDEO_FRAGMENT_SHADER)
            overlayProgram = program(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER)

            val textures = IntArray(2)
            GLES20.glGenTextures(2, textures, 0)
            videoTexture = textures[0]
            atlasTexture = textures[1]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexture)
            setTextureParameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)

            val turned = orientationHint == 90 || orientationHint == 270
            atlas = GlyphAtlas(Timecode.textSize(if (turned) height else width, if (turned) width else height))
            layout = TimecodeLayout(width, height, orientationHint, atlas.digitWidth, atlas.colonWidth, atlas.glyphHeight)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexture)
            setTextureParameters(GLES20.GL_TEXTURE_2D)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, atlas.bitmap, 0)
            atlas.bitmap.recycle()
            checkGl("textures")

            val texture = SurfaceTexture(videoTexture)
            surfaceTexture = texture
            // Without a handler the callback would go to the main thread, or get lost on a thread without a looper.
            texture.setOnFrameAvailableListener({
                lock.withLock {
                    frameAvailable = true
                    frameArrived.signalAll()
                }
            }, Handler(callbackThread.looper))
            decoderSurface = Surface(texture)
        }

        /** Waits for the frame the decoder was just told to render and makes it the current texture. */
        fun awaitFrame() {
            lock.withLock {
                var remainingNs = TimeUnit.MILLISECONDS.toNanos(FRAME_WAIT_MS)
                while (!frameAvailable) {
                    check(remainingNs > 0) { "Decoder delivered no frame" }
                    remainingNs = frameArrived.awaitNanos(remainingNs)
                }
                frameAvailable = false
            }
            surfaceTexture!!.updateTexImage()
        }

        fun draw(glyphs: IntArray) {
            surfaceTexture!!.getTransformMatrix(texMatrix)
            GLES20.glViewport(0, 0, width, height)

            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUseProgram(videoProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexture)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(videoProgram, "uTexMatrix"), 1, false, texMatrix, 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(videoProgram, "uTexture"), 0)
            bindVertices(videoProgram, "aPosition", "aTexCoord", videoVertices)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // Box first, then the nine characters; only their place in the atlas changes from frame to frame.
            overlayVertices.clear()
            putQuad(layout.box, atlas.solid, atlas.solid)
            for (slot in 0 until Timecode.LENGTH) {
                layout.glyph(slot, corners)
                putQuad(corners, atlas.left[glyphs[slot]], atlas.right[glyphs[slot]])
            }
            overlayVertices.flip()

            GLES20.glEnable(GLES20.GL_BLEND)
            // The atlas only carries coverage; colours are given premultiplied.
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(overlayProgram)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(overlayProgram, "uAtlas"), 0)
            bindVertices(overlayProgram, "aPosition", "aUv", overlayVertices)
            val color = GLES20.glGetUniformLocation(overlayProgram, "uColor")
            GLES20.glUniform4f(color, 0f, 0f, 0f, BOX_ALPHA)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, VERTICES_PER_QUAD)
            GLES20.glUniform4f(color, 1f, 1f, 1f, 1f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, VERTICES_PER_QUAD, Timecode.LENGTH * VERTICES_PER_QUAD)
            checkGl("draw")
        }

        /** Hands the drawn picture to the encoder with its time in the new clip. */
        fun present(ptsNs: Long) {
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs)
            check(EGL14.eglSwapBuffers(display, eglSurface)) { "EGL swap failed" }
        }

        fun release() {
            runCatching { decoderSurface?.release() }
            runCatching { surfaceTexture?.release() }
            decoderSurface = null
            surfaceTexture = null
            if (display != EGL14.EGL_NO_DISPLAY) {
                // The display is shared with the app's own drawing, so it is not terminated here.
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglReleaseThread()
            }
            eglSurface = EGL14.EGL_NO_SURFACE
            context = EGL14.EGL_NO_CONTEXT
            display = EGL14.EGL_NO_DISPLAY
            callbackThread.quitSafely()
        }

        /** [corners]: top left, top right, bottom left, bottom right; as two triangles with the atlas cell [u0]..[u1]. */
        private fun putQuad(corners: FloatArray, u0: Float, u1: Float) {
            var offset = 0
            fun vertex(corner: Int, u: Float, v: Float) {
                quad[offset++] = corners[corner * 2]
                quad[offset++] = corners[corner * 2 + 1]
                quad[offset++] = u
                quad[offset++] = v
            }
            vertex(0, u0, 0f)
            vertex(2, u0, 1f)
            vertex(1, u1, 0f)
            vertex(1, u1, 0f)
            vertex(2, u0, 1f)
            vertex(3, u1, 1f)
            overlayVertices.put(quad)
        }

        private fun bindVertices(program: Int, positionName: String, texCoordName: String, vertices: FloatBuffer) {
            val stride = FLOATS_PER_VERTEX * 4
            val position = GLES20.glGetAttribLocation(program, positionName)
            val texCoord = GLES20.glGetAttribLocation(program, texCoordName)
            vertices.position(0)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, stride, vertices)
            vertices.position(2)
            GLES20.glEnableVertexAttribArray(texCoord)
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, stride, vertices)
            vertices.position(0)
        }

        private fun setTextureParameters(target: Int) {
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private fun program(vertexSource: String, fragmentSource: String): Int {
            val program = GLES20.glCreateProgram()
            check(program != 0) { "No GL program" }
            GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, vertexSource))
            GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, fragmentSource))
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "GL program: " + GLES20.glGetProgramInfoLog(program) }
            return program
        }

        private fun shader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            check(shader != 0) { "No GL shader" }
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "GL shader: " + GLES20.glGetShaderInfoLog(shader) }
            return shader
        }

        private fun checkGl(step: String) {
            val error = GLES20.glGetError()
            check(error == GLES20.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(error)} at $step" }
        }

        private companion object {
            const val EGL_RECORDABLE_ANDROID = 0x3142
            const val FLOATS_PER_VERTEX = 4
            const val VERTICES_PER_QUAD = 6
            const val BOX_ALPHA = 0.5f

            fun floatBuffer(values: FloatArray): FloatBuffer =
                ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(values)
                    position(0)
                }

            val VIDEO_VERTEX_SHADER = """
                attribute vec4 aPosition;
                attribute vec4 aTexCoord;
                uniform mat4 uTexMatrix;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = (uTexMatrix * aTexCoord).xy;
                }
            """.trimIndent()

            val VIDEO_FRAGMENT_SHADER = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES uTexture;
                varying vec2 vTexCoord;
                void main() {
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                }
            """.trimIndent()

            val OVERLAY_VERTEX_SHADER = """
                attribute vec2 aPosition;
                attribute vec2 aUv;
                varying vec2 vUv;
                void main() {
                    gl_Position = vec4(aPosition, 0.0, 1.0);
                    vUv = aUv;
                }
            """.trimIndent()

            val OVERLAY_FRAGMENT_SHADER = """
                precision mediump float;
                uniform sampler2D uAtlas;
                uniform vec4 uColor;
                varying vec2 vUv;
                void main() {
                    gl_FragColor = uColor * texture2D(uAtlas, vUv).a;
                }
            """.trimIndent()
        }
    }
}
