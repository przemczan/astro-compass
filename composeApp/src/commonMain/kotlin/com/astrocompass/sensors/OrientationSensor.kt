package com.astrocompass.sensors

import com.astrocompass.astro.Quaternion
import kotlinx.coroutines.flow.StateFlow

/**
 * Live device orientation. Platform-specific implementations are plain classes (not
 * `expect`/`actual`) so the Android one can take a `Context` constructor parameter, matching
 * lightnet-mobile's `ServiceDiscovery` pattern: `MainActivity` constructs the platform instance
 * and injects it into common code.
 */
interface OrientationSensor {
    val capabilities: SensorCapabilities
    val activeSource: SensorSource
    val orientation: StateFlow<DeviceOrientation?>

    /** Device-to-world rotation in the *magnetometer*-referenced world frame (gravity up, +Y
     *  toward magnetic north), regardless of which frame [orientation] reports in. This is the
     *  raw material for [com.astrocompass.guiding.CompassAbsoluteReference]: differencing it
     *  against [orientation] recovers the yaw between the active sensor frame and magnetic
     *  north. Null with no magnetometer. */
    val magneticDeviceToWorld: StateFlow<Quaternion?>

    fun start()
    fun stop()
}
