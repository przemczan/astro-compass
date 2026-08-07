package com.astroguider.astro.ephemeris

import com.astroguider.astro.time.AstroTime
import com.astroguider.astro.utcMillis
import kotlin.test.Test
import kotlin.test.assertTrue

class EphemerisTest {

    private fun tFor(year: Int, month: Int, day: Int): Double {
        val jd = AstroTime.julianDay(utcMillis(year, month, day, hour = 12))
        return AstroTime.julianCenturiesJ2000(jd)
    }

    @Test
    fun sunDeclination_nearJuneSolstice_isNearMaxObliquity() {
        // Declination is at a flat extremum right at the solstice, so a day or two of date
        // uncertainty barely moves it -- unlike near an equinox, where it moves fastest.
        // 23.44 degrees is the well-known obliquity of the ecliptic.
        val t = tFor(2024, 6, 20)
        val dec = SunEphemeris.geocentricEquatorial(t).declination.degrees
        assertTrue(dec in 23.0..23.6, "June solstice Sun declination was $dec")
    }

    @Test
    fun sunDeclination_nearDecemberSolstice_isNearMinusMaxObliquity() {
        val t = tFor(2024, 12, 21)
        val dec = SunEphemeris.geocentricEquatorial(t).declination.degrees
        assertTrue(dec in -23.6..-23.0, "December solstice Sun declination was $dec")
    }

    @Test
    fun moonDeclination_neverExceedsObliquityPlusOrbitalInclination() {
        // Bound that holds regardless of the 18.6-year nodal cycle: |dec| <= obliquity (23.44) +
        // the Moon's orbital inclination to the ecliptic (~5.14 degrees) = ~28.6 degrees.
        for ((y, m, d) in listOf(Triple(2024, 1, 1), Triple(2024, 4, 1), Triple(2024, 7, 1), Triple(2024, 10, 1), Triple(2025, 1, 1))) {
            val t = tFor(y, m, d)
            val dec = MoonEphemeris.geocentricEquatorial(t).declination.degrees
            assertTrue(kotlin.math.abs(dec) < 29.0, "Moon declination out of bound on $y-$m-$d: $dec")
        }
    }

    @Test
    fun moonDistance_staysWithinThePerigeeApogeeRange() {
        // Well-known range: perigee ~356500 km, apogee ~406700 km. Generous margin either side.
        for ((y, m, d) in listOf(Triple(2024, 1, 1), Triple(2024, 4, 1), Triple(2024, 7, 1), Triple(2024, 10, 1))) {
            val t = tFor(y, m, d)
            val distance = MoonEphemeris.distanceKm(t)
            assertTrue(distance in 350000.0..410000.0, "Moon distance out of range on $y-$m-$d: $distance")
        }
    }

    @Test
    fun mercury_neverExceedsItsWellKnownMaximumElongation() {
        // Mercury's greatest elongation from the Sun never exceeds ~28 degrees -- an independent,
        // well-known fact this low-precision orbital-element pipeline should still respect.
        for ((y, m, d) in sampleDatesOverTwoYears()) {
            val t = tFor(y, m, d)
            val sun = SunEphemeris.geocentricEquatorial(t)
            val mercury = PlanetEphemeris.geocentricEquatorialOfDate(SolarSystemBody.MERCURY, t)
            val elongation = angularSeparationDegrees(sun.rightAscension.degrees, sun.declination.degrees, mercury.rightAscension.degrees, mercury.declination.degrees)
            assertTrue(elongation < 29.0, "Mercury elongation on $y-$m-$d was $elongation")
        }
    }

    @Test
    fun venus_neverExceedsItsWellKnownMaximumElongation() {
        // Venus's greatest elongation never exceeds ~47 degrees.
        for ((y, m, d) in sampleDatesOverTwoYears()) {
            val t = tFor(y, m, d)
            val sun = SunEphemeris.geocentricEquatorial(t)
            val venus = PlanetEphemeris.geocentricEquatorialOfDate(SolarSystemBody.VENUS, t)
            val elongation = angularSeparationDegrees(sun.rightAscension.degrees, sun.declination.degrees, venus.rightAscension.degrees, venus.declination.degrees)
            assertTrue(elongation < 48.0, "Venus elongation on $y-$m-$d was $elongation")
        }
    }

    @Test
    fun allPlanets_produceFiniteInRangeCoordinates() {
        val t = tFor(2026, 1, 1)
        for (body in listOf(
            SolarSystemBody.MERCURY, SolarSystemBody.VENUS, SolarSystemBody.MARS,
            SolarSystemBody.JUPITER, SolarSystemBody.SATURN, SolarSystemBody.URANUS, SolarSystemBody.NEPTUNE,
        )) {
            val eq = PlanetEphemeris.geocentricEquatorialOfDate(body, t)
            assertTrue(eq.rightAscension.degrees in 0.0..360.0, "$body RA out of range: ${eq.rightAscension.degrees}")
            assertTrue(eq.declination.degrees in -90.0..90.0, "$body Dec out of range: ${eq.declination.degrees}")
        }
    }

    private fun sampleDatesOverTwoYears(): List<Triple<Int, Int, Int>> =
        (0 until 24).map { monthsFromNow ->
            val year = 2024 + monthsFromNow / 12
            val month = monthsFromNow % 12 + 1
            Triple(year, month, 15)
        }

    private fun angularSeparationDegrees(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val ra1r = ra1 * kotlin.math.PI / 180.0
        val dec1r = dec1 * kotlin.math.PI / 180.0
        val ra2r = ra2 * kotlin.math.PI / 180.0
        val dec2r = dec2 * kotlin.math.PI / 180.0
        val cosSep = kotlin.math.sin(dec1r) * kotlin.math.sin(dec2r) +
            kotlin.math.cos(dec1r) * kotlin.math.cos(dec2r) * kotlin.math.cos(ra1r - ra2r)
        return kotlin.math.acos(cosSep.coerceIn(-1.0, 1.0)) * 180.0 / kotlin.math.PI
    }
}
