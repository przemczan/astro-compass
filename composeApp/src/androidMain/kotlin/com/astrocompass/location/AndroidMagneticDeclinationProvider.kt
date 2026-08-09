package com.astrocompass.location

import android.hardware.GeomagneticField
import com.astrocompass.astro.Angle

/** Backed by Android's bundled World Magnetic Model, whose `declination` is already
 *  east-positive -- the same sign convention [MagneticDeclinationProvider] specifies. */
class AndroidMagneticDeclinationProvider : MagneticDeclinationProvider {
    override fun declinationAt(location: ObserverLocation, atEpochMillis: Long): Angle =
        Angle.ofDegrees(
            GeomagneticField(
                location.latitude.degrees.toFloat(),
                location.longitude.degrees.toFloat(),
                location.elevationMeters.toFloat(),
                atEpochMillis,
            ).declination.toDouble(),
        )
}
