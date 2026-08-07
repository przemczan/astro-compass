package com.astrocompass.astro.coords

import com.astrocompass.astro.Angle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoordinateTransformsTest {

    private val latitude = Angle.ofDegrees(52.0)
    private val lst = Angle.ofDegrees(123.0)

    @Test
    fun northCelestialPole_hasAltitudeEqualToLatitudeAndAzimuthNorth() {
        // Independently checkable fact: the NCP's altitude always equals the observer's latitude.
        for (testLatitude in listOf(10.0, 35.0, 52.0, 89.0)) {
            val ncp = EquatorialCoordinates(Angle.ofDegrees(200.0), Angle.ofDegrees(90.0))
            val horizontal = CoordinateTransforms.equatorialToHorizontal(ncp, lst, Angle.ofDegrees(testLatitude))
            assertEquals(testLatitude, horizontal.altitude.degrees, absoluteTolerance = 1e-6)
            assertEquals(0.0, horizontal.azimuth.normalizedSigned().degrees, absoluteTolerance = 1e-6)
        }
    }

    @Test
    fun equatorPoint_onMeridian_transitsDueSouthForNorthernObserver() {
        // A body on the celestial equator (dec=0) crossing the meridian (H=0, i.e. RA=LST) for a
        // northern-hemisphere observer transits south of the zenith at altitude (90 - latitude).
        val onMeridianEquator = EquatorialCoordinates(lst, Angle.ZERO)
        val horizontal = CoordinateTransforms.equatorialToHorizontal(onMeridianEquator, lst, latitude)
        assertEquals(90.0 - latitude.degrees, horizontal.altitude.degrees, absoluteTolerance = 1e-6)
        assertEquals(180.0, horizontal.azimuth.degrees, absoluteTolerance = 1e-6)
    }

    @Test
    fun equatorPoint_atHourAngle90_setsDueWestOnTheHorizon() {
        // H = +90 degrees (RA = LST - 90) on the celestial equator sits exactly on the horizon, due west.
        val ra = (lst - Angle.ofDegrees(90.0)).normalized()
        val westPoint = EquatorialCoordinates(ra, Angle.ZERO)
        val horizontal = CoordinateTransforms.equatorialToHorizontal(westPoint, lst, latitude)
        assertEquals(0.0, horizontal.altitude.degrees, absoluteTolerance = 1e-6)
        assertEquals(270.0, horizontal.azimuth.degrees, absoluteTolerance = 1e-6)
    }

    @Test
    fun toEnu_alwaysProducesAUnitVector() {
        for (az in listOf(0.0, 45.0, 123.0, 270.0)) {
            for (alt in listOf(-10.0, 0.0, 30.0, 89.0)) {
                val v = HorizontalCoordinates(Angle.ofDegrees(az), Angle.ofDegrees(alt)).toEnu()
                assertEquals(1.0, v.length, absoluteTolerance = 1e-9)
            }
        }
    }

    @Test
    fun equatorialToHorizontalToEquatorial_roundTrips() {
        for (ra in listOf(10.0, 123.4, 300.0)) {
            for (dec in listOf(-40.0, 0.0, 38.7, 75.0)) {
                val original = EquatorialCoordinates(Angle.ofDegrees(ra), Angle.ofDegrees(dec))
                val horizontal = CoordinateTransforms.equatorialToHorizontal(original, lst, latitude)
                val recovered = CoordinateTransforms.horizontalToEquatorial(horizontal, lst, latitude)
                assertEquals(original.rightAscension.degrees, recovered.rightAscension.degrees, absoluteTolerance = 1e-6)
                assertEquals(original.declination.degrees, recovered.declination.degrees, absoluteTolerance = 1e-6)
            }
        }
    }

    @Test
    fun polarisAltitude_approximatesObserverLatitude() {
        // Polaris sits about 0.7 degrees from the true NCP; well within a loose tolerance this
        // still confirms the transform places it near altitude = latitude.
        val polaris = EquatorialCoordinates(Angle.ofHms(2, 31, 49.0), Angle.ofDms(89, 15, 51.0))
        val horizontal = CoordinateTransforms.equatorialToHorizontal(polaris, lst, latitude)
        assertTrue(kotlin.math.abs(horizontal.altitude.degrees - latitude.degrees) < 1.0)
    }
}
