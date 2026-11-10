package com.precorderone.camera

import android.media.MediaCodec
import android.os.SharedMemory
import android.util.Log
import java.nio.ByteBuffer

/**
 * Ring buffer for encoded H.264 frames: one block of shared memory (outside the Java heap, pages are only committed
 * when written) used as a byte ring, plus a small index ring with offset, size, timestamp and flags per frame.
 *
 * - Write pointer: the encoder drain thread copies each frame once behind the newest one and wraps around at the end.
 *   Frames older than [retentionUs], and frames in the way of a new one, are dropped from the oldest end.
 * - Read pointer: an export takes a [Lease], a fixed list of frames that are not overwritten until the lease is
 *   closed. Recording goes on meanwhile and the buffer stays full after a save. Should the writer run out of space
 *   while the lease blocks the oldest frames, it drops the new frame instead ([droppedFrames]). A lost frame leaves
 *   a hole in time, and video frames behind it would build on a picture that is missing: the ring then starts over
 *   as soon as the export is done, for video ([keyFrameStart]) with the next key frame.
 */
class EncodedFrameRingBuffer internal constructor(
    private val memory: ByteBuffer,
    maxFrames: Int,
    private val retentionUs: Long,
    private val keyFrameStart: Boolean = true,
    private val onRelease: () -> Unit
) {
    class Frame(val offset: Int, val size: Int, val presentationTimeUs: Long, val flags: Int) {
        val isKeyFrame: Boolean get() = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
    }

    inner class Lease internal constructor(val frames: List<Frame>) : AutoCloseable {
        /**
         * Bytes of [frame] without copying, as a buffer that starts at the frame (position 0, capacity = size).
         * It must be a slice: MediaMuxer's native code reads from the buffer's base address plus BufferInfo.offset
         * and ignores the position, so a positioned duplicate would hand it the start of the ring for every frame.
         * Valid until the lease is closed; callers must only read.
         */
        fun data(frame: Frame): ByteBuffer = memory.duplicate().apply {
            limit(frame.offset + frame.size)
            position(frame.offset)
        }.slice()

        override fun close() = endRead(this)
    }

    private val capacity = memory.capacity()
    private val offsets = IntArray(maxFrames)
    private val sizes = IntArray(maxFrames)
    private val timestamps = LongArray(maxFrames)
    private val flags = IntArray(maxFrames)
    private var head = 0             // index of the oldest frame
    private var count = 0
    private var headSeq = 0L         // running number of the oldest frame
    private var writePos = 0         // byte offset right behind the newest frame
    private var lease: Lease? = null
    private var leaseFirstSeq = Long.MAX_VALUE
    private var closed = false
    // A frame was dropped: what is stored ends before a hole and must not be continued.
    private var broken = false

    var droppedFrames = 0L
        private set

    /** Copies [size] bytes from [source] (position..limit) into the ring. */
    @Synchronized
    fun append(source: ByteBuffer, size: Int, presentationTimeUs: Long, frameFlags: Int): Boolean {
        if (closed || size <= 0 || size > capacity) return false
        if (broken) {
            val standsAlone = !keyFrameStart || (frameFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            if (lease != null || !standsAlone) return drop()
            // Everything stored lies before the hole; a clip across it would freeze and jump.
            headSeq += count
            head = 0
            count = 0
            writePos = 0
            broken = false
        }
        while (count > 0 && timestamps[head] < presentationTimeUs - retentionUs && evictOldest()) Unit
        if (count == offsets.size && !evictOldest()) return drop()
        val position = reserve(size) ?: return drop()
        memory.duplicate().apply {
            limit(position + size)
            position(position)
            put(source)
        }
        val index = (head + count) % offsets.size
        offsets[index] = position
        sizes[index] = size
        timestamps[index] = presentationTimeUs
        flags[index] = frameFlags
        count++
        writePos = position + size
        return true
    }

    /** Time between the oldest and the newest frame. */
    @Synchronized
    fun durationUs(): Long {
        if (count < 2) return 0L
        return timestamps[(head + count - 1) % offsets.size] - timestamps[head]
    }

    /** Frames to export; they stay untouched until the lease is closed. Null if empty or another export runs. */
    @Synchronized
    fun beginRead(): Lease? {
        if (closed || lease != null || count == 0) return null
        val frames = List(count) { i ->
            val index = (head + i) % offsets.size
            Frame(offsets[index], sizes[index], timestamps[index], flags[index])
        }
        leaseFirstSeq = headSeq
        return Lease(frames).also { lease = it }
    }

    /** Frees the memory as soon as no export reads from it anymore. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        if (lease == null) release()
    }

    @Synchronized
    private fun endRead(finished: Lease) {
        if (lease !== finished) return
        lease = null
        leaseFirstSeq = Long.MAX_VALUE
        if (closed) release()
    }

    private fun release() = onRelease()

    private fun drop(): Boolean {
        droppedFrames++
        broken = true
        return false
    }

    /** Start offset for [size] bytes; drops oldest frames until they fit. Null when a lease blocks that. */
    private fun reserve(size: Int): Int? {
        while (true) {
            if (count == 0) {
                if (writePos + size > capacity) writePos = 0
                return writePos
            }
            val tail = offsets[head]
            // Data runs from tail to writePos; once writePos has wrapped behind the oldest frame, only the gap counts.
            val wrapped = writePos <= tail
            if (!wrapped) {
                if (writePos + size <= capacity) return writePos
                if (size <= tail) {
                    writePos = 0
                    return 0
                }
            } else if (writePos + size <= tail) {
                return writePos
            }
            if (!evictOldest()) return null
        }
    }

    private fun evictOldest(): Boolean {
        if (count == 0 || headSeq >= leaseFirstSeq) return false
        head = (head + 1) % offsets.size
        count--
        headSeq++
        return true
    }

    companion object {
        private const val TAG = "EncodedFrameRingBuffer"
        private const val MIN_BYTES = 8 shl 20
        const val MAX_BYTES = 512L shl 20
        private const val PEAK_FACTOR = 1.6

        /** Seconds of frames recorded while an export holds its lease: 3 s, more for long clips that take longer to write. */
        private fun leaseSeconds(seconds: Double) = 3.0 + seconds * 0.05

        /** Seconds of video at the target bitrate the ring needs room for: x1.6 for encoder peaks, plus [leaseSeconds]. */
        private fun sizingSeconds(retentionUs: Long): Double {
            val seconds = retentionUs / 1_000_000.0
            return seconds * PEAK_FACTOR + leaseSeconds(seconds)
        }

        /** Highest bitrate at which [retentionUs] of video still fit into [maxBytes]; long buffers must stay below it. */
        fun maxBitRate(retentionUs: Long, maxBytes: Long): Int =
            (maxBytes * 8.0 / sizingSeconds(retentionUs)).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()

        /**
         * Buffer for [retentionUs] of video at [bitRate], at most [maxBytes]. [keyFrameStart] is for video, where a
         * stream can only start with a key frame; sound frames stand alone.
         */
        fun create(
            bitRate: Int,
            fps: Int,
            retentionUs: Long,
            maxBytes: Long = MAX_BYTES,
            keyFrameStart: Boolean = true
        ): EncodedFrameRingBuffer? {
            val seconds = retentionUs / 1_000_000.0
            val limit = maxBytes.coerceIn(MIN_BYTES.toLong(), MAX_BYTES)
            var bytes = (bitRate / 8.0 * sizingSeconds(retentionUs)).toLong().coerceIn(MIN_BYTES.toLong(), limit).toInt()
            val maxFrames = (fps.coerceAtLeast(30) * (seconds + leaseSeconds(seconds)) * 1.5).toInt()
            while (bytes >= MIN_BYTES) {
                try {
                    val shared = SharedMemory.create("precorder-ring", bytes)
                    val memory = shared.mapReadWrite()
                    return EncodedFrameRingBuffer(memory, maxFrames, retentionUs, keyFrameStart) {
                        SharedMemory.unmap(memory)
                        shared.close()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not allocate $bytes bytes, trying half", e)
                    bytes /= 2
                }
            }
            return null
        }
    }
}
