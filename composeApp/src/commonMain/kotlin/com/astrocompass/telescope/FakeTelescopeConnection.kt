package com.astrocompass.telescope

import com.astrocompass.astro.coords.EquatorialCoordinates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Test double: push connection state/reported position in directly instead of driving a real
 *  transport -- matches [com.astrocompass.sensors.FakeOrientationSensor]'s push-based shape. */
class FakeTelescopeConnection : TelescopeConnection {
    private val _state = MutableStateFlow<TelescopeConnectionState>(TelescopeConnectionState.Disconnected)
    override val state: StateFlow<TelescopeConnectionState> = _state

    private val _reportedPosition = MutableStateFlow<TelescopeReport?>(null)
    override val reportedPosition: StateFlow<TelescopeReport?> = _reportedPosition

    var slewOutcome: SlewOutcome = SlewOutcome.Started
    var lastSlewTarget: EquatorialCoordinates? = null
        private set
    var abortCalled: Boolean = false
        private set

    fun setState(state: TelescopeConnectionState) {
        _state.value = state
    }

    fun setReportedPosition(report: TelescopeReport?) {
        _reportedPosition.value = report
    }

    override suspend fun connect(endpoint: TelescopeEndpoint) {
        _state.value = TelescopeConnectionState.Connected(endpoint)
    }

    override suspend fun disconnect() {
        _state.value = TelescopeConnectionState.Disconnected
        _reportedPosition.value = null
    }

    override suspend fun slewTo(target: EquatorialCoordinates): SlewOutcome {
        lastSlewTarget = target
        return slewOutcome
    }

    override suspend fun abortSlew() {
        abortCalled = true
    }
}
