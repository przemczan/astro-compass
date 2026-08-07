package com.astroguider.location

import kotlinx.coroutines.flow.StateFlow

/** Same pattern as [com.astroguider.sensors.OrientationSensor]: a plain interface, not
 *  `expect`/`actual`, so the Android implementation can take a `Context` constructor parameter. */
interface LocationProvider {
    val location: StateFlow<ObserverLocation?>
    fun start()
    fun stop()
}
