package com.precorderone

import android.Manifest
import android.content.ContentUris
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.widget.TextView
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole way on a real phone: camera -> encoder -> ring, trigger, saved clip. Works on a locked test phone too,
 * because the test (not the app) asks for the activity to be shown above the lock screen.
 *
 * Right after the first clip a second one is saved. It holds what the camera recorded while the first one was being
 * saved, so it shows whether recording really went on without a hole.
 *
 * Settings come from instrumentation arguments, e.g. -Pandroid.testInstrumentationRunnerArguments.loop=60:
 * loop (seconds), fps, playback, timecode (true/false), lens (back/front), audio (true/false), keep (true/false).
 * With keep=true the clips stay in DCIM/Precorder-One to look at them; otherwise the test removes them.
 * Timings go to logcat (tag LiveCaptureTest).
 */
@ExperimentalCamera2Interop
@RunWith(AndroidJUnit4::class)
class LiveCaptureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private class Clip(val uri: Uri, val frames: Int, val durationUs: Long, val longestGapUs: Long, val hasSound: Boolean)

    @Test
    fun savesClipsFromTheRunningCamera() {
        val arguments = InstrumentationRegistry.getArguments()
        val loop = arguments.getString("loop")?.toInt() ?: 5
        val fps = arguments.getString("fps")?.toInt() ?: 30
        val playback = arguments.getString("playback")?.toInt() ?: fps
        val timecode = arguments.getString("timecode")?.toBoolean() ?: true
        val lens = arguments.getString("lens") ?: "back"
        val audio = arguments.getString("audio")?.toBoolean() ?: false
        val keep = arguments.getString("keep")?.toBoolean() ?: false

        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        if (audio) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString("loop_seconds", loop.toString())
            .putString("target_fps", fps.toString())
            .putString("playback_fps", playback.toString())
            .putString("post_roll", "0")
            .putString("lens_facing", lens)
            .remove("camera_id")
            .putBoolean("record_audio", audio)
            .putBoolean("burn_timecode", timecode)
            .commit()

        val saved = mutableListOf<Clip>()
        try {
            LockedPhone.showingActivities {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    val ready = context.getString(R.string.status_ready)
                    val fillStart = SystemClock.elapsedRealtime()
                    assertTrue(
                        "buffer not ready, status: ${status(scenario)}",
                        waitUntil((loop + 25) * 1000L) { status(scenario) == ready }
                    )
                    Log.i(TAG, "READY after ${SystemClock.elapsedRealtime() - fillStart} ms (loop $loop s, $fps -> $playback fps, timecode $timecode, $lens camera, sound $audio)")

                    val first = saveClip(scenario, "FIRST")
                    saved += first
                    // Slow motion stretches the clip; the camera may also deliver fewer pictures than asked for in a
                    // dark room, so the number of frames only has a loose lower bound.
                    val stretch = fps.toDouble() / playback
                    val seconds = first.durationUs / 1_000_000.0 / stretch
                    assertTrue("clip covers $seconds s", seconds > loop * 0.8 && seconds < loop + 2)
                    assertTrue("only ${first.frames} frames", first.frames >= loop * 10)
                    assertEquals("sound track", audio && fps == playback, first.hasSound)

                    // The buffer must not have started over while the first clip was saved ...
                    assertEquals(ready, status(scenario))
                    // ... and what was recorded meanwhile must be there without a hole.
                    val second = saveClip(scenario, "SECOND")
                    saved += second
                    val secondSeconds = second.durationUs / 1_000_000.0 / stretch
                    assertTrue("second clip covers $secondSeconds s", secondSeconds > loop * 0.8 && secondSeconds < loop + 2)
                    val gapMs = second.longestGapUs / 1000.0 / stretch
                    assertTrue("hole of $gapMs ms in what was recorded while saving", gapMs < 500)
                }
            }
        } finally {
            if (!keep) saved.forEach { context.contentResolver.delete(it.uri, null, null) }
        }
    }

    /** Triggers like a volume key and waits until the clip is saved. */
    private fun saveClip(scenario: ActivityScenario<MainActivity>, label: String): Clip {
        val before = newestClip()
        val start = SystemClock.elapsedRealtime()
        scenario.onActivity { it.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)) }
        val saving = context.getString(R.string.status_saving).substringBefore(' ')
        // What the status chip showed on the way, e.g. the percentage while the timecode is burnt in.
        val shown = LinkedHashSet<String>()
        val done = waitUntil(900_000L) {
            val status = status(scenario)
            if (status.startsWith(saving) && shown.add(status) && shown.size == 3) LockedPhone.screenshot("saving")
            !status.startsWith(saving)
        }
        assertTrue("still saving: ${status(scenario)}", done)
        val saveMs = SystemClock.elapsedRealtime() - start
        val uri = newestClip()
        assertTrue("no new clip saved", uri != null && uri != before)
        val clip = read(uri!!)
        Log.i(
            TAG,
            "$label saved in $saveMs ms: ${clip.frames} frames, ${clip.durationUs / 1000} ms long, longest gap " +
                "${clip.longestGapUs / 1000.0} ms, sound ${clip.hasSound}, status afterwards: ${status(scenario)}, " +
                "shown while saving: ${shown.take(4)}${if (shown.size > 4) " … ${shown.last()}" else ""}, $uri"
        )
        return clip
    }

    private fun status(scenario: ActivityScenario<MainActivity>): String {
        var text = ""
        scenario.onActivity { text = it.findViewById<TextView>(R.id.statusChip).text.toString() }
        return text
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return true
            SystemClock.sleep(200)
        }
        return condition()
    }

    private fun newestClip(): Uri? =
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("precorder_%"),
            "${MediaStore.Video.Media._ID} DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)) else null
        }

    private fun read(uri: Uri): Clip {
        val extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
        val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!! }
        extractor.selectTrack(mimes.indexOfFirst { it.startsWith("video/") })
        var frames = 0
        var lastUs = 0L
        var longestGapUs = 0L
        while (extractor.sampleTime >= 0) {
            if (frames > 0) longestGapUs = maxOf(longestGapUs, extractor.sampleTime - lastUs)
            frames++
            lastUs = extractor.sampleTime
            extractor.advance()
        }
        extractor.release()
        return Clip(uri, frames, lastUs, longestGapUs, mimes.any { it.startsWith("audio/") })
    }

    private companion object {
        const val TAG = "LiveCaptureTest"
    }
}
