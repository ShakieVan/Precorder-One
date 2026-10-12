package com.precorderone.camera

import android.media.MediaCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class EncodedFrameRingBufferTest {
    private var released = 0

    private fun ring(capacity: Int, maxFrames: Int = 64, retentionUs: Long = 1_000_000L) =
        EncodedFrameRingBuffer(ByteBuffer.allocate(capacity), maxFrames, retentionUs) { released++ }

    /** Frame [index] is filled with its own index, so overwritten or misplaced bytes show up. */
    private fun frame(index: Int, size: Int): ByteBuffer =
        ByteBuffer.wrap(ByteArray(size) { (index % 251).toByte() })

    private fun EncodedFrameRingBuffer.add(index: Int, size: Int, ptsUs: Long = index * 10_000L, key: Boolean = false) =
        append(frame(index, size), size, ptsUs, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)

    private fun assertIntact(lease: EncodedFrameRingBuffer.Lease) {
        lease.frames.forEach { frame ->
            val index = (frame.presentationTimeUs / 10_000L).toInt()
            val data = lease.data(frame)
            assertEquals(frame.size, data.remaining())
            while (data.hasRemaining()) assertEquals("frame $index", (index % 251).toByte(), data.get())
        }
    }

    @Test
    fun keepsOnlyRetentionWindow() {
        val ring = ring(capacity = 100_000, retentionUs = 100_000L)
        repeat(50) { assertTrue(ring.add(it, 100)) }
        assertEquals(100_000L, ring.durationUs())
        ring.beginRead()!!.use { lease ->
            assertEquals(390_000L, lease.frames.first().presentationTimeUs)
            assertEquals(490_000L, lease.frames.last().presentationTimeUs)
        }
    }

    @Test
    fun wrapsAroundWithoutCorruptingFrames() {
        val ring = ring(capacity = 1_000, retentionUs = Long.MAX_VALUE / 2)
        // Sizes that never divide the capacity evenly, so frames land on every possible wrap position.
        repeat(500) { assertTrue(ring.add(it, 37 + (it * 53) % 211)) }
        ring.beginRead()!!.use { lease ->
            assertTrue(lease.frames.isNotEmpty())
            assertEquals(499 * 10_000L, lease.frames.last().presentationTimeUs)
            assertIntact(lease)
        }
    }

    @Test
    fun leaseProtectsFramesWhileRecordingGoesOn() {
        val ring = ring(capacity = 2_000, retentionUs = 50_000L)
        repeat(20) { ring.add(it, 100, key = it % 5 == 0) }
        val lease = ring.beginRead()!!
        val leased = lease.frames.map { it.presentationTimeUs }
        // Far more data than fits: once only leased frames are left, new frames must be dropped, not written over them.
        repeat(100) { ring.add(20 + it, 150) }
        assertTrue(ring.droppedFrames > 0)
        assertEquals(leased, lease.frames.map { it.presentationTimeUs })
        assertIntact(lease)
        lease.close()

        // After the export the writer continues normally and the buffer is full again right away.
        repeat(30) { assertTrue(ring.add(200 + it, 100)) }
        assertTrue(ring.durationUs() >= 50_000L)
        ring.beginRead()!!.use { assertIntact(it) }
    }

    @Test
    fun frameDataStartsAtItsOwnBytes() {
        // MediaMuxer ignores the buffer position and reads from the buffer start: each frame must be its own slice.
        val ring = ring(capacity = 1_000)
        repeat(5) { ring.add(it, 50 + it) }
        ring.beginRead()!!.use { lease ->
            lease.frames.forEach { frame ->
                val data = lease.data(frame)
                assertEquals(0, data.position())
                assertEquals(frame.size, data.capacity())
                val index = (frame.presentationTimeUs / 10_000L).toInt()
                assertEquals((index % 251).toByte(), data.get(0))
            }
        }
    }

    @Test
    fun onlyOneLeaseAtATime() {
        val ring = ring(capacity = 1_000)
        ring.add(0, 10)
        val first = ring.beginRead()
        assertNotNull(first)
        assertNull(ring.beginRead())
        first!!.close()
        assertNotNull(ring.beginRead())
    }

    @Test
    fun closeWaitsForRunningExport() {
        val ring = ring(capacity = 1_000)
        ring.add(0, 10)
        val lease = ring.beginRead()!!
        ring.close()
        assertEquals(0, released)
        assertTrue(!ring.add(1, 10))
        assertIntact(lease)
        lease.close()
        assertEquals(1, released)
        ring.close()
        assertEquals(1, released)
    }

    @Test
    fun rejectsFrameLargerThanBuffer() {
        val ring = ring(capacity = 100)
        assertTrue(!ring.add(0, 101))
        assertTrue(ring.add(1, 100))
    }
}
