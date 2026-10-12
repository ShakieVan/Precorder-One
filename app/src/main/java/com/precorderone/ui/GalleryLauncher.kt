package com.precorderone.ui

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.util.Locale

/**
 * Opens the gallery in the album that holds the saved clips.
 *
 * Android has no standard "open album" intent. Samsung Gallery offers the entry point its home-screen album
 * shortcuts use: SHORTCUT_ALBUM_VIEW with the MediaStore bucket id as ALBUM_ID (checked on One UI 7). Other
 * galleries get the clip itself through the camera "review" action, which shows it among the other media.
 */
object GalleryLauncher {
    private const val SAMSUNG_GALLERY = "com.sec.android.gallery3d"
    private const val SAMSUNG_ALBUM_VIEW = "com.android.gallery.action.SHORTCUT_ALBUM_VIEW"
    private const val SAMSUNG_ALBUM_ID = "ALBUM_ID"

    fun openAlbumOf(context: Context, clip: Uri): Boolean {
        bucketIdOf(context, clip)?.let { bucketId ->
            val album = Intent(SAMSUNG_ALBUM_VIEW)
                .setPackage(SAMSUNG_GALLERY)
                .putExtra(SAMSUNG_ALBUM_ID, bucketId)
            if (start(context, album)) return true
        }
        return listOf(MediaStore.ACTION_REVIEW, Intent.ACTION_VIEW).any { action ->
            start(context, Intent(action).setDataAndType(clip, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    /**
     * MediaStore's bucket id of the clip's folder: read from MediaStore, or for a clip in a folder picked in the
     * settings computed like MediaStore does it, as the hash of the lower-case folder path.
     */
    private fun bucketIdOf(context: Context, clip: Uri): Int? {
        if (clip.authority == MediaStore.AUTHORITY) {
            return runCatching {
                context.contentResolver.query(clip, arrayOf(MediaStore.Video.VideoColumns.BUCKET_ID), null, null, null)?.use {
                    if (it.moveToFirst()) it.getInt(0) else null
                }
            }.getOrNull()
        }
        if (clip.scheme != ContentResolver.SCHEME_CONTENT || !DocumentsContract.isDocumentUri(context, clip)) return null
        // "primary:DCIM/Clips/precorder_123.mp4" on the external storage provider.
        val documentId = DocumentsContract.getDocumentId(clip)
        if (documentId.substringBefore(':') != "primary") return null
        val folder = File(Environment.getExternalStorageDirectory(), documentId.substringAfter(':')).parentFile ?: return null
        return folder.path.lowercase(Locale.ROOT).hashCode()
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
