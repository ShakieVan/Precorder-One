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
        val lensPref = findPreference<ListPreference>(KEY_LENS_FACING)
        val cameraPref = findPreference<ListPreference>(KEY_CAMERA_ID) ?: return
        val targetFpsPref = findPreference<ListPreference>(KEY_TARGET_FPS)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        fun selectedLensFacing(): Int {
            return if (lensPref?.value == "front") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        }

        fun updateFpsOptions(selectedId: String?) {
            val id = selectedId ?: return
            val chars = manager.getCameraCharacteristics(id)
            val supported = collectAppSupportedFps(chars)
                .filter { it >= 24 }
                .distinct()
                .sorted()
                .ifEmpty { listOf(30, 60) }

            targetFpsPref?.entries = supported.map { "$it fps" }.toTypedArray()
            targetFpsPref?.entryValues = supported.map { it.toString() }.toTypedArray()
            val current = targetFpsPref?.value?.toIntOrNull()
            if (current == null || current !in supported) {
                targetFpsPref?.value = supported.last().toString()
            }
        }

        fun refreshCameraEntries() {
            val desiredLens = selectedLensFacing()
            val ids = manager.cameraIdList.filter { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == desiredLens
            }

            val labels = ids.mapIndexed { index, id ->
                val chars = manager.getCameraCharacteristics(id)
                val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val focalLabel = focal?.let { String.format("%.1fmm", it) } ?: "?mm"
                val appMaxFps = collectAppSupportedFps(chars).maxOrNull() ?: 30
                val highSpeedMaxFps = collectHighSpeedFps(chars).maxOrNull()
                val lensName = if (desiredLens == CameraCharacteristics.LENS_FACING_FRONT) "Front" else "Back"
                val hsLabel = highSpeedMaxFps?.let { ", HS bis $it fps" } ?: ""
                "$lensName ${index + 1} (ID $id, $focalLabel, App bis $appMaxFps fps$hsLabel)"
            }

            cameraPref.entries = labels.toTypedArray()
            cameraPref.entryValues = ids.toTypedArray()
            if (ids.isEmpty()) {
                cameraPref.value = null
                return
            }
            if (cameraPref.value !in ids) {
                cameraPref.value = ids.first()
            }
            cameraPref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            updateFpsOptions(cameraPref.value)
        }

        fun updateLensFromCamera(cameraId: String?) {
            val id = cameraId ?: return
            val chars = manager.getCameraCharacteristics(id)
            when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> lensPref?.value = "front"
                CameraCharacteristics.LENS_FACING_BACK -> lensPref?.value = "back"
            }
        }

        cameraPref.setOnPreferenceChangeListener { _, newValue ->
            val id = newValue as? String
            updateLensFromCamera(id)
            updateFpsOptions(id)
            true
        }

        lensPref?.setOnPreferenceChangeListener { _, _ ->
            refreshCameraEntries()
            true
        }

        refreshCameraEntries()
    }

    private fun collectAppSupportedFps(chars: CameraCharacteristics): List<Int> {
        val values = mutableSetOf<Int>()
        chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.forEach { range -> values.addAll(supportedValuesFromAeRange(range)) }
        return values.toList()
    }

    private fun collectHighSpeedFps(chars: CameraCharacteristics): List<Int> {
        val values = mutableSetOf<Int>()
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        map?.highSpeedVideoFpsRanges?.forEach { range ->
            values.add(range.upper)
            values.add(range.lower)
        }
        return values.toList()
    }

    private fun supportedValuesFromAeRange(range: Range<Int>): List<Int> {
        val values = mutableListOf<Int>()
        val candidates = listOf(24, 25, 30, 48, 50, 60, 90, 100, 120, 144, 240)
        candidates.forEach { candidate ->
            if (candidate <= 60 && candidate in range.lower..range.upper) values.add(candidate)
            if (candidate > 60 && range.lower == candidate && range.upper == candidate) values.add(candidate)
        }
        if (range.upper <= 60 && range.upper !in values) values.add(range.upper)
        return values
    }

    companion object {
        private const val KEY_OUTPUT_URI = "output_uri"
        private const val KEY_CAMERA_ID = "camera_id"
        private const val KEY_LENS_FACING = "lens_facing"
        private const val KEY_TARGET_FPS = "target_fps"
        private const val KEY_ASPECT_RATIO = "aspect_ratio"
    }
}
