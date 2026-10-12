package com.precorderone

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.precorderone.camera.LensOption
import com.precorderone.camera.PrecorderEngine
import com.precorderone.data.PrecorderSettings
import com.precorderone.data.SettingsRepository
import com.precorderone.databinding.ActivityMainBinding
import com.precorderone.ui.QuickSettingsSheet
import com.precorderone.ui.SettingsActivity
import com.precorderone.update.AppUpdater
import com.precorderone.update.UpdateDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

@ExperimentalCamera2Interop
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var engine: PrecorderEngine
    private lateinit var orientationListener: OrientationEventListener

    private var isRecordingLoop = false
    private var isSaving = false
    private var bufferFill = 0f
    private var currentPhysicalRotation = Surface.ROTATION_0
    private var iconRotation = 0f
    private var sourceFps = 0f
    private var queuedFps = 0f
    private var encodedFps = 0f
    private var dropRate = 0f
    private var triggerArmed = false
    private var hasActiveBinding = false
    private var lastBoundSettings: PrecorderSettings? = null
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchMoved = false
    private var touchSlopPx = 0
    private var focusLocked = false
    private var pipelineInfo: String? = null
    private var lensOptions: List<LensOption> = emptyList()
    private var currentLens: LensOption? = null
    private var activePhysicalCamera: String? = null
    private var lastClipUri: Uri? = null
    private var showDiagnostics = false

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            bindCamera()
        } else {
            Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop

        settingsRepository = SettingsRepository(this)
        engine = PrecorderEngine(this)
        setupEngineCallbacks()
        setupControls()

        // The UI stays in portrait; like the system camera, only the icons turn upright with the device.
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = mapDegreesToSurfaceRotation(orientation)
                if (rotation == currentPhysicalRotation) return
                currentPhysicalRotation = rotation
                rotateIcons(rotation)
            }
        }

        updateFocusUi()
        updateZoomUi(rebuild = true)
        updateBufferUi()
        setupUpdateHint()
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        if (hasPermissions()) bindCamera()
    }

    override fun onPause() {
        super.onPause()
        orientationListener.disable()
        if (hasActiveBinding) {
            engine.pauseSession()
            hasActiveBinding = false
        }
        focusLocked = false
        updateFocusUi()
    }

    private fun setupEngineCallbacks() {
        engine.onBufferFillChanged = { progress ->
            runOnUiThread {
                bufferFill = progress
                updateBufferUi()
            }
        }
        engine.onMeasuredFpsChanged = { fps ->
            runOnUiThread {
                sourceFps = fps
                updateBufferUi()
            }
        }
        engine.onDebugStatsChanged = { source, queued, encoded, drop ->
            runOnUiThread {
                sourceFps = source
                queuedFps = queued
                encodedFps = encoded
                dropRate = drop
                updateBufferUi()
            }
        }
        engine.onProfileFallback = { msg -> runOnUiThread { showMessage(msg) } }
        // Shown in the diagnostics line instead of a toast: toasts sit right on top of the lens bar.
        engine.onPipelineChanged = { pipeline, cameraId ->
            runOnUiThread {
                val name = getString(
                    when (pipeline) {
                        PrecorderEngine.CapturePipeline.CAMERA2_HIGHSPEED -> R.string.pipeline_highspeed
                        PrecorderEngine.CapturePipeline.CAMERA2_NORMAL -> R.string.pipeline_camera2
                        PrecorderEngine.CapturePipeline.CAMERAX -> R.string.pipeline_camerax
                    }
                )
                pipelineInfo = getString(R.string.debug_pipeline, name, cameraId ?: "?")
                updateBufferUi()
            }
        }
        engine.onActivePhysicalCameraChanged = { id ->
            runOnUiThread {
                activePhysicalCamera = id
                updateBufferUi()
            }
        }
        engine.onLensesChanged = { options, current ->
            runOnUiThread {
                val rebuild = options != lensOptions
                lensOptions = options
                currentLens = current
                updateZoomUi(rebuild)
            }
        }
        engine.onPreviewAspectChanged = { aspect ->
            runOnUiThread { binding.highSpeedPreviewView.setAspectRatio(aspect) }
        }
    }

    private fun setupControls() {
        binding.btnTrigger.setOnClickListener { onTrigger() }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.profileChip.setOnClickListener { showQuickSettings() }
        binding.statusChip.setOnClickListener { showQuickSettings() }
        binding.btnTorch.setOnClickListener { toggleTorch() }
        binding.btnSwitchCamera.setOnClickListener { switchFacing() }
        binding.lastClipThumb.setOnClickListener { openLastClip() }
        binding.btnFocusAuto.setOnClickListener {
            if (!engine.clearFocusLock()) {
                Toast.makeText(this, R.string.focus_failed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            focusLocked = false
            updateFocusUi()
        }

        binding.btnExposure.setOnClickListener {
            val panel = binding.exposurePanel
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        // Long press resets the compensation, so a forgotten offset is one gesture away from neutral.
        binding.btnExposure.setOnLongClickListener {
            binding.exposureSlider.progress = EXPOSURE_STEPS_PER_SIDE
            applyExposure(EXPOSURE_STEPS_PER_SIDE)
            true
        }
        binding.exposureSlider.max = EXPOSURE_STEPS_PER_SIDE * 2
        binding.exposureSlider.progress = (engine.getExposureEv() * EXPOSURE_STEPS_PER_EV).roundToInt() + EXPOSURE_STEPS_PER_SIDE
        binding.exposureSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) applyExposure(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.previewView.setOnTouchListener { view, event -> handlePreviewTouch(view, event, highSpeedPreview = false) }
        binding.highSpeedPreviewView.setOnTouchListener { view, event -> handlePreviewTouch(view, event, highSpeedPreview = true) }
    }

    private fun showQuickSettings() {
        if (isSaving) return
        QuickSettingsSheet(this, settingsRepository, engine.getActiveCameraId()) {
            restartCamera(engine.getCurrentZoomRatio())
        }.show()
    }

    private fun applyExposure(progress: Int) {
        val ev = (progress - EXPOSURE_STEPS_PER_SIDE).toFloat() / EXPOSURE_STEPS_PER_EV
        val applied = engine.setExposureEv(ev)
        binding.exposureText.text = if (abs(applied) < 0.05f) getString(R.string.exposure_zero) else getString(R.string.exposure_value, applied)
    }

    private fun setupUpdateHint() {
        AppUpdater.init(this)
        AppUpdater.check(manual = false)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppUpdater.state.collect { state ->
                    val release = when (state) {
                        is AppUpdater.State.Available -> state.release
                        is AppUpdater.State.Ready -> state.release
                        else -> null
                    } ?: return@collect
                    if (AppUpdater.announcedVersion == release.version) return@collect
                    AppUpdater.announcedVersion = release.version
                    showMessage(getString(R.string.update_snackbar, release.version), R.string.update_snackbar_action) {
                        UpdateDialog.show(this@MainActivity)
                    }
                }
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (hasPermissions()) bindCamera() else permissionsLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun hasPermissions(): Boolean =
        REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun bindCamera(zoomRatio: Float? = null) {
        val settings = settingsRepository.load()
        showDiagnostics = settingsRepository.showDiagnostics()
        binding.debugFpsText.visibility = if (showDiagnostics) View.VISIBLE else View.GONE
        if (hasActiveBinding && settings == lastBoundSettings) {
            return
        }
        // Same camera as before (e.g. back from the settings): keep the lens that was in use.
        val initialZoom = zoomRatio
            ?: if (settings.cameraId == lastBoundSettings?.cameraId) engine.getCurrentZoomRatio() else 1f
        engine.bind(this, binding.previewView, binding.highSpeedPreviewView, settings, initialZoom)
        hasActiveBinding = true
        lastBoundSettings = settings
        isRecordingLoop = true
        bufferFill = 0f
        isSaving = false
        triggerArmed = false
        focusLocked = false
        updateTorchIcon(settings.torchEnabled)
        updateProfileChip(settings)
        updateFocusUi()
        updateBufferUi()
    }

    /** Reopens the camera after the stored camera ID changed (lens bar, front/back switch). */
    private fun restartCamera(zoomRatio: Float) {
        engine.pauseSession()
        hasActiveBinding = false
        bindCamera(zoomRatio)
    }

    private fun onLensSelected(option: LensOption) {
        if (isSaving || option == currentLens) return
        if (option.cameraId == engine.getActiveCameraId()) {
            if (!engine.setZoomRatio(option.zoomRatio)) {
                Toast.makeText(this, R.string.zoom_set_failed, Toast.LENGTH_SHORT).show()
            }
            return
        }
        // Lens is its own camera ID: store it like the settings screen does, then reopen with that camera.
        settingsRepository.save(settingsRepository.load().copy(cameraId = option.cameraId))
        restartCamera(option.zoomRatio)
    }

    private fun switchFacing() {
        if (isSaving) return
        val settings = settingsRepository.load()
        val toFront = settings.lensFacing != CameraSelector.LENS_FACING_FRONT
        val wanted = if (toFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            runCatching { manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == wanted }.getOrDefault(false)
        } ?: return
        val facing = if (toFront) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        settingsRepository.save(settings.copy(lensFacing = facing, cameraId = cameraId))
        binding.btnSwitchCamera.animate().rotationBy(180f).setDuration(250).start()
        restartCamera(zoomRatio = 1f)
    }

    private fun toggleTorch() {
        val settings = settingsRepository.load()
        val enabled = !settings.torchEnabled
        settingsRepository.save(settings.copy(torchEnabled = enabled))
        lastBoundSettings = lastBoundSettings?.copy(torchEnabled = enabled)
        engine.toggleTorch(enabled)
        updateTorchIcon(enabled)
    }

    private fun updateTorchIcon(enabled: Boolean) {
        binding.btnTorch.setImageResource(if (enabled) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
    }

    private fun updateProfileChip(settings: PrecorderSettings) {
        val factor = settings.targetFps.toFloat() / settings.playbackFps.coerceAtLeast(1)
        binding.profileChip.text = if (factor <= 1.01f) {
            getString(R.string.profile_realtime, settings.targetFps, settings.aspectRatio)
        } else {
            val factorText = if (abs(factor - factor.roundToInt()) < 0.05f) "${factor.roundToInt()}" else String.format("%.1f", factor)
            getString(R.string.profile_slowmo, settings.targetFps, factorText, settings.aspectRatio)
        }
    }

    private fun handlePreviewTouch(view: View, event: MotionEvent, highSpeedPreview: Boolean): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                touchMoved = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - touchStartX) > touchSlopPx || abs(event.y - touchStartY) > touchSlopPx) {
                    touchMoved = true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!touchMoved) {
                    val focused = when {
                        highSpeedPreview && view.visibility == View.VISIBLE -> engine.focusAtPreview(
                            event.x / view.width.coerceAtLeast(1),
                            event.y / view.height.coerceAtLeast(1)
                        )
                        !highSpeedPreview && view.visibility == View.VISIBLE -> engine.focusAt(binding.previewView, event.x, event.y)
                        else -> false
                    }
                    if (focused) {
                        focusLocked = true
                        showFocusRing(view, event.x, event.y)
                    } else {
                        Toast.makeText(this, R.string.focus_failed, Toast.LENGTH_SHORT).show()
                    }
                    updateFocusUi()
                }
                touchMoved = false
            }
            MotionEvent.ACTION_CANCEL -> touchMoved = false
        }
        return true
    }

    private fun showFocusRing(preview: View, x: Float, y: Float) {
        val ring = binding.focusRing
        ring.animate().cancel()
        ring.translationX = preview.left + x - ring.width / 2f
        ring.translationY = preview.top + y - ring.height / 2f
        ring.alpha = 1f
        ring.scaleX = 1.4f
        ring.scaleY = 1.4f
        ring.visibility = View.VISIBLE
        ring.animate().scaleX(1f).scaleY(1f).setDuration(180).withEndAction {
            ring.animate().alpha(0.5f).setStartDelay(900).setDuration(300).start()
        }.start()
    }

    private fun updateFocusUi() {
        binding.btnFocusAuto.visibility = if (focusLocked) View.VISIBLE else View.GONE
        if (!focusLocked) {
            binding.focusRing.animate().cancel()
            binding.focusRing.visibility = View.INVISIBLE
        }
    }

    /** One button per native lens; a single lens needs no bar. */
    private fun updateZoomUi(rebuild: Boolean) {
        val container = binding.zoomControlsContainer
        if (rebuild) {
            container.removeAllViews()
            val density = resources.displayMetrics.density
            lensOptions.forEachIndexed { index, option ->
                val button = TextView(this).apply {
                    text = option.label
                    contentDescription = getString(R.string.lens_button_description, option.label)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    isClickable = true
                    isFocusable = true
                    rotation = iconRotation
                    setOnClickListener { onLensSelected(option) }
                }
                val params = LinearLayout.LayoutParams((48 * density).toInt(), (36 * density).toInt())
                if (index > 0) params.marginStart = (4 * density).toInt()
                container.addView(button, params)
            }
        }
        container.visibility = if (lensOptions.size > 1) View.VISIBLE else View.INVISIBLE
        lensOptions.forEachIndexed { index, option ->
            (container.getChildAt(index) as? TextView)?.let { setZoomButtonState(it, option == currentLens) }
        }
    }

    private fun setZoomButtonState(button: TextView, selected: Boolean) {
        button.setBackgroundResource(if (selected) R.drawable.bg_zoom_button_selected else R.drawable.bg_zoom_button_idle)
        button.setTextColor(ContextCompat.getColor(this, if (selected) android.R.color.black else android.R.color.white))
    }

    private fun rotateIcons(rotation: Int) {
        // OrientationEventListener 90 (device turned clockwise) maps to ROTATION_270: turn the icons back by 90°.
        iconRotation = when (rotation) {
            Surface.ROTATION_270 -> -90f
            Surface.ROTATION_90 -> 90f
            Surface.ROTATION_180 -> 180f
            else -> 0f
        }
        val zoomButtons = (0 until binding.zoomControlsContainer.childCount).map { binding.zoomControlsContainer.getChildAt(it) }
        (listOf(binding.btnSettings, binding.btnTorch, binding.btnExposure, binding.btnSwitchCamera, binding.lastClipThumb) + zoomButtons)
            .forEach { it.animate().rotation(iconRotation).setDuration(250).start() }
    }

    private fun onTrigger() {
        if (!isRecordingLoop || isSaving) return
        val settings = settingsRepository.load()
        isSaving = true
        binding.btnTrigger.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        binding.btnTrigger.animate().scaleX(0.86f).scaleY(0.86f).setDuration(90).withEndAction {
            binding.btnTrigger.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }.start()
        updateBufferUi()

        engine.exportClip(settings, currentPhysicalRotation) { uri ->
            runOnUiThread {
                isSaving = false
                if (uri != null) onClipSaved(uri) else showMessage(getString(R.string.saved_failed))
                updateBufferUi()
            }
        }
    }

    private fun onClipSaved(uri: Uri) {
        lastClipUri = uri
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            binding.btnTrigger.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
        showMessage(getString(R.string.saved_success), R.string.saved_open) { openLastClip() }
        lifecycleScope.launch {
            val thumbnail = withContext(Dispatchers.IO) {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(this@MainActivity, uri)
                        retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 192)
                    } finally {
                        retriever.release()
                    }
                }.getOrNull()
            } ?: return@launch
            binding.lastClipThumb.setImageBitmap(thumbnail)
            binding.lastClipThumb.visibility = View.VISIBLE
        }
    }

    private fun openLastClip() {
        val uri = lastClipUri ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_video_player, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMessage(text: CharSequence, actionLabel: Int? = null, action: (() -> Unit)? = null) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.bottomBar)
            .apply { if (actionLabel != null && action != null) setAction(actionLabel) { action() } }
            .show()
    }

    private fun updateBufferUi() {
        val percent = (bufferFill * 100).toInt().coerceIn(0, 100)
        if (bufferFill >= 0.98f) triggerArmed = true
        if (bufferFill < 0.90f) triggerArmed = false

        val color = ContextCompat.getColor(
            this,
            when {
                isSaving -> R.color.brand_red
                triggerArmed -> R.color.ready_green
                else -> R.color.buffer_amber
            }
        )
        binding.bufferRing.setProgressCompat(percent, false)
        binding.bufferRing.setIndicatorColor(color)
        binding.btnTrigger.isEnabled = !isSaving
        binding.btnTrigger.alpha = if (isSaving) 0.6f else 1f
        binding.statusChip.text = when {
            isSaving -> getString(R.string.status_saving)
            triggerArmed -> getString(R.string.status_ready, lastBoundSettings?.loopSeconds ?: 0)
            else -> getString(R.string.status_buffering, percent)
        }
        TextViewCompat.setCompoundDrawableTintList(binding.statusChip, ColorStateList.valueOf(color))

        if (showDiagnostics) {
            val target = lastBoundSettings?.targetFps ?: 0
            val fpsText = getString(R.string.debug_fps, sourceFps, queuedFps, encodedFps, dropRate, target)
            val cameraLine = pipelineInfo?.let { info ->
                info + (activePhysicalCamera?.let { getString(R.string.debug_physical_camera, it) } ?: "")
            }
            binding.debugFpsText.text = if (cameraLine != null) "$fpsText\n$cameraLine" else fpsText
        }
    }

    private fun mapDegreesToSurfaceRotation(orientation: Int): Int = when {
        orientation in 45..134 -> Surface.ROTATION_270
        orientation in 135..224 -> Surface.ROTATION_180
        orientation in 225..314 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val settings = settingsRepository.load()
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && settings.triggerWithVolumeDown) {
            onTrigger()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.release()
    }

    companion object {
        // Clips have no audio track, so the microphone is not requested (denying it used to block the camera).
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA
        )

        // Exposure slider: ±2 EV in thirds, the usual camera granularity.
        private const val EXPOSURE_STEPS_PER_EV = 3
        private const val EXPOSURE_STEPS_PER_SIDE = 2 * EXPOSURE_STEPS_PER_EV
    }
}
