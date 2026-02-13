package com.precorderone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
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

    private var isRecordingLoop = false

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) {
            bindCamera()
        } else {
            Toast.makeText(this, "Kamera-Berechtigung erforderlich", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsRepository = SettingsRepository(this)
        engine = PrecorderEngine(this)

        binding.btnTrigger.setOnClickListener {
            onTrigger()
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (hasPermissions()) bindCamera()
    }

    private fun requestPermissionsIfNeeded() {
        if (hasPermissions()) {
            bindCamera()
        } else {
            permissionsLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private fun hasPermissions(): Boolean =
        REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun bindCamera() {
        val settings = settingsRepository.load()
        engine.bind(this, binding.previewView, settings)
        isRecordingLoop = true
        binding.statusText.text = getString(R.string.status_recording)
    }

    private fun onTrigger() {
        val settings = settingsRepository.load()
        if (!isRecordingLoop) {
            bindCamera()
            return
        }
        binding.statusText.text = getString(R.string.status_saving)
        engine.exportClip(settings, currentSurfaceRotation()) { uri ->
            runOnUiThread {
                if (uri != null) {
                    Toast.makeText(this, getString(R.string.saved_success, uri.toString()), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, R.string.saved_failed, Toast.LENGTH_LONG).show()
                }
                binding.statusText.text = getString(R.string.status_recording)
            }
        }
    }


    private fun currentSurfaceRotation(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
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
