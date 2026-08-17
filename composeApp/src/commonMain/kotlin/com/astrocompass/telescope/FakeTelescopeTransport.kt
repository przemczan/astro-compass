package com.astrocompass.telescope

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Test double: scripted inbound byte chunks queued by the test, every [write] captured for
 *  assertion. Lets [Lx200Codec]/[Lx200Session]/[Lx200TelescopeConnection] be tested without any
 *  real transport, matching [com.astrocompass.sensors.FakeOrientationSensor]'s push-based shape. */
class FakeTelescopeTransport : TelescopeTransport {
    private val _state = MutableStateFlow(TelescopeTransportState.IDLE)
    override val state: StateFlow<TelescopeTransportState> = _state

    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
    private val writes = mutableListOf<String>()

    /** Set before [connect] to simulate a connection failure (device unreachable, refused, ...). */
    var connectShouldFail: Boolean = false

    /** Set before [connect] to simulate an unresponsive device that never completes the connect
     *  attempt -- exercises [Lx200TelescopeConnection]'s connect timeout under virtual time. */
    var connectShouldHang: Boolean = false

    override suspend fun connect() {
        _state.value = TelescopeTransportState.CONNECTING
        if (connectShouldHang) awaitCancellation()
        _state.value = if (connectShouldFail) TelescopeTransportState.FAILED else TelescopeTransportState.CONNECTED
    }

    override suspend fun disconnect() {
        _state.value = TelescopeTransportState.DISCONNECTED
    }

    /** Mirrors [TcpTelescopeTransport]/`AndroidBluetoothTelescopeTransport`'s own
     *  "`error(...)` if the underlying socket field is gone" contract -- without this, a test
     *  simulating a mid-session drop (e.g. via [disconnect]) would let writes/reads silently keep
     *  succeeding afterward, which no real transport does. */
    override suspend fun write(bytes: ByteArray) {
        check(_state.value == TelescopeTransportState.CONNECTED) { "FakeTelescopeTransport.write called while not connected" }
        writes += bytes.decodeToString()
    }

    override suspend fun readAvailable(): ByteArray {
        check(_state.value == TelescopeTransportState.CONNECTED) { "FakeTelescopeTransport.readAvailable called while not connected" }
        return inbound.receive()
    }

    /** Queues bytes for the next [readAvailable] call(s) to return -- split across multiple calls
     *  to exercise a response spread over several reads, or combined in one to exercise several
     *  responses arriving in a single read. */
    fun enqueueInbound(bytes: ByteArray) {
        inbound.trySend(bytes)
    }

    fun enqueueInbound(text: String) = enqueueInbound(text.encodeToByteArray())

    fun writtenCommands(): List<String> = writes.toList()
}
