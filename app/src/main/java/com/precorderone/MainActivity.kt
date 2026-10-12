package com.precorderone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.precorderone.camera.LensOption
import com.precorderone.camera.PrecorderEngine
import com.precorderone.data.PrecorderSettings
import com.precorderone.data.SettingsRepository
import com.precorderone.databinding.ActivityMainBinding
import com.precorderone.ui.SettingsActivity
import com.precorderone.update.AppUpdater
import com.precorderone.update.UpdateDialog
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    private var sourceFps = 0f
    private var queuedFps = 0f
    private var encodedFps = 0f
    private var dropRate = 0f
    private var triggerArmed = false
    private var exposurePercent = 55
    private var hasActiveBinding = false
    private var lastBoundSettings: PrecorderSettings? = null
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchMoved = false
    private var touchSlopPx = 0
    private var focusLocked = false
    private var lastPipelineToast: String? = null
    private var lensOptions: List<LensOption> = emptyList()
    private var currentLens: LensOption? = null
    private var activePhysicalCamera: String? = null

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) bindCamera() else Toast.makeText(this, "Kamera-Berechtigung erforderlich", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop

        settingsRepository = SettingsRepository(this)
        engine = PrecorderEngine(this)
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
        engine.onProfileFallback = { msg ->
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }
        engine.onPipelineChanged = { msg ->
            runOnUiThread {
                if (msg != lastPipelineToast) {
                    lastPipelineToast = msg
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
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

        exposurePercent = engine.getManualExposurePercent()
        binding.exposureSlider.max = 80
        binding.exposureSlider.progress = exposurePercent - 20
        binding.exposureSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                exposurePercent = progress + 20
                binding.exposureText.text = getString(R.string.exposure_label, exposurePercent)
                if (!fromUser) engine.setManualExposurePercent(exposurePercent)
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                engine.setManualExposurePercent(exposurePercent)
            }
        })
        binding.exposureText.text = getString(R.string.exposure_label, exposurePercent)

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                currentPhysicalRotation = mapDegreesToSurfaceRotation(orientation)
                val indicatorAngle = (surfaceRotationToDegrees(currentPhysicalRotation) + 180f) % 360f
                binding.orientationIndicatorRing.rotation = indicatorAngle
            }
        }

        binding.btnTrigger.setOnClickListener { onTrigger() }
        binding.btnFocusAuto.setOnClickListener {
            val unlocked = engine.clearFocusLock()
            if (!unlocked) {
                Toast.makeText(this, R.string.focus_failed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            focusLocked = false
            updateFocusUi()
        }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.previewView.setOnTouchListener { _, event -> handlePreviewTouch(event, allowTapFocus = true, highSpeedPreview = false) }
        binding.highSpeedPreviewView.setOnTouchListener { _, event -> handlePreviewTouch(event, allowTapFocus = true, highSpeedPreview = true) }
        binding.previewView.previewStreamState.observe(this) { state ->
            if (state == PreviewView.StreamState.STREAMING) {
                engine.setManualExposurePercent(exposurePercent)
            }
        }
        updateFocusUi()
        updateZoomUi(rebuild = true)
        setupUpdateHint()

        requestPermissionsIfNeeded()
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
                    Snackbar.make(binding.root, getString(R.string.update_snackbar, release.version), Snackbar.LENGTH_LONG)
                        .setAnchorView(binding.orientationIndicatorRing)
                        .setAction(R.string.update_snackbar_action) { UpdateDialog.show(this@MainActivity) }
                        .show()
                }
            }
        }
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

    private fun requestPermissionsIfNeeded() {
        if (hasPermissions()) bindCamera() else permissionsLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun hasPermissions(): Boolean =
        REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun bindCamera(zoomRatio: Float? = null) {
        val settings = settingsRepository.load()
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
        updateFocusUi()
        updateBufferUi()
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
        engine.pauseSession()
        hasActiveBinding = false
        bindCamera(zoomRatio = option.zoomRatio)
    }

    private fun handlePreviewTouch(
        event: MotionEvent,
        allowTapFocus: Boolean,
        highSpeedPreview: Boolean
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                touchMoved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - touchStartX) > touchSlopPx || abs(event.y - touchStartY) > touchSlopPx) {
                    touchMoved = true
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (allowTapFocus && !touchMoved) {
                    val focused = if (highSpeedPreview && binding.highSpeedPreviewView.visibility == View.VISIBLE) {
                        val width = binding.highSpeedPreviewView.width.coerceAtLeast(1)
                        val height = binding.highSpeedPreviewView.height.coerceAtLeast(1)
                        engine.focusAtHighSpeed(event.x / width.toFloat(), event.y / height.toFloat())
                    } else if (!highSpeedPreview && binding.previewView.visibility == View.VISIBLE) {
                        engine.focusAt(binding.previewView, event.x, event.y)
                    } else {
                        false
                    }
                    if (!focused) {
                        Toast.makeText(this, R.string.focus_failed, Toast.LENGTH_SHORT).show()
                    } else {
                        focusLocked = true
                    }
                    updateFocusUi()
                }
                touchMoved = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                touchMoved = false
                return true
            }
        }
        return true
    }

    private fun updateFocusUi() {
        val canShow = focusLocked && (binding.previewView.visibility == View.VISIBLE || binding.highSpeedPreviewView.visibility == View.VISIBLE)
        binding.btnFocusAuto.visibility = if (canShow) View.VISIBLE else View.GONE
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
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onLensSelected(option) }
                }
                val params = LinearLayout.LayoutParams((58 * density).toInt(), (38 * density).toInt())
                if (index > 0) params.marginStart = (4 * density).toInt()
                container.addView(button, params)
            }
        }
        container.visibility = if (lensOptions.size > 1) View.VISIBLE else View.GONE
        lensOptions.forEachIndexed { index, option ->
            (container.getChildAt(index) as? TextView)?.let { setZoomButtonState(it, option == currentLens) }
        }
    }

    private fun setZoomButtonState(button: TextView, selected: Boolean) {
        button.alpha = if (selected) 1f else 0.65f
        button.setBackgroundResource(
            if (selected) R.drawable.bg_zoom_button_selected else R.drawable.bg_zoom_button_idle
        )
        button.setTextColor(
            ContextCompat.getColor(
                this,
                if (selected) android.R.color.black else android.R.color.white
            )
        )
    }

    private fun onTrigger() {
        val settings = settingsRepository.load()
        if (!isRecordingLoop || isSaving) return

        isSaving = true
        updateBufferUi()

        engine.exportClip(settings, currentPhysicalRotation) { uri ->
            runOnUiThread {
                isSaving = false
                if (uri != null) {
                    Toast.makeText(this, getString(R.string.saved_success, uri.toString()), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, R.string.saved_failed, Toast.LENGTH_LONG).show()
                }
                updateBufferUi()
            }
        }
    }

    private fun updateBufferUi() {
        binding.bufferProgress.progress = (bufferFill * 100).toInt().coerceIn(0, 100)
        if (bufferFill >= 0.98f) triggerArmed = true
        if (bufferFill < 0.90f) triggerArmed = false
        val ready = triggerArmed
        binding.btnTrigger.isEnabled = !isSaving
        binding.btnTrigger.alpha = if (!isSaving) 1f else 0.4f

        val target = lastBoundSettings?.targetFps ?: 0
        val fpsText = getString(R.string.debug_fps, sourceFps, queuedFps, encodedFps, dropRate, target)
        binding.debugFpsText.text = activePhysicalCamera
            ?.let { fpsText + "\n" + getString(R.string.debug_physical_camera, engine.getActiveCameraId() ?: "?", it) }
            ?: fpsText

        binding.statusText.text = when {
            isSaving -> getString(R.string.status_saving)
            ready -> getString(R.string.status_recording_ready)
            else -> getString(R.string.status_buffering, (bufferFill * 100).toInt().coerceIn(0, 100))
        }
    }

    private fun mapDegreesToSurfaceRotation(orientation: Int): Int = when {
        orientation in 45..134 -> Surface.ROTATION_270
        orientation in 135..224 -> Surface.ROTATION_180
        orientation in 225..314 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
    }

    private fun surfaceRotationToDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
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
    }
}
