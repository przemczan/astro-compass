package com.astrocompass.guiding

import com.astrocompass.astro.Angle
import com.astrocompass.astro.utcMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NightWizardDefaultStartTimeTest {

    private val midLatitude = Angle.ofDegrees(40.0)
    private val greenwich = Angle.ofDegrees(0.0)

    @Test
    fun duringDaylight_defaultsToUpcomingDusk() {
        val noon = utcMillis(2024, 3, 15, hour = 12)
        val result = NightWizardDefaultStartTime.compute(noon, midLatitude, greenwich)
        assertTrue(result.twilightKnown)
        assertTrue(result.startEpochMillis > noon, "expected a later dusk time, got ${result.startEpochMillis}")
    }

    @Test
    fun duringTheDarkWindow_defaultsToNow() {
        val midnight = utcMillis(2024, 3, 15, hour = 0)
        val result = NightWizardDefaultStartTime.compute(midnight, midLatitude, greenwich)
        assertTrue(result.twilightKnown)
        assertEquals(midnight, result.startEpochMillis)
    }

    @Test
    fun whenTwilightNeverOccurs_fallsBackToNowAndSaysSo() {
        val highLatitude = Angle.ofDegrees(70.0)
        val juneNoon = utcMillis(2024, 6, 20, hour = 12)
        val result = NightWizardDefaultStartTime.compute(juneNoon, highLatitude, greenwich)
        assertEquals(false, result.twilightKnown)
        assertEquals(juneNoon, result.startEpochMillis)
    }
}
