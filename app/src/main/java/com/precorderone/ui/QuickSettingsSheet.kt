package com.precorderone.ui

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.precorderone.R
import com.precorderone.camera.FpsCatalog
import com.precorderone.data.PrecorderSettings
import com.precorderone.data.SettingsRepository
import com.precorderone.databinding.SheetQuickSettingsBinding

/**
 * Bottom sheet behind the chips of the main screen: the recording options people change while filming, without
 * leaving the camera. Every change is saved at once and [onChanged] restarts the camera with it.
 */
class QuickSettingsSheet(
    private val context: Context,
    private val repository: SettingsRepository,
    private val activeCameraId: String?,
    private val onChanged: () -> Unit
) {
    private val inflater = LayoutInflater.from(context)

    fun show() {
        val dialog = BottomSheetDialog(context)
        val binding = SheetQuickSettingsBinding.inflate(inflater)
        render(binding)
        binding.btnAllSettings.setOnClickListener {
            dialog.dismiss()
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
        dialog.setContentView(binding.root)
        dialog.show()
    }

    private fun render(binding: SheetQuickSettingsBinding) {
        val settings = repository.load()
        binding.fpsGroup.fill(fpsOptions(settings).map { it.toString() to "$it fps" }, settings.targetFps.toString()) {
            update(binding) { copy(targetFps = it.toInt()) }
        }
        val playbackOptions = PLAYBACK_FPS.map { fps ->
            val factor = settings.targetFps.toFloat() / fps
            val factorText = if (factor % 1f == 0f) factor.toInt().toString() else String.format("%.1f", factor)
            val label = if (factor > 1f) context.getString(R.string.quick_slowmo, fps, factorText) else "$fps fps"
            fps.toString() to label
        }
        binding.playbackGroup.fill(playbackOptions, settings.playbackFps.toString()) {
            update(binding) { copy(playbackFps = it.toInt()) }
        }
        binding.loopGroup.fill(LOOP_SECONDS.map { it.toString() to "$it s" }, settings.loopSeconds.toString()) {
            update(binding) { copy(loopSeconds = it.toInt()) }
        }
        val postRollOptions = POST_ROLL_SECONDS.map {
            it.toString() to if (it == 0) context.getString(R.string.quick_post_roll_off) else "$it s"
        }
        binding.postRollGroup.fill(postRollOptions, settings.postRollSeconds.toString()) {
            update(binding) { copy(postRollSeconds = it.toInt()) }
        }
        binding.aspectGroup.fill(ASPECTS.map { it to it }, settings.aspectRatio) {
            update(binding) { copy(aspectRatio = it) }
        }
    }

    private fun update(binding: SheetQuickSettingsBinding, change: PrecorderSettings.() -> PrecorderSettings) {
        val current = repository.load()
        val updated = current.change()
        if (updated == current) return
        repository.save(updated)
        onChanged()
        // Labels depend on each other (slow-motion factor follows the frame rate).
        render(binding)
    }

    private fun fpsOptions(settings: PrecorderSettings): List<Int> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = activeCameraId ?: settings.cameraId ?: return listOf(settings.targetFps)
        return runCatching { FpsCatalog.supported(manager.getCameraCharacteristics(id)) }.getOrDefault(listOf(settings.targetFps))
    }

    private fun ChipGroup.fill(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
        setOnCheckedStateChangeListener(null)
        removeAllViews()
        options.forEach { (value, label) ->
            val chip = inflater.inflate(R.layout.item_choice_chip, this, false) as Chip
            chip.id = View.generateViewId()
            chip.text = label
            chip.tag = value
            chip.isChecked = value == selected
            addView(chip)
        }
        setOnCheckedStateChangeListener { group, checkedIds ->
            val chip = checkedIds.firstOrNull()?.let { group.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            onSelect(chip.tag as String)
        }
    }

    companion object {
        private val PLAYBACK_FPS = listOf(24, 30, 60)
        private val LOOP_SECONDS = listOf(5, 10)
        private val POST_ROLL_SECONDS = listOf(0, 1, 2, 3, 5)
        private val ASPECTS = listOf("16:9", "4:3")
    }
}
