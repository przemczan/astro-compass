package com.astroguider.astro.ephemeris

import com.astroguider.astro.Angle
import com.astroguider.astro.coords.Ecliptic
import com.astroguider.astro.coords.EclipticCoordinates
import com.astroguider.astro.coords.EquatorialCoordinates
import kotlin.math.sin

/**
 * Low-precision solar position (Meeus, *Astronomical Algorithms* ch. 25 "low accuracy" method),
 * good to about 0.01° — more than sufficient against this app's 0.5°+ pointing budget, and far
 * cheaper than a VSOP87 series. Returns geometric position referred to the mean equinox *of
 * date*, so unlike stars and planets (J2000-referenced), no separate precession step is needed.
 */
object SunEphemeris {

    fun geocentricEquatorial(julianCenturiesJ2000: Double): EquatorialCoordinates {
        val t = julianCenturiesJ2000

        val meanLongitude = Angle.ofDegrees(280.46646 + 36000.76983 * t + 0.0003032 * t * t).normalized()
        val meanAnomaly = Angle.ofDegrees(357.52911 + 35999.05029 * t - 0.0001537 * t * t).normalized()
        val m = meanAnomaly.radians

        val equationOfCenter = Angle.ofDegrees(
            (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(m) +
                (0.019993 - 0.000101 * t) * sin(2 * m) +
                0.000289 * sin(3 * m)
        )

        val trueLongitude = meanLongitude + equationOfCenter
        val omega = Angle.ofDegrees(125.04 - 1934.136 * t)
        val apparentLongitude = trueLongitude - Angle.ofDegrees(0.00569) - Angle.ofDegrees(0.00478 * sin(omega.radians))

        val obliquity = Ecliptic.meanObliquity(t) + Angle.ofDegrees(0.00256 * kotlin.math.cos(omega.radians))

        return Ecliptic.toEquatorial(EclipticCoordinates(apparentLongitude), obliquity)
    }
}
