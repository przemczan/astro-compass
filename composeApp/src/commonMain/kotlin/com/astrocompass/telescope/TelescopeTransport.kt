package com.astrocompass.telescope

import kotlinx.coroutines.flow.StateFlow

enum class TelescopeTransportState { IDLE, CONNECTING, CONNECTED, DISCONNECTED, FAILED }

/**
 * A raw duplex byte stream to a telescope, with no protocol knowledge of its own -- LX200
 * framing/half-duplex sequencing is [Lx200Session]'s job, so the same interface could carry a
 * future protocol untouched. [TcpTelescopeTransport] and the Android/iOS Bluetooth Classic SPP
 * implementations are the concrete instances; plain classes rather than `expect`/`actual`,
 * matching [com.astrocompass.sensors.OrientationSensor]'s convention.
 */
interface TelescopeTransport {
    val state: StateFlow<TelescopeTransportState>

    /** Suspends until the attempt settles into [TelescopeTransportState.CONNECTED] or
     *  [TelescopeTransportState.FAILED] -- never throws; implementations catch their own I/O
     *  failures and report them through [state] instead, so callers only ever need to check
     *  [state] after this returns, never wrap it in a try/catch. */
    suspend fun connect()
    suspend fun disconnect()
    suspend fun write(bytes: ByteArray)

    /** Suspends until at least one byte is available. May return more or fewer bytes than the
     *  caller's next parse step needs -- [Lx200Session] owns the buffering. */
    suspend fun readAvailable(): ByteArray
}
