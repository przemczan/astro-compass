package com.astrocompass.astro.coords

import com.astrocompass.astro.Angle
import com.astrocompass.astro.Matrix3
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Precesses J2000.0 equatorial coordinates to the coordinates of date (IAU 1976 precession
 * angles, rigorous rotation formula). Precession is ~50.29"/year (the well-known precession
 * rate); by the mid-2020s that has accumulated to roughly 0.36°, comparable to this app's
 * overall 0.5-2° pointing budget, so — unlike nutation and aberration — it is not skipped.
 */
object Precession {

    fun j2000ToDate(equatorial: EquatorialCoordinates, julianCenturiesJ2000: Double): EquatorialCoordinates {
        val t = julianCenturiesJ2000
        val arcsecToDeg = 1.0 / 3600.0

        val zeta = (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) * arcsecToDeg
        val z = (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) * arcsecToDeg
        val theta = (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) * arcsecToDeg

        val ra0 = equatorial.rightAscension.radians
        val dec0 = equatorial.declination.radians
        val zetaRad = Angle.ofDegrees(zeta).radians
        val zRad = Angle.ofDegrees(z).radians
        val thetaRad = Angle.ofDegrees(theta).radians

        val raPlusZeta = ra0 + zetaRad
        val a = cos(dec0) * sin(raPlusZeta)
        val b = cos(thetaRad) * cos(dec0) * cos(raPlusZeta) - sin(thetaRad) * sin(dec0)
        val c = sin(thetaRad) * cos(dec0) * cos(raPlusZeta) + cos(thetaRad) * sin(dec0)

        val ra = Angle.ofRadians(atan2(a, b)) + Angle.ofRadians(zRad)
        val dec = Angle.ofRadians(asin(c.coerceIn(-1.0, 1.0)))
        return EquatorialCoordinates(ra.normalized(), dec)
    }

    /** The same transform as [j2000ToDate], as a single rotation matrix -- built once per tick and
     *  applied to many cached direction vectors (see the sky map's scene builder) rather than
     *  re-deriving the per-point trig for every object. Columns are the images of the equatorial
     *  Cartesian basis vectors under [j2000ToDate]; precession is linear, so composing the matrix
     *  this way is exact and reuses the already-tested per-point formula instead of re-deriving it. */
    fun rotationJ2000ToDate(julianCenturiesJ2000: Double): Matrix3 {
        val ex = j2000ToDate(EquatorialCoordinates(Angle.ZERO, Angle.ZERO), julianCenturiesJ2000).toUnitVector()
        val ey = j2000ToDate(EquatorialCoordinates(Angle.ofDegrees(90.0), Angle.ZERO), julianCenturiesJ2000).toUnitVector()
        val ez = j2000ToDate(EquatorialCoordinates(Angle.ZERO, Angle.ofDegrees(90.0)), julianCenturiesJ2000).toUnitVector()
        return Matrix3(
            ex.x, ey.x, ez.x,
            ex.y, ey.y, ez.y,
            ex.z, ey.z, ez.z,
        )
    }
}
