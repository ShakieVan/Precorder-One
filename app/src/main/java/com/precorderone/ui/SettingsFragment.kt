package com.precorderone.ui

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Range
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.precorderone.R

class SettingsFragment : PreferenceFragmentCompat() {

    private val openFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        context?.contentResolver?.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        findPreference<Preference>(KEY_OUTPUT_URI)?.summary = uri.toString()
        preferenceManager.sharedPreferences?.edit()?.putString(KEY_OUTPUT_URI, uri.toString())?.apply()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        setupCameraListPreference(requireContext())
        setupStoragePreference()
        setupAspectRatioSummary()
    }

    private fun setupStoragePreference() {
        val storagePref = findPreference<Preference>(KEY_OUTPUT_URI)
        val current = preferenceManager.sharedPreferences?.getString(KEY_OUTPUT_URI, null)
        storagePref?.summary = current ?: "DCIM/Precorder-One"
        storagePref?.setOnPreferenceClickListener {
            openFolder.launch(current?.toUri())
            true
        }
    }

    private fun setupAspectRatioSummary() {
        findPreference<ListPreference>(KEY_ASPECT_RATIO)?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
    }

    private fun setupCameraListPreference(context: Context) {
        val cameraPref = findPreference<ListPreference>(KEY_CAMERA_ID) ?: return
        val targetFpsPref = findPreference<ListPreference>(KEY_TARGET_FPS)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = manager.cameraIdList
        val labels = ids.map { id ->
            val chars = manager.getCameraCharacteristics(id)
            val lens = chars.get(CameraCharacteristics.LENS_FACING)
            val lensLabel = when (lens) {
                CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                CameraCharacteristics.LENS_FACING_BACK -> "Back"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                else -> "Unknown"
            }
            val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            val maxFps = ranges?.maxOfOrNull { it.upper } ?: 30
            "Kamera $id ($lensLabel, bis $maxFps fps)"
        }

        cameraPref.entries = labels.toTypedArray()
        cameraPref.entryValues = ids
        if (cameraPref.value == null && ids.isNotEmpty()) {
            cameraPref.value = ids.first()
        }
        cameraPref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        fun updateFpsOptions(selectedId: String?) {
            val id = selectedId ?: return
            val chars = manager.getCameraCharacteristics(id)
            val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            val supported = ranges
                .flatMap { range -> supportedValuesFromRange(range) }
                .distinct()
                .sorted()
                .filter { it >= 24 }
                .ifEmpty { listOf(30, 60) }

            targetFpsPref?.entries = supported.map { "$it fps" }.toTypedArray()
            targetFpsPref?.entryValues = supported.map { it.toString() }.toTypedArray()
            val current = targetFpsPref?.value?.toIntOrNull()
            if (current == null || current !in supported) {
                targetFpsPref?.value = supported.last().toString()
            }
        }

        updateFpsOptions(cameraPref.value)
        cameraPref.setOnPreferenceChangeListener { _, newValue ->
            updateFpsOptions(newValue as? String)
            true
        }
    }

    private fun supportedValuesFromRange(range: Range<Int>): List<Int> {
        val values = mutableListOf<Int>()
        val candidates = listOf(24, 25, 30, 48, 50, 60, 90, 100, 120, 144, 240)
        candidates.forEach {
            if (it in range.lower..range.upper) values.add(it)
        }
        if (range.upper !in values) values.add(range.upper)
        return values
    }

    companion object {
        private const val KEY_OUTPUT_URI = "output_uri"
        private const val KEY_CAMERA_ID = "camera_id"
        private const val KEY_TARGET_FPS = "target_fps"
        private const val KEY_ASPECT_RATIO = "aspect_ratio"
    }
}
