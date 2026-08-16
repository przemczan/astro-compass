package com.astrocompass.telescope

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** runBlocking, not runTest -- these exercise a real TCP socket on real threads, which runTest's
 *  virtual-time scheduler doesn't mix safely with (see Lx200SessionTest's notes on the same
 *  issue with withTimeout). Supersedes the earlier throwaway ktor-network spike. */
class TcpTelescopeTransportTest {

    @Test
    fun connectsWritesAndReadsOverARealSocket() = runBlocking {
        val port = 47_815
        val selectorManager = SelectorManager(Dispatchers.IO)
        val serverSocket = aSocket(selectorManager).tcp().bind("127.0.0.1", port)
        val serverJob = launch(Dispatchers.IO) {
            val connection = serverSocket.accept()
            val readChannel = connection.openReadChannel()
            val writeChannel = connection.openWriteChannel(autoFlush = true)
            val received = ByteArray(4)
            readChannel.readAvailable(received)
            assertEquals(":GR#", received.decodeToString())
            writeChannel.writeByteArray("18:36:56#".encodeToByteArray())
            connection.close()
        }

        val transport = TcpTelescopeTransport("127.0.0.1", port)
        transport.connect()
        assertEquals(TelescopeTransportState.CONNECTED, transport.state.value)

        transport.write(":GR#".encodeToByteArray())
        val reply = transport.readAvailable()
        assertEquals("18:36:56#", reply.decodeToString())

        transport.disconnect()
        assertEquals(TelescopeTransportState.DISCONNECTED, transport.state.value)

        serverJob.join()
        serverSocket.close()
        selectorManager.close()
    }

    @Test
    fun connectFailureSetsFailedStateRatherThanThrowing() = runBlocking {
        // Nothing listening on this port -- connect() must fail cleanly (see
        // TelescopeTransport.connect's contract), not throw.
        val transport = TcpTelescopeTransport("127.0.0.1", 47_816)

        transport.connect()

        assertEquals(TelescopeTransportState.FAILED, transport.state.value)
    }
}
