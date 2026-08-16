@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.astrocompass.telescope

import com.astrocompass.astro.Angle
import com.astrocompass.astro.coords.EquatorialCoordinates
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val TCP_ENDPOINT = TelescopeEndpoint(TelescopeTransportKind.TCP, "Test mount", host = "127.0.0.1", port = 4030)

class Lx200TelescopeConnectionTest {

    @Test
    fun connectSucceedsAndReportsConnectedState() = runTest {
        val transport = FakeTelescopeTransport()
        val connection = connectionWith(transport)

        connection.connect(TCP_ENDPOINT)

        assertEquals(TelescopeConnectionState.Connected(TCP_ENDPOINT), connection.state.value)
    }

    @Test
    fun connectFailureReportsFailedState() = runTest {
        val transport = FakeTelescopeTransport().apply { connectShouldFail = true }
        val connection = connectionWith(transport)

        connection.connect(TCP_ENDPOINT)

        assertIs<TelescopeConnectionState.Failed>(connection.state.value)
    }

    @Test
    fun pollsPositionAfterConnecting() = runTest {
        val transport = FakeTelescopeTransport()
        transport.enqueueInbound("18:36:56#")
        transport.enqueueInbound("+38*47:01#")
        val connection = connectionWith(transport)

        connection.connect(TCP_ENDPOINT)
        // Lets the poll loop's first iteration run -- runCurrent(), not advanceUntilIdle(), so
        // the delay() the loop suspends on afterward isn't force-fired (see Lx200SessionTest for
        // why advanceUntilIdle is unsafe around a pending timed suspension).
        runCurrent()

        val report = assertNotNull(connection.reportedPosition.value)
        assertEquals(Angle.ofHms(18, 36, 56.0).degrees, report.equatorialJNow.rightAscension.degrees, 1e-9)
        assertEquals(Angle.ofDms(38, 47, 1.0).degrees, report.equatorialJNow.declination.degrees, 1e-9)
    }

    @Test
    fun disconnectStopsPollingAndClearsState() = runTest {
        val transport = FakeTelescopeTransport()
        transport.enqueueInbound("18:36:56#")
        transport.enqueueInbound("+38*47:01#")
        val connection = connectionWith(transport)
        connection.connect(TCP_ENDPOINT)
        runCurrent()
        assertNotNull(connection.reportedPosition.value)

        connection.disconnect()

        assertEquals(TelescopeConnectionState.Disconnected, connection.state.value)
        assertNull(connection.reportedPosition.value)
        assertEquals(TelescopeTransportState.DISCONNECTED, transport.state.value)
    }

    @Test
    fun reconnectingTearsDownThePreviousTransport() = runTest {
        val firstTransport = FakeTelescopeTransport()
        val secondTransport = FakeTelescopeTransport()
        var callCount = 0
        val connection = Lx200TelescopeConnection(
            scope = backgroundScope,
            tcpTransportFactory = { _, _ -> if (++callCount == 1) firstTransport else secondTransport },
            bluetoothTransportFactory = { error("not used") },
        )

        connection.connect(TCP_ENDPOINT)
        connection.connect(TCP_ENDPOINT.copy(displayName = "Second mount"))

        assertEquals(TelescopeTransportState.DISCONNECTED, firstTransport.state.value)
        assertEquals(TelescopeTransportState.CONNECTED, secondTransport.state.value)
    }

    @Test
    fun slewToWithoutAConnectionReturnsNoConnection() = runTest {
        val connection = connectionWith(FakeTelescopeTransport())

        val outcome = connection.slewTo(EquatorialCoordinates(Angle.ZERO, Angle.ZERO))

        assertEquals(SlewOutcome.NoConnection, outcome)
    }

    @Test
    fun slewToSendsTargetThenSlewCommandOnAcceptedAcks() = runTest {
        val transport = FakeTelescopeTransport()
        transport.enqueueInbound("1#") // :Sr# accepted
        transport.enqueueInbound("1#") // :Sd# accepted
        transport.enqueueInbound("0") // :MS# started -- bare byte, no terminator
        val connection = connectionWith(transport)
        connection.connect(TCP_ENDPOINT)

        val outcome = connection.slewTo(EquatorialCoordinates(Angle.ofHms(18, 36, 56.0), Angle.ofDms(38, 47, 1.0)))

        assertEquals(SlewOutcome.Started, outcome)
        assertEquals(listOf(":Sr 18:36:56#", ":Sd +38*47:01#", ":MS#"), transport.writtenCommands())
    }

    @Test
    fun slewToStopsAtARejectedRightAscension() = runTest {
        val transport = FakeTelescopeTransport()
        transport.enqueueInbound("0#") // :Sr# rejected
        val connection = connectionWith(transport)
        connection.connect(TCP_ENDPOINT)

        val outcome = connection.slewTo(EquatorialCoordinates(Angle.ZERO, Angle.ZERO))

        assertIs<SlewOutcome.Rejected>(outcome)
        assertEquals(listOf(":Sr 00:00:00#"), transport.writtenCommands())
    }

    @Test
    fun slewToSurfacesAMountRejection() = runTest {
        val transport = FakeTelescopeTransport()
        transport.enqueueInbound("1#")
        transport.enqueueInbound("1#")
        transport.enqueueInbound("1Object Below Horizon#")
        val connection = connectionWith(transport)
        connection.connect(TCP_ENDPOINT)

        val outcome = connection.slewTo(EquatorialCoordinates(Angle.ZERO, Angle.ZERO))

        assertEquals(SlewOutcome.Rejected("Object Below Horizon"), outcome)
    }

    @Test
    fun abortSlewWritesTheAbortCommandWhenConnected() = runTest {
        val transport = FakeTelescopeTransport()
        val connection = connectionWith(transport)
        connection.connect(TCP_ENDPOINT)

        connection.abortSlew()

        assertEquals(listOf(":Q#"), transport.writtenCommands())
    }

    @Test
    fun abortSlewIsANoOpWithoutAConnection() = runTest {
        val transport = FakeTelescopeTransport()
        val connection = connectionWith(transport)

        connection.abortSlew()

        assertEquals(emptyList(), transport.writtenCommands())
    }

    private fun kotlinx.coroutines.test.TestScope.connectionWith(transport: FakeTelescopeTransport) =
        Lx200TelescopeConnection(
            scope = backgroundScope,
            tcpTransportFactory = { _, _ -> transport },
            bluetoothTransportFactory = { error("not used in these tests") },
        )
}
