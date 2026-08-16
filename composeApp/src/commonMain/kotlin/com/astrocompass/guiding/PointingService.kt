package com.astrocompass.guiding

import com.astrocompass.astro.Vector3
import com.astrocompass.sensors.OrientationSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Fuses the continuous relative sensor stream with the current [AbsoluteReference] to answer
 * "which way is the telescope pointing right now, in true sky (ENU) coordinates". Null until
 * both a sensor reading and an alignment exist -- there is no meaningful pointing direction
 * before the first sync, and the Guidance screen relies on that to show "not aligned" rather
 * than a meaningless arrow.
 */
class PointingService(
    scope: CoroutineScope,
    orientationSensor: OrientationSensor,
    absoluteReference: AbsoluteReference,
    telescopeAxis: StateFlow<TelescopeAxis>,
) : SkyPointingSource {
    override val currentSkyDirection: StateFlow<Vector3?> =
        combine(orientationSensor.orientation, absoluteReference.current, telescopeAxis) { orientation, reference, axis ->
            if (orientation == null || reference == null) {
                null
            } else {
                reference.sensorToSky.rotate(orientation.deviceToWorld.rotate(axis.deviceVector))
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    val isAligned: StateFlow<Boolean> =
        absoluteReference.current
            .map { it != null }
            .stateIn(scope, SharingStarted.Eagerly, absoluteReference.current.value != null)

    override val isReady: StateFlow<Boolean> get() = isAligned
    override val origin: StateFlow<PointingOrigin> = MutableStateFlow(PointingOrigin.PHONE_SENSORS)
}
