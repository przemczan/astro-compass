package com.astrocompass.telescope

import com.astrocompass.astro.Vector3
import com.astrocompass.astro.coords.CoordinateTransforms
import com.astrocompass.astro.time.AstroTime
import com.astrocompass.astro.time.currentEpochMillis
import com.astrocompass.guiding.PointingOrigin
import com.astrocompass.guiding.SkyPointingSource
import com.astrocompass.location.ObserverLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

private const val DEFAULT_STALE_THRESHOLD_MILLIS = 5_000L
private const val TICK_INTERVAL_MILLIS = 1_000L

/**
 * Wraps [TelescopeConnection.reportedPosition] as a [SkyPointingSource]: the mount's own JNow
 * RA/Dec converted straight to ENU via local sidereal time and observer latitude -- no phone
 * IMU, no [com.astrocompass.guiding.AbsoluteReference], no
 * [com.astrocompass.guiding.TelescopeAxis] (see [SkyPointingSource]'s doc for why). Recomputes on
 * every clock tick as well as on every new report, since ENU depends on LST even between polls.
 *
 * [isReady] is false whenever the connection isn't [TelescopeConnectionState.Connected] or the
 * last report is older than [staleThresholdMillis] (a few poll intervals) -- this is what lets
 * [com.astrocompass.guiding.PrioritizedPointingSource] stay a dumb readiness check rather than
 * needing its own staleness logic.
 */
class TelescopePointingSource(
    scope: CoroutineScope,
    connection: TelescopeConnection,
    location: StateFlow<ObserverLocation?>,
    staleThresholdMillis: Long = DEFAULT_STALE_THRESHOLD_MILLIS,
) : SkyPointingSource {

    private val now: StateFlow<Long> =
        tickerFlow(TICK_INTERVAL_MILLIS).stateIn(scope, SharingStarted.Eagerly, currentEpochMillis())

    override val currentSkyDirection: StateFlow<Vector3?> =
        combine(connection.reportedPosition, location, now) { report, observerLocation, nowMillis ->
            if (report == null || observerLocation == null) return@combine null
            val lst = AstroTime.localSiderealTime(AstroTime.julianDay(nowMillis), observerLocation.longitude)
            CoordinateTransforms.equatorialToHorizontal(report.equatorialJNow, lst, observerLocation.latitude).toEnu()
        }.stateIn(scope, SharingStarted.Eagerly, null)

    override val isReady: StateFlow<Boolean> =
        combine(connection.state, connection.reportedPosition, now) { connectionState, report, nowMillis ->
            connectionState is TelescopeConnectionState.Connected &&
                report != null &&
                (nowMillis - report.epochMillis) <= staleThresholdMillis
        }.stateIn(scope, SharingStarted.Eagerly, false)

    override val origin: StateFlow<PointingOrigin> = MutableStateFlow(PointingOrigin.TELESCOPE)
}

private fun tickerFlow(intervalMillis: Long): Flow<Long> = flow {
    while (true) {
        emit(currentEpochMillis())
        delay(intervalMillis)
    }
}
