package com.astroguider.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Every altitude/azimuth in the app depends on having a location, so this is the single place
 * that decides which one: a manually-entered location always wins over GPS when both are set,
 * since a user who bothered to type one in usually did so because GPS was wrong, slow, or
 * unavailable indoors.
 */
class LocationResolver(
    scope: CoroutineScope,
    locationProvider: LocationProvider,
    manualLocation: StateFlow<ObserverLocation?>,
) {
    val resolved: StateFlow<ObserverLocation?> =
        combine(manualLocation, locationProvider.location) { manual, gps -> manual ?: gps }
            .stateIn(scope, SharingStarted.Eagerly, manualLocation.value ?: locationProvider.location.value)
}
