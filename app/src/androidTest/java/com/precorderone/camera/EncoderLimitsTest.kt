package com.precorderone.camera

import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The phone's own word on what its encoder takes: used to pick the picture size from 60 fps on. */
@RunWith(AndroidJUnit4::class)
class EncoderLimitsTest {
    private val avc = MediaFormat.MIMETYPE_VIDEO_AVC

    @Test
    fun takesFullHdAt60FpsButNot8kAt240() {
        assertTrue("1080p30", EncoderLimits.manages(avc, 1920, 1080, 30))
        assertTrue("1080p60", EncoderLimits.manages(avc, 1920, 1080, 60))
        assertFalse("8K at 240 fps", EncoderLimits.manages(avc, 7680, 4320, 240))
    }

    @Test
    fun saysYesWhenThereIsNoSuchEncoder() {
        assertTrue(EncoderLimits.manages("video/does-not-exist", 1920, 1080, 30))
    }
}
