package com.astroguider.astro.ephemeris

import com.astroguider.astro.Angle
import com.astroguider.astro.coords.Ecliptic
import com.astroguider.astro.coords.EclipticCoordinates
import com.astroguider.astro.coords.EquatorialCoordinates
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lunar position, deliberately truncated from Meeus ch. 47's ~60-term ELP2000-82 series down to
 * its handful of largest-amplitude terms. Full ch. 47 reaches arcsecond precision; this app's
 * budget only needs the Moon placed to a few tenths of a degree, so the truncation trades away
 * precision this app cannot use in exchange for a small, low-risk set of well-known coefficients
 * (accuracy here is estimated at roughly 0.1-0.3°, not claimed to match the full series).
 *
 * Geocentric position is referred to the mean equinox of date (same convention as [SunEphemeris]).
 * Unlike the Sun, the Moon is close enough that topocentric parallax (up to ~1°) matters, hence
 * [distanceKm] / [horizontalParallax] alongside the direction.
 */
object MoonEphemeris {

    fun geocentricEquatorial(julianCenturiesJ2000: Double): EquatorialCoordinates {
        val t = julianCenturiesJ2000
        val meanLongitude = Angle.ofDegrees(218.3164477 + 481267.88123421 * t).normalized()
        val d = Angle.ofDegrees(297.8501921 + 445267.1114034 * t).radians
        val m = Angle.ofDegrees(357.5291092 + 35999.0502909 * t).radians
        val mPrime = Angle.ofDegrees(134.9633964 + 477198.8675055 * t).radians
        val f = Angle.ofDegrees(93.2720950 + 483202.0175233 * t).radians

        val longitudeCorrection = Angle.ofDegrees(
            6.289 * sin(mPrime) -
                1.274 * sin(2 * d - mPrime) +
                0.658 * sin(2 * d) -
                0.186 * sin(m) -
                0.059 * sin(2 * mPrime - 2 * d)
        )
        val latitude = Angle.ofDegrees(
            5.128 * sin(f) +
                0.281 * sin(mPrime + f) -
                0.278 * sin(f - mPrime)
        )

        val longitude = meanLongitude + longitudeCorrection
        val obliquity = Ecliptic.meanObliquity(t)
        return Ecliptic.toEquatorial(EclipticCoordinates(longitude, latitude), obliquity)
    }

    fun distanceKm(julianCenturiesJ2000: Double): Double {
        val t = julianCenturiesJ2000
        val d = Angle.ofDegrees(297.8501921 + 445267.1114034 * t).radians
        val m = Angle.ofDegrees(357.5291092 + 35999.0502909 * t).radians
        val mPrime = Angle.ofDegrees(134.9633964 + 477198.8675055 * t).radians

        return 385000.56 -
            20905.355 * cos(mPrime) -
            3699.111 * cos(2 * d - mPrime) -
            2955.968 * cos(2 * d)
    }

    /** Earth equatorial radius / distance — used for the parallax-in-altitude correction. */
    fun horizontalParallax(julianCenturiesJ2000: Double): Angle {
        val earthRadiusKm = 6378.14
        return Angle.ofRadians(asin(earthRadiusKm / distanceKm(julianCenturiesJ2000)))
    }
}
