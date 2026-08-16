package com.astrocompass.telescope

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val READ_CHUNK_SIZE = 256

/**
 * [TelescopeTransport] over a plain TCP socket, via `ktor-network`'s raw socket API -- this is
 * the project's first-ever networking dependency, chosen because its socket support covers both
 * the JVM (Android) and Kotlin/Native (iOS) targets from a single `commonMain` class, unlike
 * Bluetooth Classic SPP which needs a real platform implementation per target.
 */
class TcpTelescopeTransport(private val host: String, private val port: Int) : TelescopeTransport {
    private val _state = MutableStateFlow(TelescopeTransportState.IDLE)
    override val state: StateFlow<TelescopeTransportState> = _state

    private var selectorManager: SelectorManager? = null
    private var socket: Socket? = null
    private var readChannel: ByteReadChannel? = null
    private var writeChannel: ByteWriteChannel? = null

    override suspend fun connect() {
        _state.value = TelescopeTransportState.CONNECTING
        val newSelectorManager = SelectorManager(Dispatchers.IO)
        val connected = runCatching { aSocket(newSelectorManager).tcp().connect(host, port) }.getOrNull()

        if (connected == null) {
            newSelectorManager.close()
            _state.value = TelescopeTransportState.FAILED
            return
        }

        selectorManager = newSelectorManager
        socket = connected
        readChannel = connected.openReadChannel()
        writeChannel = connected.openWriteChannel(autoFlush = true)
        _state.value = TelescopeTransportState.CONNECTED
    }

    override suspend fun disconnect() {
        socket?.close()
        selectorManager?.close()
        socket = null
        selectorManager = null
        readChannel = null
        writeChannel = null
        _state.value = TelescopeTransportState.DISCONNECTED
    }

    override suspend fun write(bytes: ByteArray) {
        val channel = writeChannel ?: error("TcpTelescopeTransport.write called while not connected")
        try {
            channel.writeByteArray(bytes)
        } catch (e: Exception) {
            _state.value = TelescopeTransportState.FAILED
            throw e
        }
    }

    /** Throws (after flipping [state] to [TelescopeTransportState.FAILED]) on either an I/O
     *  error or the peer closing the connection (`readAvailable` returning a negative count) --
     *  never returns an empty array to signal failure, since [Lx200Session]'s read loops would
     *  spin forever calling a non-suspending "nothing happened" result. */
    override suspend fun readAvailable(): ByteArray {
        val channel = readChannel ?: error("TcpTelescopeTransport.readAvailable called while not connected")
        val buffer = ByteArray(READ_CHUNK_SIZE)
        val count = try {
            channel.readAvailable(buffer)
        } catch (e: Exception) {
            _state.value = TelescopeTransportState.FAILED
            throw e
        }
        if (count < 0) {
            _state.value = TelescopeTransportState.FAILED
            error("Telescope connection closed by peer")
        }
        return buffer.copyOf(count)
    }
}
