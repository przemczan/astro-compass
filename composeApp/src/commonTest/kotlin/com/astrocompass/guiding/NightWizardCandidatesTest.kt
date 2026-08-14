package com.astrocompass.guiding

import com.astrocompass.astro.Angle
import com.astrocompass.astro.coords.EquatorialCoordinates
import com.astrocompass.astro.ephemeris.SolarSystemBody
import com.astrocompass.astro.time.AstroTime
import com.astrocompass.astro.utcMillis
import com.astrocompass.catalog.DeepSkyObject
import com.astrocompass.catalog.MapObjectFilter
import com.astrocompass.catalog.SkyObject
import com.astrocompass.catalog.SkyObjectType
import com.astrocompass.catalog.SolarSystemObject
import com.astrocompass.location.ObserverLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NightWizardCandidatesTest {

    // Equator observer at the Greenwich meridian, so "on the meridian, dec=0" sits at the
    // zenith (altitude ~90) and its antipode sits at the nadir (altitude ~-90) -- see
    // CoordinateTransformsTest for the same construction.
    private val epochMillis = utcMillis(2024, 3, 15, hour = 12)
    private val location = ObserverLocation(latitude = Angle.ZERO, longitude = Angle.ZERO)
    private val localSiderealTime = AstroTime.localSiderealTime(AstroTime.julianDay(epochMillis), location.longitude)

    private fun dsoAt(
        name: String,
        type: SkyObjectType,
        magnitude: Float,
        rightAscension: Angle,
    ): DeepSkyObject = DeepSkyObject(
        catalogDesignation = name,
        messier = 0,
        type = type,
        j2000 = EquatorialCoordinates(rightAscension, Angle.ZERO),
        magnitude = magnitude,
        constellation = "",
        commonName = "",
    )

    private fun highInSky(name: String, type: SkyObjectType, magnitude: Float) =
        dsoAt(name, type, magnitude, rightAscension = localSiderealTime)

    private fun belowHorizon(name: String, type: SkyObjectType, magnitude: Float) =
        dsoAt(name, type, magnitude, rightAscension = (localSiderealTime + Angle.ofDegrees(180.0)).normalized())

    private fun compute(
        objects: List<SkyObject>,
        filter: MapObjectFilter = MapObjectFilter(),
        magnitudeLimit: Float = 20f,
        minAltitudeDegrees: Float = -90f,
    ) = NightWizardCandidates.compute(objects, filter, magnitudeLimit, minAltitudeDegrees, location, epochMillis)

    @Test
    fun typeFilter_excludesUnselectedCategory() {
        val galaxy = highInSky("GAL", SkyObjectType.GALAXY, magnitude = 8f)
        val nebula = highInSky("NEB", SkyObjectType.NEBULA, magnitude = 8f)
        val result = compute(listOf(galaxy, nebula), filter = MapObjectFilter(showGalaxies = false))
        assertEquals(listOf(nebula), result)
    }

    @Test
    fun magnitudeFilter_excludesFainterThanLimit_andExcludesUnknownMagnitude() {
        // Unlike CatalogSearch, an unknown-magnitude DSO is excluded here, not passed through --
        // a brightness-vetted list can't honestly include an object whose brightness is unknown.
        val bright = highInSky("BRIGHT", SkyObjectType.NEBULA, magnitude = 5f)
        val faint = highInSky("FAINT", SkyObjectType.NEBULA, magnitude = 12f)
        val unknown = highInSky("UNKNOWN", SkyObjectType.NEBULA, magnitude = Float.NaN)
        val result = compute(listOf(bright, faint, unknown), magnitudeLimit = 9f)
        assertEquals(listOf(bright), result)
    }

    @Test
    fun altitudeFilter_excludesObjectsBelowMinimum() {
        val up = highInSky("UP", SkyObjectType.NEBULA, magnitude = 8f)
        val down = belowHorizon("DOWN", SkyObjectType.NEBULA, magnitude = 8f)
        val result = compute(listOf(up, down), minAltitudeDegrees = 20f)
        assertEquals(listOf(up), result)
    }

    @Test
    fun sun_isAlwaysExcluded_regardlessOfFilterOrAltitude() {
        val result = compute(listOf(SolarSystemObject(SolarSystemBody.SUN)), minAltitudeDegrees = -90f)
        assertTrue(result.isEmpty())
    }

    @Test
    fun solarSystemObject_bypassesMagnitudeFilter() {
        val moon = SolarSystemObject(SolarSystemBody.MOON)
        // A magnitude no real deep-sky object could ever beat, so this DSO would fail the filter --
        // the Moon must still come through since Solar System objects bypass it entirely.
        val faintDso = highInSky("FAINT", SkyObjectType.NEBULA, magnitude = 8f)
        val result = compute(listOf(faintDso, moon), magnitudeLimit = -100f, minAltitudeDegrees = -90f)
        assertEquals(listOf(moon), result)
    }

    @Test
    fun solarSystemObject_sortsBeforeDeepSkyObjects_evenNumericallyBrighterOnes() {
        val moon = SolarSystemObject(SolarSystemBody.MOON)
        val brightDso = highInSky("BRIGHT", SkyObjectType.NEBULA, magnitude = -5f)
        val result = compute(listOf(brightDso, moon), magnitudeLimit = 10f, minAltitudeDegrees = -90f)
        assertEquals(listOf(moon, brightDso), result)
    }

    @Test
    fun deepSkyObjects_sortAscendingByMagnitude() {
        val a = highInSky("A", SkyObjectType.NEBULA, magnitude = 5f)
        val b = highInSky("B", SkyObjectType.NEBULA, magnitude = 2f)
        val c = highInSky("C", SkyObjectType.NEBULA, magnitude = 8f)
        val result = compute(listOf(a, b, c))
        assertEquals(listOf(b, a, c), result)
    }
}
