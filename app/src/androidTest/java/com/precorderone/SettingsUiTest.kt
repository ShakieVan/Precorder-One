package com.precorderone

import android.Manifest
import android.os.SystemClock
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withParent
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.precorderone.camera.Corner
import com.precorderone.data.SettingsRepository
import com.precorderone.ui.SettingsActivity
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The buffer lengths up to a minute, the timecode with its corner and the speed label, in the settings and in the quick menu. */
@ExperimentalCamera2Interop
@RunWith(AndroidJUnit4::class)
class SettingsUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun settingsOfferAMinuteOfBufferAndTheTimecodeSwitch() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        LockedPhone.showingActivities {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.fragments.filterIsInstance<PreferenceFragmentCompat>().single()
                    val loop = fragment.findPreference<ListPreference>("loop_seconds")!!
                    assertEquals(listOf("5", "10", "30", "60"), loop.entryValues.map { it.toString() })
                    assertEquals(loop.entryValues.size, loop.entries.size)
                    val timecode = fragment.findPreference<SwitchPreferenceCompat>("burn_timecode")
                    assertNotNull(timecode)
                    // On unless switched off, the same default the camera screen uses when it saves.
                    assertTrue(timecode!!.isChecked)
                    assertTrue(SettingsRepository(activity).burnTimecode())

                    // Top left unless changed, and every corner on offer.
                    val corner = fragment.findPreference<ListPreference>("timecode_corner")!!
                    assertEquals("top_left", corner.value)
                    assertEquals(Corner.values().map { it.key }, corner.entryValues.map { it.toString() })
                    assertEquals(corner.entryValues.size, corner.entries.size)
                    assertTrue(corner.isEnabled)
                    assertEquals(Corner.TOP_LEFT, SettingsRepository(activity).timecodeCorner())
                    val speedLabel = fragment.findPreference<SwitchPreferenceCompat>("speed_label")
                    assertNotNull(speedLabel)
                    assertTrue(speedLabel!!.isChecked)
                    assertTrue(SettingsRepository(activity).showSpeedLabel())
                }
                onView(withText(R.string.pref_timecode_title)).check(matches(isDisplayed()))
                LockedPhone.screenshot("settings")
                onView(withText(R.string.pref_timecode_title)).perform(click())
                assertFalse(SettingsRepository(context).burnTimecode())
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.fragments.filterIsInstance<PreferenceFragmentCompat>().single()
                    // Without timecode there is no corner to choose.
                    assertFalse(fragment.findPreference<ListPreference>("timecode_corner")!!.isEnabled)
                }
            }
        }
    }

    @Test
    fun quickMenuSetsBufferLengthAndTimecode() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        LockedPhone.showingActivities {
            ActivityScenario.launch(MainActivity::class.java).use {
                // The sheet slides in, at full height: the timecode row is on the screen without dragging. On a locked
                // phone the lock screen is still fading out when the activity reports itself ready, and a tap sent
                // in that moment goes nowhere: tap again until the sheet is there (a tap with the sheet in front
                // finds no chip and does nothing).
                eventually {
                    runCatching { onView(withId(R.id.statusChip)).perform(click()) }
                    SystemClock.sleep(1_000)
                    onView(withText(R.string.quick_timecode)).inRoot(isDialog()).check(matches(isDisplayed()))
                }
                LockedPhone.screenshot("quickmenu")
                val inTimecodeRow = withParent(withId(R.id.timecodeGroup))
                onView(allOf(withText("Oben links"), inTimecodeRow)).inRoot(isDialog()).check(matches(isChecked()))

                val repository = SettingsRepository(context)
                onView(withText("60 s")).inRoot(isDialog()).perform(click())
                assertEquals(60, repository.load().loopSeconds)

                onView(allOf(withText("Unten rechts"), inTimecodeRow)).inRoot(isDialog()).perform(scrollTo(), click())
                assertEquals(Corner.BOTTOM_RIGHT, repository.timecodeCorner())
                assertTrue(repository.burnTimecode())

                // "Aus" exists twice, for the post-roll and for the timecode.
                onView(allOf(withText(R.string.quick_post_roll_off), inTimecodeRow)).inRoot(isDialog()).perform(scrollTo(), click())
                assertFalse(repository.burnTimecode())
                // The corner is kept for the next time the timecode is switched on.
                assertEquals(Corner.BOTTOM_RIGHT, repository.timecodeCorner())
                // Switching the timecode does not touch the recording settings.
                assertEquals(60, repository.load().loopSeconds)
            }
        }
    }

    /** Repeats [check] until it passes, for up to ten seconds. */
    private fun eventually(check: () -> Unit) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (true) {
            try {
                check()
                return
            } catch (e: Throwable) {
                if (SystemClock.elapsedRealtime() > end) throw e
                SystemClock.sleep(200)
            }
        }
    }
}
