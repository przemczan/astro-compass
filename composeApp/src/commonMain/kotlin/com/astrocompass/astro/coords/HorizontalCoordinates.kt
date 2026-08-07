package com.astrocompass.astro.coords

import com.astrocompass.astro.Angle
import com.astrocompass.astro.Vector3
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Azimuth (from North, clockwise through East) and altitude, as seen by the observer right now. */
data class HorizontalCoordinates(val azimuth: Angle, val altitude: Angle) {

    /** Unit vector in the ENU frame: x = East, y = North, z = Up. */
    fun toEnu(): Vector3 {
        val cosAlt = cos(altitude.radians)
        return Vector3(
            x = cosAlt * sin(azimuth.radians),
            y = cosAlt * cos(azimuth.radians),
            z = sin(altitude.radians),
        )
    }

    companion object {
        fun fromEnu(v: Vector3): HorizontalCoordinates {
            val unit = v.normalized()
            return HorizontalCoordinates(
                azimuth = Angle.ofRadians(atan2(unit.x, unit.y)).normalized(),
                altitude = Angle.ofRadians(asin(unit.z.coerceIn(-1.0, 1.0))),
            )
        }
    }
}
