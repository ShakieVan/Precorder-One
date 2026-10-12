package com.precorderone.camera

import android.hardware.camera2.CameraDevice
import android.os.Handler
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraDevice.close() returns immediately; the device is only free once StateCallback.onClosed arrives. Opening the
 * next camera before that (lens switch, normal <-> high-speed) makes many HALs answer CAMERA_IN_USE or
 * MAX_CAMERAS_IN_USE. Both session classes register their closes here and open the next camera only when all of them
 * have finished, or after [CLOSE_TIMEOUT_MS] if a HAL never reports onClosed.
 */
internal object CameraCloseGate {
    private const val TAG = "CameraCloseGate"
    private const val CLOSE_TIMEOUT_MS = 1_000L

    private val lock = Any()
    private val closing = mutableSetOf<CameraDevice>()
    private val waiters = mutableListOf<Runnable>()

    /** Closes [camera] and remembers it until onClosed. */
    fun close(camera: CameraDevice) {
        synchronized(lock) { closing += camera }
        runCatching { camera.close() }.onFailure { closed(camera) }
    }

    /** Called from StateCallback.onClosed. */
    fun closed(camera: CameraDevice) {
        val ready = synchronized(lock) {
            closing -= camera
            if (closing.isEmpty()) waiters.toList().also { waiters.clear() } else emptyList()
        }
        ready.forEach { it.run() }
    }

    /** Runs [action] on [handler] once no camera is closing anymore. */
    fun whenClear(handler: Handler, action: () -> Unit) {
        val done = AtomicBoolean(false)
        lateinit var timeout: Runnable
        val run = Runnable {
            if (done.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                action()
            }
        }
        timeout = Runnable {
            synchronized(lock) {
                if (closing.isNotEmpty()) Log.w(TAG, "No onClosed for ${closing.size} camera(s) after $CLOSE_TIMEOUT_MS ms")
                closing.clear()
                waiters.clear()
            }
            run.run()
        }
        val clear = synchronized(lock) {
            closing.isEmpty().also { empty -> if (!empty) waiters += Runnable { handler.post(run) } }
        }
        if (clear) {
            handler.post(run)
        } else {
            handler.postDelayed(timeout, CLOSE_TIMEOUT_MS)
        }
    }
}
