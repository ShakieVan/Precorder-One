package com.precorderone.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class FpsCatalogTest {
    // Samsung's normal sessions end at 30 fps (Galaxy S10 and S21); faster rates only come as high-speed sessions.
    private val samsungNormal = FpsCatalog.normal(
        listOf(15 to 15, 15 to 20, 20 to 20, 24 to 24, 8 to 30, 10 to 30, 15 to 30, 30 to 30)
    )
    private val s21Back = FpsCatalog.supported(samsungNormal, FpsCatalog.highSpeed(listOf(30 to 120, 120 to 120, 30 to 240, 240 to 240)))
    private val s21Front = FpsCatalog.supported(samsungNormal, FpsCatalog.highSpeed(listOf(30 to 120, 120 to 120)))
    private val s10Back = FpsCatalog.supported(samsungNormal, FpsCatalog.highSpeed(listOf(30 to 120, 60 to 120, 120 to 120, 240 to 240)))

    @Test
    fun listsNormalRatesAndTheUpperEndsOfHighSpeedRanges() {
        assertEquals(listOf(24, 25, 30, 120, 240), s21Back)
        assertEquals(listOf(24, 25, 30, 120), s21Front)
        // 60-120 is no reason to offer 60 fps: that is only how far the camera slows down in the dark.
        assertEquals(listOf(24, 25, 30, 120, 240), s10Back)
    }

    @Test
    fun offers60FpsWhereANormalSessionReachesIt() {
        val normal = FpsCatalog.normal(listOf(15 to 15, 15 to 30, 30 to 30, 15 to 60, 60 to 60))
        assertEquals(listOf(24, 25, 30, 48, 50, 60, 120), FpsCatalog.supported(normal, FpsCatalog.highSpeed(listOf(120 to 120))))
    }

    @Test
    fun replacesARateTheCameraDoesNotOfferByTheNextLowerOne() {
        // The preset of 60 fps on a phone without it.
        assertEquals(30, FpsCatalog.closest(s21Back, 60))
        // 240 fps of the back camera after switching to the front camera.
        assertEquals(120, FpsCatalog.closest(s21Front, 240))
        assertEquals(240, FpsCatalog.closest(s21Back, 240))
        assertEquals(30, FpsCatalog.closest(s21Back, 30))
        // Nothing below: the lowest rate there is.
        assertEquals(24, FpsCatalog.closest(s21Back, 10))
        assertEquals(60, FpsCatalog.closest(emptyList(), 60))
    }
}
