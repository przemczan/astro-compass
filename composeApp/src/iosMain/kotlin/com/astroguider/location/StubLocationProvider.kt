package com.astroguider.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** No CoreLocation binding yet -- see composeApp/src/iosMain/README.md. Never emits. */
class StubLocationProvider : LocationProvider {
    override val location: StateFlow<ObserverLocation?> = MutableStateFlow(null)
    override fun start() = Unit
    override fun stop() = Unit
}
