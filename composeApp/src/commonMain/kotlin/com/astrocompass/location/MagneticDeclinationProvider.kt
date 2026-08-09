package com.astrocompass.location

import com.astrocompass.astro.Angle

/**
 * Magnetic declination from a platform geomagnetic model -- the one piece of the compass
 * fallback that cannot be computed in `commonMain`, since it needs a field model (Android's
 * `GeomagneticField`) rather than a formula.
 *
 * A plain interface, not `expect`/`actual`, matching
 * [OrientationSensor][com.astrocompass.sensors.OrientationSensor] and [LocationProvider]:
 * the platform entry point constructs the implementation and injects it.
 */
fun interface MagneticDeclinationProvider {
    /** East-positive declination at [location]: `trueAzimuth = magneticAzimuth + declination`.
     *  Null when the platform has no geomagnetic model, which disables the compass fallback
     *  entirely rather than silently pointing at magnetic north. */
    fun declinationAt(location: ObserverLocation, atEpochMillis: Long): Angle?
}
