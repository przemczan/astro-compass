package com.astrocompass.guiding

import com.astrocompass.alignment.CompassAlignment
import com.astrocompass.astro.Angle
import com.astrocompass.astro.time.currentEpochMillis
import com.astrocompass.location.MagneticDeclinationProvider
import com.astrocompass.location.ObserverLocation
import com.astrocompass.sensors.OrientationSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Nominal figure for [AbsoluteReferenceState.uncertaintyDegrees], not a measurement: phone
 *  magnetometers land within roughly this much of true north in clean air, and can be far worse
 *  beside a steel tube or a motorised mount. Consumers should treat compass mode as qualitatively
 *  rough (via [AbsoluteReferenceState.origin]) rather than quoting this number. */
private const val COMPASS_UNCERTAINTY_DEGREES = 10.0

/**
 * The fallback absolute reference: a live magnetometer-derived pointing solution requiring no
 * alignment at all, so a first-run user gets an arrow that is roughly right instead of a
 * "Not aligned" wall. Self-refreshing rather than established once -- there is nothing to drift
 * away from, since it is recomputed from the magnetometer on every reading.
 *
 * Null unless the device has a magnetometer *and* a location is known (declination needs one),
 * which is what keeps this off on iOS and on gyro-less-and-magnetometer-less hardware.
 *
 * Accuracy note: when the active source is not itself magnetometer-referenced, the two attitude
 * streams are sampled at different rates, so fast motion briefly leaks into the recovered yaw.
 * It settles as soon as the telescope is held still -- which is when the user is actually reading
 * the guidance.
 */
class CompassAbsoluteReference(
    scope: CoroutineScope,
    orientationSensor: OrientationSensor,
    location: StateFlow<ObserverLocation?>,
    declinationProvider: MagneticDeclinationProvider,
) : AbsoluteReference {

    // Declination is a function of place and (very slowly) date, so it is derived from location
    // alone rather than recomputed per sensor reading.
    private val declination: StateFlow<Angle?> = location
        .map { resolved -> resolved?.let { declinationProvider.declinationAt(it, currentEpochMillis()) } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override val current: StateFlow<AbsoluteReferenceState?> = combine(
        orientationSensor.orientation,
        orientationSensor.magneticDeviceToWorld,
        declination,
    ) { orientation, magneticDeviceToWorld, declinationValue ->
        if (orientation == null || magneticDeviceToWorld == null || declinationValue == null) {
            null
        } else {
            AbsoluteReferenceState(
                sensorToSky = CompassAlignment.sensorToSky(
                    sensorDeviceToWorld = orientation.deviceToWorld,
                    magneticDeviceToWorld = magneticDeviceToWorld,
                    declination = declinationValue,
                ),
                establishedAtEpochMillis = currentEpochMillis(),
                uncertaintyDegrees = COMPASS_UNCERTAINTY_DEGREES,
                origin = ReferenceOrigin.COMPASS,
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)
}
