package com.astrocompass.telescope

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Bluetooth Classic SPP has no public API on iOS for arbitrary (non-MFi-certified) accessories --
 * the only path in is Apple's External Accessory framework, which requires the hardware vendor to
 * be MFi-certified and ship an Apple-approved protocol string, something no DSC box or GoTo mount
 * vendor does. This is a hard platform limitation, not a "not implemented yet": [connect] always
 * settles into [TelescopeTransportState.FAILED]. Matches [StubOrientationSensor][
 * com.astrocompass.sensors.StubOrientationSensor]'s convention -- TCP still works identically on
 * iOS via the shared [TcpTelescopeTransport].
 */
class StubBluetoothTelescopeTransport : TelescopeTransport {
    private val _state = MutableStateFlow(TelescopeTransportState.IDLE)
    override val state: StateFlow<TelescopeTransportState> = _state

    override suspend fun connect() {
        _state.value = TelescopeTransportState.FAILED
    }

    override suspend fun disconnect() {
        _state.value = TelescopeTransportState.DISCONNECTED
    }

    override suspend fun write(bytes: ByteArray): Unit = error("Bluetooth Classic SPP is not available on iOS")
    override suspend fun readAvailable(): ByteArray = error("Bluetooth Classic SPP is not available on iOS")
}
