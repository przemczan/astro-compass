package com.astrocompass.catalog

import com.astrocompass.astro.Angle
import com.astrocompass.astro.coords.EquatorialCoordinates
import com.astrocompass.astro.ephemeris.SolarSystemBody
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapObjectFilterTest {

    private fun star(magnitude: Float) = StarObject(
        hygId = 1,
        hip = 0,
        properName = "Test",
        bayer = "",
        flamsteed = 0,
        constellation = "",
        j2000 = EquatorialCoordinates(Angle.ZERO, Angle.ZERO),
        magnitude = magnitude,
    )

    private fun galaxy(magnitude: Float) = DeepSkyObject(
        catalogDesignation = "NGC0001",
        messier = 0,
        type = SkyObjectType.GALAXY,
        j2000 = EquatorialCoordinates(Angle.ZERO, Angle.ZERO),
        magnitude = magnitude,
        constellation = "",
        commonName = "",
    )

    @Test
    fun noMagnitudeLimit_admitsEveryBrightness() {
        val filter = MapObjectFilter()

        assertTrue(filter.matches(star(20f)))
        assertTrue(filter.matches(galaxy(20f)))
    }

    @Test
    fun magnitudeLimit_hidesDimmerObjects() {
        val filter = MapObjectFilter(maxMagnitude = 5f)

        assertTrue(filter.matches(star(2.5f)), "Expected a mag 2.5 star to pass a mag 5 limit")
        assertTrue(filter.matches(star(5f)), "Expected a star exactly at the limit to pass")
        assertFalse(filter.matches(star(6.8f)), "Expected a mag 6.8 star to be hidden by a mag 5 limit")
    }

    @Test
    fun magnitudeLimit_exemptsObjectsWithNoKnownMagnitude() {
        val filter = MapObjectFilter(maxMagnitude = 1f)

        assertTrue(filter.matches(SolarSystemObject(SolarSystemBody.JUPITER)))
        assertTrue(filter.matches(galaxy(Float.NaN)))
    }

    @Test
    fun magnitudeLimit_stillHonorsCategoryToggles() {
        val filter = MapObjectFilter(showGalaxies = false, maxMagnitude = 20f)

        assertFalse(filter.matches(galaxy(1f)), "A hidden category should stay hidden regardless of brightness")
    }
}
