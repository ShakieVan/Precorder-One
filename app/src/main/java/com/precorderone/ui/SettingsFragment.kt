package com.precorderone.ui

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
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
    }

    private fun setupStoragePreference() {
        val storagePref = findPreference<Preference>(KEY_OUTPUT_URI)
        val current = preferenceManager.sharedPreferences?.getString(KEY_OUTPUT_URI, null)
        storagePref?.summary = current ?: "DCIM/Precorder-One"
        storagePref?.setOnPreferenceClickListener {
            openFolder.launch(null)
            true
        }
    }

    private fun setupCameraListPreference(context: Context) {
        val cameraPref = findPreference<ListPreference>(KEY_CAMERA_ID) ?: return
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
            "Kamera $id ($lensLabel)"
        }

        cameraPref.entries = labels.toTypedArray()
        cameraPref.entryValues = ids
        if (cameraPref.value == null && ids.isNotEmpty()) {
            cameraPref.value = ids.first()
        }
        cameraPref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
    }

    companion object {
        private const val KEY_OUTPUT_URI = "output_uri"
        private const val KEY_CAMERA_ID = "camera_id"
    }
}
