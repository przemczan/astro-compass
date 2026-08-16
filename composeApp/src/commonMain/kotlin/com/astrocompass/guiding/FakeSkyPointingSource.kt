package com.astrocompass.guiding

import com.astrocompass.astro.Vector3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Test double: push readiness/direction in directly instead of deriving them from real sensors
 *  or a telescope connection -- matches [com.astrocompass.sensors.FakeOrientationSensor]'s
 *  push-based shape. */
class FakeSkyPointingSource(
    override val origin: StateFlow<PointingOrigin> = MutableStateFlow(PointingOrigin.PHONE_SENSORS),
) : SkyPointingSource {
    private val _currentSkyDirection = MutableStateFlow<Vector3?>(null)
    override val currentSkyDirection: StateFlow<Vector3?> = _currentSkyDirection

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady

    fun setReady(ready: Boolean) {
        _isReady.value = ready
    }

    fun setDirection(direction: Vector3?) {
        _currentSkyDirection.value = direction
    }
}
