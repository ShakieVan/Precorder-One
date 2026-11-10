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

    private fun ring(capacity: Int, maxFrames: Int = 64, retentionUs: Long = 1_000_000L, keyFrameStart: Boolean = true) =
        EncodedFrameRingBuffer(ByteBuffer.allocate(capacity), maxFrames, retentionUs, keyFrameStart) { released++ }

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

        // After the export the writer continues with the next key frame and the buffer fills up again.
        repeat(30) { assertTrue(ring.add(200 + it, 100, key = it == 0)) }
        assertTrue(ring.durationUs() >= 50_000L)
        ring.beginRead()!!.use { assertIntact(it) }
    }

    @Test
    fun startsOverWithKeyFrameAfterLostFrames() {
        val ring = ring(capacity = 2_000, retentionUs = Long.MAX_VALUE / 2)
        repeat(10) { ring.add(it, 100, key = it == 0) }
        val lease = ring.beginRead()!!
        // The export takes so long that the ring runs full: frames 20..24 are lost.
        repeat(15) { ring.add(10 + it, 100) }
        assertEquals(5L, ring.droppedFrames)
        lease.close()

        // Frames that build on a lost one are useless, and nothing may be stored behind the hole in time.
        assertTrue(!ring.add(25, 100))
        assertTrue(!ring.add(26, 100))
        assertTrue(ring.add(27, 100, key = true))
        assertTrue(ring.add(28, 100))
        assertEquals(10_000L, ring.durationUs())
        ring.beginRead()!!.use { fresh ->
            assertEquals(listOf(270_000L, 280_000L), fresh.frames.map { it.presentationTimeUs })
            assertTrue(fresh.frames.first().isKeyFrame)
            assertIntact(fresh)
        }
    }

    @Test
    fun exportDuringTheHoleStillGetsTheOldFrames() {
        val ring = ring(capacity = 1_000, retentionUs = Long.MAX_VALUE / 2)
        repeat(5) { ring.add(it, 100, key = it == 0) }
        val first = ring.beginRead()!!
        repeat(10) { ring.add(5 + it, 100) }
        assertTrue(ring.droppedFrames > 0)
        first.close()
        // No key frame has arrived yet: what is stored is still one unbroken stretch and may be saved.
        ring.beginRead()!!.use { second ->
            assertEquals(0L, second.frames.first().presentationTimeUs)
            assertEquals(90_000L, second.frames.last().presentationTimeUs)
            assertIntact(second)
        }
    }

    @Test
    fun soundContinuesWithoutKeyFrame() {
        val ring = ring(capacity = 1_000, retentionUs = Long.MAX_VALUE / 2, keyFrameStart = false)
        repeat(5) { ring.add(it, 100) }
        val lease = ring.beginRead()!!
        repeat(10) { ring.add(5 + it, 100) }
        assertTrue(ring.droppedFrames > 0)
        lease.close()
        assertTrue(ring.add(15, 100))
        ring.beginRead()!!.use { assertEquals(listOf(150_000L), it.frames.map { frame -> frame.presentationTimeUs }) }
    }

    @Test
    fun longBufferLimitsTheBitRateToItsMemory() {
        val oneMinute = 61_000_000L
        val limit = EncodedFrameRingBuffer.maxBitRate(oneMinute, EncodedFrameRingBuffer.MAX_BYTES)
        // 512 MB hold a minute at roughly 40 Mbit/s once the reserve for encoder peaks is taken off.
        assertTrue("limit $limit", limit in 35_000_000..45_000_000)
        assertTrue(EncodedFrameRingBuffer.maxBitRate(11_000_000L, EncodedFrameRingBuffer.MAX_BYTES) > 80_000_000)
        assertTrue(EncodedFrameRingBuffer.maxBitRate(oneMinute, EncodedFrameRingBuffer.MAX_BYTES / 2) in limit / 2 - 1..limit / 2 + 1)
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
