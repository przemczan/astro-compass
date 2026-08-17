package com.astrocompass.telescope

import com.astrocompass.astro.coords.EquatorialCoordinates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Test double: push connection state/reported position in directly instead of driving a real
 *  transport -- matches [com.astrocompass.sensors.FakeOrientationSensor]'s push-based shape.
 *  [connect] does not simulate [Lx200TelescopeConnection]'s automatic mount-sync sequence -- push
 *  results via [setMountSyncResults] directly if a test needs them. */
class FakeTelescopeConnection : TelescopeConnection {
    private val _state = MutableStateFlow<TelescopeConnectionState>(TelescopeConnectionState.Disconnected)
    override val state: StateFlow<TelescopeConnectionState> = _state

    private val _reportedPosition = MutableStateFlow<TelescopeReport?>(null)
    override val reportedPosition: StateFlow<TelescopeReport?> = _reportedPosition

    private val _mountSyncResults = MutableStateFlow<List<MountSyncStepResult>>(emptyList())
    override val mountSyncResults: StateFlow<List<MountSyncStepResult>> = _mountSyncResults

    var slewOutcome: SlewOutcome = SlewOutcome.Started
    var lastSlewTarget: EquatorialCoordinates? = null
        private set
    var abortCalled: Boolean = false
        private set

    var trackingAccepted: Boolean = true
    var reportedTrackingEnabled: Boolean? = true
    var lastSlewRatePreset: SlewRatePreset? = null
        private set
    var lastTrackingRequest: Boolean? = null
        private set

    fun setState(state: TelescopeConnectionState) {
        _state.value = state
    }

    fun setReportedPosition(report: TelescopeReport?) {
        _reportedPosition.value = report
    }

    fun setMountSyncResults(results: List<MountSyncStepResult>) {
        _mountSyncResults.value = results
    }

    override suspend fun connect(endpoint: TelescopeEndpoint) {
        _state.value = TelescopeConnectionState.Connected(endpoint)
    }

    override suspend fun disconnect() {
        _state.value = TelescopeConnectionState.Disconnected
        _reportedPosition.value = null
        _mountSyncResults.value = emptyList()
    }

    override suspend fun slewTo(target: EquatorialCoordinates): SlewOutcome {
        lastSlewTarget = target
        return slewOutcome
    }

    override suspend fun abortSlew() {
        abortCalled = true
    }

    override suspend fun setSlewRatePreset(preset: SlewRatePreset) {
        lastSlewRatePreset = preset
    }

    override suspend fun setTracking(enabled: Boolean): Boolean {
        lastTrackingRequest = enabled
        if (trackingAccepted) reportedTrackingEnabled = enabled
        return trackingAccepted
    }

    override suspend fun readTrackingEnabled(): Boolean? = reportedTrackingEnabled
}
