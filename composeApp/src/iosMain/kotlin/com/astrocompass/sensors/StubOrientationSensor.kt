package com.astrocompass.sensors

import com.astrocompass.astro.Quaternion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** No CoreMotion binding yet -- see composeApp/src/iosMain/README.md for the Android-only
 *  status of this build. Never emits. */
class StubOrientationSensor : OrientationSensor {
    override val capabilities = SensorCapabilities(hasGyroscope = false, hasMagnetometer = false, hasAccelerometer = false)
    override val activeSource: SensorSource = SensorSource.GEOMAGNETIC_ROTATION_VECTOR
    override val orientation: StateFlow<DeviceOrientation?> = MutableStateFlow(null)
    override val magneticDeviceToWorld: StateFlow<Quaternion?> = MutableStateFlow(null)

    override fun start() = Unit
    override fun stop() = Unit
}
