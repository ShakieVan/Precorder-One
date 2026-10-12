package com.precorderone.update

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.precorderone.R
import kotlinx.coroutines.launch

/** Release notes plus download/install in one dialog; follows [AppUpdater.state] while it is open. */
object UpdateDialog {
    fun show(activity: AppCompatActivity) {
        val release = AppUpdater.releaseOf(AppUpdater.state.value) ?: return
        val view = activity.layoutInflater.inflate(R.layout.dialog_update, null)
        val notes = view.findViewById<TextView>(R.id.updateNotes)
        val status = view.findViewById<TextView>(R.id.updateStatus)
        val progress = view.findViewById<LinearProgressIndicator>(R.id.updateProgress)
        notes.text = release.notes.ifBlank { activity.getString(R.string.update_no_notes) }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_dialog_title, release.version))
            .setView(view)
            .setPositiveButton(R.string.update_action_download, null)
            .setNegativeButton(R.string.update_action_later, null)
            .setNeutralButton(R.string.update_action_browser) { _, _ ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl))) }
            }
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            // Set after show(), so a tap does not close the dialog.
            positive.setOnClickListener {
                if (AppUpdater.state.value is AppUpdater.State.Ready) {
                    AppUpdater.install(activity)?.let { problem -> status.text = problem; status.visibility = View.VISIBLE }
                } else {
                    AppUpdater.download()
                }
            }
            val job = activity.lifecycleScope.launch {
                activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    AppUpdater.state.collect { state ->
                        val downloading = state is AppUpdater.State.Downloading
                        progress.visibility = if (downloading) View.VISIBLE else View.GONE
                        if (state is AppUpdater.State.Downloading) progress.setProgressCompat(state.percent, true)
                        positive.isEnabled = !downloading
                        positive.setText(
                            when (state) {
                                is AppUpdater.State.Ready -> R.string.update_action_install
                                is AppUpdater.State.Failed -> R.string.update_action_retry
                                else -> R.string.update_action_download
                            }
                        )
                        val text = when (state) {
                            is AppUpdater.State.Available -> ""
                            else -> AppUpdater.statusText(activity, state)
                        }
                        status.text = text
                        status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
            }
            dialog.setOnDismissListener { job.cancel() }
        }
        dialog.show()
    }
}
