package com.astroguider.astro.coords

import com.astroguider.astro.Angle
import com.astroguider.astro.Vector3
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
        val hourAngle = (localSiderealTime - equatorial.rightAscension).normalized()
        return HorizontalCoordinates.fromEnu(hourAngleDecToEnu(hourAngle, equatorial.declination, observerLatitude))
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

    private fun hourAngleDecToEnu(hourAngle: Angle, declination: Angle, latitude: Angle): Vector3 {
        val h = hourAngle.radians
        val dec = declination.radians
        val phi = latitude.radians
        val cosDec = cos(dec)
        val sinDec = sin(dec)
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)
        val cosH = cos(h)
        val sinH = sin(h)
        return Vector3(
            x = -cosDec * sinH,
            y = cosPhi * sinDec - sinPhi * cosDec * cosH,
            z = sinPhi * sinDec + cosPhi * cosDec * cosH,
        )
    }
}
