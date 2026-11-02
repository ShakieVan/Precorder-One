package com.precorderone.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.precorderone.R

/**
 * Black stand-in between the camera and the gallery; it closes itself as soon as the gallery is gone.
 *
 * Samsung's album view is a translucent activity, and Android takes the orientation from the activity behind a
 * translucent one. Right on top of the camera the gallery would inherit its portrait lock, so videos could not be
 * watched in landscape. This opaque activity hides the camera, which keeps its lock and never rotates, and asks for
 * no orientation itself. The manifest keeps a rotation from recreating it: that would close it behind the gallery.
 */
class GalleryHostActivity : Activity() {
    private var galleryOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restored after the process died behind the gallery: the gallery is already there.
        galleryOpened = savedInstanceState != null
        if (galleryOpened) return
        val clip = intent.data
        if (clip == null || !GalleryLauncher.openAlbumOf(this, clip)) {
            Toast.makeText(this, R.string.no_video_player, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (galleryOpened) finish()
    }

    override fun onPause() {
        super.onPause()
        galleryOpened = true
    }
}
