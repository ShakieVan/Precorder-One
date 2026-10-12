package com.precorderone.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.preference.SwitchPreferenceCompat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.precorderone.R
import com.precorderone.camera.FpsCatalog
import com.precorderone.camera.LensCatalog
import com.precorderone.update.AppUpdater
import com.precorderone.update.UpdateDialog
import kotlinx.coroutines.launch

class SettingsFragment : PreferenceFragmentCompat() {

    private val openFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        context?.contentResolver?.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        findPreference<Preference>(KEY_OUTPUT_URI)?.summary = readableFolder(uri.toString())
        preferenceManager.sharedPreferences?.edit()?.putString(KEY_OUTPUT_URI, uri.toString())?.apply()
    }

    /** "content://…/tree/primary%3ADCIM%2FClips" → "Interner Speicher/DCIM/Clips". */
    private fun readableFolder(uri: String?): String {
        if (uri.isNullOrBlank()) return getString(R.string.pref_storage_default)
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri.toUri()) }.getOrNull() ?: return uri
        val volume = documentId.substringBefore(':')
        val path = documentId.substringAfter(':', "")
        val volumeName = if (volume == "primary") getString(R.string.pref_storage_internal) else volume
        return if (path.isEmpty()) volumeName else "$volumeName/$path"
    }

    // The microphone is optional: asked for only when sound is switched on, and the switch goes back off if denied.
    private val requestMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            findPreference<SwitchPreferenceCompat>(KEY_RECORD_AUDIO)?.isChecked = false
            context?.let { Toast.makeText(it, R.string.pref_audio_denied, Toast.LENGTH_LONG).show() }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        findPreference<SwitchPreferenceCompat>(KEY_RECORD_AUDIO)?.setOnPreferenceChangeListener { _, newValue ->
            val granted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (newValue == true && !granted) requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
            true
        }
        setupCameraListPreference(requireContext())
        setupStoragePreference()
        setupAspectRatioSummary()
        setupUpdatePreferences()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val updatePref = findPreference<Preference>(KEY_APP_UPDATE) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppUpdater.state.collect { state -> updatePref.summary = AppUpdater.statusText(requireContext(), state) }
            }
        }
    }

    private fun setupUpdatePreferences() {
        AppUpdater.init(requireContext())
        findPreference<Preference>(KEY_APP_VERSION)?.summary = AppUpdater.currentVersion()
        findPreference<Preference>(KEY_APP_UPDATE)?.setOnPreferenceClickListener {
            if (AppUpdater.releaseOf(AppUpdater.state.value) != null) {
                UpdateDialog.show(requireActivity() as AppCompatActivity)
            } else {
                AppUpdater.check(manual = true)
            }
            true
        }
    }

    private fun setupStoragePreference() {
        val storagePref = findPreference<Preference>(KEY_OUTPUT_URI)
        val current = preferenceManager.sharedPreferences?.getString(KEY_OUTPUT_URI, null)
        storagePref?.summary = readableFolder(current)
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

        fun lensFacingOf(value: String?): Int {
            return if (value == "front") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        }

        fun selectedLensFacing(): Int = lensFacingOf(lensPref?.value)

        fun updateFpsOptions(selectedId: String?) {
            val id = selectedId ?: return
            val chars = manager.getCameraCharacteristics(id)
            val supported = FpsCatalog.supported(chars)

            targetFpsPref?.entries = supported.map { "$it fps" }.toTypedArray()
            targetFpsPref?.entryValues = supported.map { it.toString() }.toTypedArray()
            val current = targetFpsPref?.value?.toIntOrNull()
            if (current == null || current !in supported) {
                targetFpsPref?.value = supported.last().toString()
            }
        }

        fun refreshCameraEntries() {
            val desiredLens = selectedLensFacing()
            val ids = manager.cameraIdList.toList()

            // Named like the system camera ("Rückkamera 0.6x"), with what the module can record.
            val labels = ids.map { id ->
                val chars = manager.getCameraCharacteristics(id)
                val appMaxFps = FpsCatalog.normal(chars).maxOrNull() ?: 30
                val highSpeedMaxFps = FpsCatalog.highSpeed(chars).maxOrNull()
                val lensName = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "Frontkamera"
                    CameraCharacteristics.LENS_FACING_BACK -> "Rückkamera"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "Externe Kamera"
                    else -> "Kamera"
                }
                val factor = runCatching {
                    LensCatalog.lenses(manager, id, zoomRatioLenses = false).current(id, 1f)?.label
                }.getOrNull()
                val hsLabel = highSpeedMaxFps?.let { " · High-Speed $it fps" } ?: ""
                "$lensName ${factor ?: ""} · bis $appMaxFps fps$hsLabel (ID $id)".replace("  ", " ")
            }

            cameraPref.entries = labels.toTypedArray()
            cameraPref.entryValues = ids.toTypedArray()
            if (ids.isEmpty()) {
                cameraPref.value = null
                return
            }
            if (cameraPref.value !in ids) {
                val preferredByLens = ids.firstOrNull { id ->
                    val chars = manager.getCameraCharacteristics(id)
                    chars.get(CameraCharacteristics.LENS_FACING) == desiredLens
                }
                cameraPref.value = preferredByLens ?: ids.first()
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

        // The listener runs before the new value is stored, so lensPref.value still holds the old lens here.
        lensPref?.setOnPreferenceChangeListener { _, newValue ->
            val ids = manager.cameraIdList.toList()
            val desiredLens = lensFacingOf(newValue as? String)
            val byLens = ids.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == desiredLens
            }
            if (byLens != null) {
                cameraPref.value = byLens
                updateFpsOptions(byLens)
            }
            true
        }

        refreshCameraEntries()
    }

    companion object {
        private const val KEY_OUTPUT_URI = "output_uri"
        private const val KEY_CAMERA_ID = "camera_id"
        private const val KEY_LENS_FACING = "lens_facing"
        private const val KEY_TARGET_FPS = "target_fps"
        private const val KEY_ASPECT_RATIO = "aspect_ratio"
        private const val KEY_RECORD_AUDIO = "record_audio"
        private const val KEY_APP_VERSION = "app_version"
        private const val KEY_APP_UPDATE = "app_update"
    }
}
