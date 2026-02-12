package com.precorderone

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.TextureView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : ComponentActivity() {
    private lateinit var textureView: TextureView
    private lateinit var triggerButton: Button
    private lateinit var statusText: TextView

    private lateinit var settingsStore: SettingsStore
    private lateinit var engine: PreRecorderEngine

    private var settings = AppSettings()
    private var captureReady = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startEngine() else toast("Kamera-Berechtigung fehlt")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsStore = SettingsStore(this)
        settings = settingsStore.load()
        engine = PreRecorderEngine(this)

        setupUi()

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (hasCameraPermission()) startEngine() else cameraPermission.launch(Manifest.permission.CAMERA)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                engine.stop()
                captureReady = false
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    private fun setupUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        textureView = TextureView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        statusText = TextView(this).apply {
            text = "Bereit"
            gravity = Gravity.CENTER_HORIZONTAL
        }

        triggerButton = Button(this).apply {
            text = "Trigger (Start/Save)"
            setOnClickListener { onTrigger() }
        }

        val settingsButton = Button(this).apply {
            text = "Konfiguration"
            setOnClickListener { showSettingsDialog() }
        }

        root.addView(textureView)
        root.addView(statusText)
        root.addView(triggerButton)
        root.addView(settingsButton)
        setContentView(root)
    }

    private fun startEngine() {
        val surface = textureView.surfaceTexture ?: return
        engine.start(surface, settings) { ready ->
            runOnUiThread {
                captureReady = ready
                statusText.text = if (ready) "Ringpuffer aktiv (${settings.loopSeconds}s)" else "Fehler beim Start"
            }
        }
    }

    private fun onTrigger() {
        if (!captureReady) {
            toast("Engine noch nicht bereit")
            return
        }
        val saved = engine.saveBuffer(settings)
        if (saved != null) {
            toast("Gespeichert: ${saved.absolutePath}")
            statusText.text = "Gespeichert: ${saved.name}"
        } else {
            toast("Kein gültiges Material im Ringpuffer")
        }
    }

    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }

        val loopSpinner = Spinner(this)
        val loopOptions = listOf("5", "10")
        loopSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, loopOptions)
        loopSpinner.setSelection(if (settings.loopSeconds == 10) 1 else 0)

        val cameraSpinner = Spinner(this)
        val cameraIds = engine.cameraIds().toList()
        cameraSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cameraIds)
        val cameraSelection = cameraIds.indexOf(settings.cameraId).takeIf { it >= 0 } ?: 0
        if (cameraIds.isNotEmpty()) cameraSpinner.setSelection(cameraSelection)

        val storageField = EditText(this).apply { setText(settings.storageDir) }
        val targetFpsField = EditText(this).apply { setText(settings.targetFps.toString()); hint = "Aufnahme FPS" }
        val saveFpsField = EditText(this).apply { setText(settings.saveFps.toString()); hint = "Datei FPS" }
        val torchSwitch = Switch(this).apply {
            text = "Kameralicht (Torch)"
            isChecked = settings.torchEnabled
        }

        val maxZoom = if (cameraIds.isNotEmpty()) engine.maxZoomRatio(cameraIds[cameraSpinner.selectedItemPosition]) else 1f
        val zoomLabel = TextView(this).apply { text = "Zoom: ${"%.1f".format(settings.zoomRatio)}x" }
        val zoomBar = SeekBar(this).apply {
            max = ((maxZoom - 1f) * 10f).toInt().coerceAtLeast(1)
            progress = ((settings.zoomRatio - 1f) * 10f).toInt().coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    zoomLabel.text = "Zoom: ${"%.1f".format(1f + progress / 10f)}x"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }

        container.addView(TextView(this).apply { text = "Loop-Speicher (Sekunden)" })
        container.addView(loopSpinner)
        container.addView(TextView(this).apply { text = "Kamera-ID" })
        container.addView(cameraSpinner)
        container.addView(TextView(this).apply { text = "Speicherpfad" })
        container.addView(storageField)
        container.addView(targetFpsField)
        container.addView(saveFpsField)
        container.addView(torchSwitch)
        container.addView(zoomLabel)
        container.addView(zoomBar)

        MaterialAlertDialogBuilder(this)
            .setTitle("Precorder-One Konfiguration")
            .setView(container)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Speichern") { _, _ ->
                settings = settings.copy(
                    loopSeconds = loopOptions[loopSpinner.selectedItemPosition].toInt(),
                    storageDir = storageField.text.toString().trim().ifBlank { AppSettings.defaultStorageDir() },
                    cameraId = cameraIds.getOrNull(cameraSpinner.selectedItemPosition),
                    targetFps = targetFpsField.text.toString().toIntOrNull()?.coerceAtLeast(15) ?: 30,
                    saveFps = saveFpsField.text.toString().toIntOrNull()?.coerceAtLeast(15) ?: 30,
                    torchEnabled = torchSwitch.isChecked,
                    zoomRatio = 1f + zoomBar.progress / 10f
                )
                settingsStore.save(settings)
                startEngine()
            }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            onTrigger()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        engine.stop()
    }
}
