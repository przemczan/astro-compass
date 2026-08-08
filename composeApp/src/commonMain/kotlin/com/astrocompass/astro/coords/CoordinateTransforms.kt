package com.astrocompass.astro.coords

import com.astrocompass.astro.Angle
import com.astrocompass.astro.Matrix3
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Equatorial <-> horizontal <-> ENU-vector conversions.
 *
 * The rotation is derived directly from three checkable reference directions rather than quoted
 * from a specific text, since azimuth-from-north sign conventions vary by source: the north
 * celestial pole (declination +90°) sits at azimuth 0°/altitude = latitude; the celestial-equator
 * point at hour angle +90° sits due west on the horizon; the celestial-equator point at hour
 * angle 0° transits the meridian at azimuth 180°/altitude (90° - latitude) for latitude > 0.
 * [CoordinateTransformsTest] checks all three directly.
 */
object CoordinateTransforms {

    fun equatorialToHorizontal(
        equatorial: EquatorialCoordinates,
        localSiderealTime: Angle,
        observerLatitude: Angle,
    ): HorizontalCoordinates {
        val matrix = equatorialToEnuMatrix(localSiderealTime, observerLatitude)
        return HorizontalCoordinates.fromEnu(matrix * equatorial.toUnitVector())
    }

    /** The rotation from the sky-fixed equatorial Cartesian frame ([EquatorialCoordinates.toUnitVector])
     *  to the observer's ENU frame at this moment -- the bulk-projection counterpart of
     *  [equatorialToHorizontal], built once per tick and applied to many cached direction vectors
     *  (see the sky map's scene builder) instead of repeating the per-point trig for every object. */
    fun equatorialToEnuMatrix(localSiderealTime: Angle, observerLatitude: Angle): Matrix3 {
        val lst = localSiderealTime.radians
        val phi = observerLatitude.radians
        val sinLst = sin(lst)
        val cosLst = cos(lst)
        val sinPhi = sin(phi)
        val cosPhi = cos(phi)
        return Matrix3(
            -sinLst, cosLst, 0.0,
            -sinPhi * cosLst, -sinPhi * sinLst, cosPhi,
            cosPhi * cosLst, cosPhi * sinLst, sinPhi,
        )
    }

    fun horizontalToEquatorial(
        horizontal: HorizontalCoordinates,
        localSiderealTime: Angle,
        observerLatitude: Angle,
    ): EquatorialCoordinates {
        val v = horizontal.toEnu()
        val phi = observerLatitude.radians
        val hourAngle = Angle.ofRadians(atan2(-v.x, -v.y * sin(phi) + v.z * cos(phi)))
        val declination = Angle.ofRadians(asin((v.y * cos(phi) + v.z * sin(phi)).coerceIn(-1.0, 1.0)))
        val rightAscension = (localSiderealTime - hourAngle).normalized()
        return EquatorialCoordinates(rightAscension, declination)
    }
}
