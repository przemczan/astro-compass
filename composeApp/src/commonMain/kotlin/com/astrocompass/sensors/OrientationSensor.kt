package com.astrocompass.sensors

import com.astrocompass.astro.Angle
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

    /** Rough compass-only azimuth, used solely to suggest which alignment stars are currently
     *  above the horizon before the first sync. Null with no magnetometer, or once real
     *  alignment data is available -- this is a bootstrap, not a pointing source. */
    val compassBootstrapAzimuth: StateFlow<Angle?>

    fun start()
    fun stop()
}
