package com.astrocompass.astro.ephemeris

import com.astrocompass.astro.Angle
import com.astrocompass.astro.coords.CoordinateTransforms
import com.astrocompass.astro.time.AstroTime
import com.astrocompass.astro.utcMillis
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TwilightTest {

    private val midLatitude = Angle.ofDegrees(40.0)
    private val greenwich = Angle.ofDegrees(0.0)

    private fun sunAltitudeDegrees(epochMillis: Long, latitude: Angle, longitude: Angle): Double {
        val julianDay = AstroTime.julianDay(epochMillis)
        val julianCenturies = AstroTime.julianCenturiesJ2000(julianDay)
        val lst = AstroTime.localSiderealTime(julianDay, longitude)
        val sun = SunEphemeris.geocentricEquatorial(julianCenturies)
        return CoordinateTransforms.equatorialToHorizontal(sun, lst, latitude).altitude.degrees
    }

    @Test
    fun nextNauticalDusk_fromMidday_landsWhereSunIsAtMinusTwelveDegrees() {
        val noon = utcMillis(2024, 3, 15, hour = 12)
        val dusk = Twilight.nextNauticalDusk(noon, midLatitude, greenwich)
        assertTrue(dusk != null && dusk > noon, "expected a dusk crossing after noon, got $dusk")
        val altitude = sunAltitudeDegrees(dusk, midLatitude, greenwich)
        assertTrue(altitude in -12.3..-11.7, "Sun altitude at reported dusk was $altitude")
    }

    @Test
    fun nextNauticalDawn_fromMidnight_landsWhereSunIsAtMinusTwelveDegrees() {
        val midnight = utcMillis(2024, 3, 15, hour = 0)
        val dawn = Twilight.nextNauticalDawn(midnight, midLatitude, greenwich)
        assertTrue(dawn != null && dawn > midnight, "expected a dawn crossing after midnight, got $dawn")
        val altitude = sunAltitudeDegrees(dawn, midLatitude, greenwich)
        assertTrue(altitude in -12.3..-11.7, "Sun altitude at reported dawn was $altitude")
    }

    @Test
    fun nauticalDusk_atHighSummerLatitude_neverOccurs() {
        // Above ~54.5 deg latitude, the Sun never drops below -12 deg altitude around the June
        // solstice ("white nights") -- 70N in June is comfortably inside that band.
        val highLatitude = Angle.ofDegrees(70.0)
        val juneNoon = utcMillis(2024, 6, 20, hour = 12)
        val dusk = Twilight.nextNauticalDusk(juneNoon, highLatitude, greenwich)
        assertNull(dusk, "expected no nautical dusk at 70N in June, got $dusk")
    }
}
