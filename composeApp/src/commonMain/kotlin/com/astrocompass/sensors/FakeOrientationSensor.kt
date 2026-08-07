package com.astrocompass.sensors

import com.astrocompass.astro.Angle
import com.astrocompass.astro.Quaternion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Test/demo double: push readings in directly instead of listening to real hardware. */
class FakeOrientationSensor(
    override val capabilities: SensorCapabilities = SensorCapabilities(
        hasGyroscope = true, hasMagnetometer = true, hasAccelerometer = true,
    ),
    override val activeSource: SensorSource = SensorSource.GAME_ROTATION_VECTOR,
) : OrientationSensor {

    private val _orientation = MutableStateFlow<DeviceOrientation?>(null)
    override val orientation: StateFlow<DeviceOrientation?> = _orientation

    private val _compassBootstrapAzimuth = MutableStateFlow<Angle?>(null)
    override val compassBootstrapAzimuth: StateFlow<Angle?> = _compassBootstrapAzimuth

    var started: Boolean = false
        private set

    override fun start() { started = true }
    override fun stop() { started = false }

    fun emit(deviceToWorld: Quaternion, timestampMillis: Long) {
        _orientation.value = DeviceOrientation(deviceToWorld, activeSource, timestampMillis)
    }

    fun emitCompassBootstrap(azimuth: Angle?) {
        _compassBootstrapAzimuth.value = azimuth
    }
}
