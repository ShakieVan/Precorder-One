package com.precorderone.camera

import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * Ring buffer for encoded H.264 frames in memory.
 */
class EncodedFrameRingBuffer(private val retentionUs: Long) {
    private val queue = ArrayDeque<EncodedFrame>()

    @Synchronized
    fun append(frame: EncodedFrame) {
        queue.addLast(frame)
        trim(frame.presentationTimeUs)
    }

    @Synchronized
    fun snapshot(): List<EncodedFrame> = queue.toList()

    @Synchronized
    fun clear() = queue.clear()

    private fun trim(latestPtsUs: Long) {
        val cutoff = latestPtsUs - retentionUs
        while (queue.isNotEmpty() && queue.first().presentationTimeUs < cutoff) {
            queue.removeFirst()
        }
    }
}

data class EncodedFrame(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val flags: Int,
    val isConfig: Boolean
) {
    fun asByteBuffer(): ByteBuffer = ByteBuffer.wrap(data)
}
