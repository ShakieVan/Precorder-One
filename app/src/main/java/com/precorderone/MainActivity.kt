package com.precorderone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.precorderone.camera.PrecorderEngine
import com.precorderone.data.SettingsRepository
import com.precorderone.databinding.ActivityMainBinding
import com.precorderone.ui.SettingsActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var engine: PrecorderEngine
    private lateinit var orientationListener: OrientationEventListener

    private var isRecordingLoop = false
    private var isSaving = false
    private var bufferFill = 0f
    private var currentPhysicalRotation = Surface.ROTATION_0
    private var measuredFps = 0f
    private var triggerArmed = false

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
                measuredFps = fps
                val target = settingsRepository.load().targetFps
                binding.debugFpsText.text = getString(R.string.debug_fps, fps, target)
            }
        }

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                currentPhysicalRotation = mapDegreesToSurfaceRotation(orientation)
                val indicatorAngle = (surfaceRotationToDegrees(currentPhysicalRotation) + 180f) % 360f
                binding.orientationIndicatorRing.rotation = indicatorAngle
            }
        }

        binding.btnTrigger.setOnClickListener { onTrigger() }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

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
    }

    private fun requestPermissionsIfNeeded() {
        if (hasPermissions()) bindCamera() else permissionsLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun hasPermissions(): Boolean =
        REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun bindCamera() {
        val settings = settingsRepository.load()
        engine.bind(this, binding.previewView, settings)
        isRecordingLoop = true
        bufferFill = 0f
        isSaving = false
        triggerArmed = false
        updateBufferUi()
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

        val target = settingsRepository.load().targetFps
        binding.debugFpsText.text = getString(R.string.debug_fps, measuredFps, target)

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
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}
