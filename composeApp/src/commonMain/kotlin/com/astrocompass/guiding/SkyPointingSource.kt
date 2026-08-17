package com.astrocompass.guiding

import com.astrocompass.astro.Vector3
import kotlinx.coroutines.flow.StateFlow

enum class PointingOrigin { PHONE_SENSORS, TELESCOPE }

/**
 * Common shape [PointingService] (phone IMU + [AbsoluteReference]) and
 * [com.astrocompass.telescope.TelescopePointingSource] (a connected mount's own reported
 * position) both satisfy -- the Guidance/Map screens and [GuidanceCalculator] consume this,
 * never `PointingService` directly, so either source drives the same UI.
 *
 * A mount's self-report is *not* funneled through [AbsoluteReference]/`PointingService`'s
 * composition (`reference.sensorToSky.rotate(orientation.deviceToWorld.rotate(axis.deviceVector))`):
 * it is already a complete absolute answer with no phone IMU and no
 * [TelescopeAxis]-mounting-offset involved, so routing it through that pipeline would wrongly
 * reintroduce phone drift into a feed that has none. [SelectablePointingSource] picks between the
 * two outright, never blending them -- the same rule [PrioritizedAbsoluteReference] follows for
 * star-alignment vs. compass one layer down, except the choice here is the user's ([GuidingMode])
 * rather than a priority order.
 */
interface SkyPointingSource {
    val currentSkyDirection: StateFlow<Vector3?>
    val isReady: StateFlow<Boolean>
    val origin: StateFlow<PointingOrigin>
}
