package com.astrocompass.location

import kotlinx.coroutines.flow.StateFlow

/** Same pattern as [com.astrocompass.sensors.OrientationSensor]: a plain interface, not
 *  `expect`/`actual`, so the Android implementation can take a `Context` constructor parameter. */
interface LocationProvider {
    val location: StateFlow<ObserverLocation?>
    fun start()
    fun stop()
}
