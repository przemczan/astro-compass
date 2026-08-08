package com.astrocompass.astro.coords

import com.astrocompass.astro.Angle
import com.astrocompass.astro.Vector3
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Right ascension / declination, referred to a specific equinox (usually J2000 or date). */
data class EquatorialCoordinates(val rightAscension: Angle, val declination: Angle) {

    /** Unit vector in the equatorial Cartesian frame: x toward (RA=0, Dec=0), z toward the north
     *  celestial pole. Distinct from [HorizontalCoordinates.toEnu]'s ENU frame -- this one is
     *  sky-fixed, not observer/time-dependent, which is what plate-solving matches stars in. */
    fun toUnitVector(): Vector3 {
        val cosDec = cos(declination.radians)
        return Vector3(
            x = cosDec * cos(rightAscension.radians),
            y = cosDec * sin(rightAscension.radians),
            z = sin(declination.radians),
        )
    }

    companion object {
        fun fromUnitVector(v: Vector3): EquatorialCoordinates {
            val unit = v.normalized()
            return EquatorialCoordinates(
                rightAscension = Angle.ofRadians(atan2(unit.y, unit.x)).normalized(),
                declination = Angle.ofRadians(asin(unit.z.coerceIn(-1.0, 1.0))),
            )
        }
    }
}
