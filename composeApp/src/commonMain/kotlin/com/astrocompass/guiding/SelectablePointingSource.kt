package com.astrocompass.guiding

import com.astrocompass.astro.Vector3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Routes every [SkyPointingSource] property to whichever source [mode] names -- including
 * [origin], so a screen branching on `origin` (the Guidance toolbar does) automatically shows the
 * selected mode's actions with no second, separately-derived notion of "the current mode" that
 * could disagree with this one.
 *
 * Deliberately not a readiness-based fallback: [GuidingMode.TELESCOPE] with a mount that hasn't
 * reported a position yet must stay unready and say so, not quietly serve phone-sensor pointing
 * under a Telescope label. Falling back to [GuidingMode.PHONE] when there is no mount at all is
 * [com.astrocompass.AppContainer.guidingMode]'s job, upstream of this.
 */
class SelectablePointingSource(
    scope: CoroutineScope,
    mode: StateFlow<GuidingMode>,
    telescope: SkyPointingSource,
    phone: SkyPointingSource,
) : SkyPointingSource {
    override val currentSkyDirection: StateFlow<Vector3?> =
        selecting(scope, mode, telescope.currentSkyDirection, phone.currentSkyDirection)

    override val isReady: StateFlow<Boolean> =
        selecting(scope, mode, telescope.isReady, phone.isReady)

    override val origin: StateFlow<PointingOrigin> =
        selecting(scope, mode, telescope.origin, phone.origin)
}

private fun <T> selecting(
    scope: CoroutineScope,
    mode: StateFlow<GuidingMode>,
    telescope: StateFlow<T>,
    phone: StateFlow<T>,
): StateFlow<T> =
    combine(mode, telescope, phone) { selected, fromTelescope, fromPhone ->
        pick(selected, fromTelescope, fromPhone)
    }.stateIn(scope, SharingStarted.Eagerly, pick(mode.value, telescope.value, phone.value))

private fun <T> pick(mode: GuidingMode, telescope: T, phone: T): T =
    if (mode == GuidingMode.TELESCOPE) telescope else phone
