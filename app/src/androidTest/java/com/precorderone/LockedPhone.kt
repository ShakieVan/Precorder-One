package com.precorderone

import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Lets tests with a screen run on a locked test phone: while [showingActivities] runs, the test (not the app) asks
 * for every activity to be shown above the lock screen and for the screen to be switched on.
 */
internal object LockedPhone {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    fun <T> showingActivities(block: () -> T): T {
        val app = instrumentation.targetContext.applicationContext as Application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        try {
            return block()
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    /** What is on the screen right now, as Download/precorder_export_test_<tag>_*.png (outlives the test app). */
    fun screenshot(tag: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val resolver = instrumentation.targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "precorder_export_test_${tag}_${System.currentTimeMillis()}.png")
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
