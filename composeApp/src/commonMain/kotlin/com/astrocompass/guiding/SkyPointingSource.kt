package com.astrocompass.guiding

import com.astrocompass.astro.Vector3
import kotlinx.coroutines.flow.StateFlow

/**
 * A live sky direction plus readiness -- the shape [PointingService] (phone IMU +
 * [AbsoluteReference]) and [com.astrocompass.telescope.TelescopePointingSource] (a connected
 * mount's own reported position) both satisfy. `GuidanceScreen`/`MapScreen` consume the phone's
 * [PointingService] through this interface for guidance itself; a connected mount's own position
 * is shown separately as its own map marker (see
 * [com.astrocompass.AppContainer.telescopeSkyDirection]), never through this type.
 */
interface SkyPointingSource {
    val currentSkyDirection: StateFlow<Vector3?>
    val isReady: StateFlow<Boolean>
}
