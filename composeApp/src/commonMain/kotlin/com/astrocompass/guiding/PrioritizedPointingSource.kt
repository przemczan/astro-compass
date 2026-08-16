package com.astrocompass.guiding

import com.astrocompass.astro.Vector3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Telescope reporting wins outright over phone sensors whenever it's ready -- same never-blend
 * philosophy as [PrioritizedAbsoluteReference], one layer up the stack: a connected,
 * actively-reporting mount is unambiguously ground truth, so there's nothing for a blend to add.
 */
class PrioritizedPointingSource(
    scope: CoroutineScope,
    preferred: SkyPointingSource,
    fallback: SkyPointingSource,
) : SkyPointingSource {
    override val isReady: StateFlow<Boolean> =
        combine(preferred.isReady, fallback.isReady) { preferredReady, fallbackReady -> preferredReady || fallbackReady }
            .stateIn(scope, SharingStarted.Eagerly, preferred.isReady.value || fallback.isReady.value)

    override val origin: StateFlow<PointingOrigin> =
        combine(preferred.isReady, preferred.origin, fallback.origin) { ready, preferredOrigin, fallbackOrigin ->
            if (ready) preferredOrigin else fallbackOrigin
        }.stateIn(scope, SharingStarted.Eagerly, if (preferred.isReady.value) preferred.origin.value else fallback.origin.value)

    override val currentSkyDirection: StateFlow<Vector3?> =
        combine(preferred.isReady, preferred.currentSkyDirection, fallback.currentSkyDirection) { ready, preferredDirection, fallbackDirection ->
            if (ready) preferredDirection else fallbackDirection
        }.stateIn(
            scope, SharingStarted.Eagerly,
            if (preferred.isReady.value) preferred.currentSkyDirection.value else fallback.currentSkyDirection.value,
        )
}
