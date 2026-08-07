package com.astrocompass.astro.coords

import com.astrocompass.astro.Angle
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Ecliptic longitude/latitude, and the ecliptic <-> equatorial conversion shared by the
 *  Sun/Moon/planet ephemerides. */
data class EclipticCoordinates(val longitude: Angle, val latitude: Angle = Angle.ZERO)

object Ecliptic {

    /** Mean obliquity of the ecliptic, IAU 1980 (Meeus eq. 22.3), valid within a few
     *  centuries of J2000 — well beyond this app's needs. */
    fun meanObliquity(julianCenturiesJ2000: Double): Angle {
        val t = julianCenturiesJ2000
        val arcsec = 84381.448 - 46.8150 * t - 0.00059 * t * t + 0.001813 * t * t * t
        return Angle.ofDegrees(arcsec / 3600.0)
    }

    fun toEquatorial(ecliptic: EclipticCoordinates, obliquity: Angle): EquatorialCoordinates {
        val lambda = ecliptic.longitude.radians
        val beta = ecliptic.latitude.radians
        val eps = obliquity.radians

        val ra = atan2(sin(lambda) * cos(eps) - tan(beta) * sin(eps), cos(lambda))
        val dec = asin((sin(beta) * cos(eps) + cos(beta) * sin(lambda) * sin(eps)).coerceIn(-1.0, 1.0))
        return EquatorialCoordinates(Angle.ofRadians(ra).normalized(), Angle.ofRadians(dec))
    }
}
