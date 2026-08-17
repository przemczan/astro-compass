@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.astrocompass.telescope

import com.astrocompass.astro.Angle
import com.astrocompass.astro.coords.EquatorialCoordinates
import com.astrocompass.location.ObserverLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TCP_ENDPOINT = TelescopeEndpoint(TelescopeTransportKind.TCP, "Test mount", host = "127.0.0.1", port = 4030)
private val TEST_LOCATION = ObserverLocation(latitude = Angle.ofDegrees(51.5), longitude = Angle.ofDegrees(-0.1))
private const val POLL_INTERVAL_MILLIS = 1_000L

class Lx200TelescopeConnectionTest {

    @Test
    fun connectSucceedsAndReportsConnectedState() = runTest {
        val transport = FakeTelescopeTransport().withMountSyncAcks()
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
        val transport = FakeTelescopeTransport().withMountSyncAcks()
        transport.enqueueInbound("18:36:56#")
        transport.enqueueInbound("+38*47:01#")
        val connection = connectionWith(transport)

        connection.connect(TCP_ENDPOINT)
        assertNull(connection.reportedPosition.value) // pollPosition waits out one interval first
        // advanceTimeBy, not advanceUntilIdle(): the poll loop reschedules itself forever, so
        // advanceUntilIdle() would never consider the scheduler idle (see Lx200SessionTest for the
        // same reasoning around a single pending timeout).
        advanceTimeBy(POLL_INTERVAL_MILLIS)
        runCurrent()

        val report = assertNotNull(connection.reportedPosition.value)
        assertEquals(Angle.ofHms(18, 36, 56.0).degrees, report.equatorialJNow.rightAscension.degrees, 1e-9)
        assertEquals(Angle.ofDms(38, 47, 1.0).degrees, report.equatorialJNow.declination.degrees, 1e-9)
    }

    @Test
    fun disconnectStopsPollingAndClearsState() = runTest {
        val transport = FakeTelescopeTransport().withMountSyncAcks()
        transport.enqueueInbound("18:36:56#")
        transport.enqueueInbound("+38*47:01#")
        val connection = connectionWith(transport)
        connection.connect(TCP_ENDPOINT)
        advanceTimeBy(POLL_INTERVAL_MILLIS)
        runCurrent()
        assertNotNull(connection.reportedPosition.value)

        connection.disconnect()

        assertEquals(TelescopeConnectionState.Disconnected, connection.state.value)
        assertNull(connection.reportedPosition.value)
        assertEquals(TelescopeTransportState.DISCONNECTED, transport.state.value)
    }

    @Test
    fun reconnectingTearsDownThePreviousTransport() = runTest {
        val firstTransport = FakeTelescopeTransport().withMountSyncAcks()
        val secondTransport = FakeTelescopeTransport().withMountSyncAcks()
        var callCount = 0
        val connection = Lx200TelescopeConnection(
            scope = backgroundScope,
            tcpTransportFactory = { _, _ -> if (++callCount == 1) firstTransport else secondTransport },
            bluetoothTransportFactory = { error("not used") },
            location = MutableStateFlow(null),
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
        val transport = FakeTelescopeTransport().withMountSyncAcks()
        transport.enqueueInbound("1") // :Sr# accepted
        transport.enqueueInbound("1") // :Sd# accepted
        transport.enqueueInbound("0") // :MS# started -- bare byte, no terminator
        val connection = connectionWith(transport)
        connection.connect(TCP_ENDPOINT)

        val outcome = connection.slewTo(EquatorialCoordinates(Angle.ofHms(18, 36, 56.0), Angle.ofDms(38, 47, 1.0)))

        assertEquals(SlewOutcome.Started, outcome)
        // Drops the 5 mount-sync commands connect() sent first -- this test is only about slewTo.
        // The poll loop never touches the transport until its own interval elapses (see
        // pollPosition's doc), so this prefix is exact with no risk of an eager poll read landing
        // in between.
        assertEquals(listOf(":Sr 18:36:56#", ":Sd +38*47:01#", ":MS#"), transport.writtenCommands().drop(5))
    }

    @Test
    fun slewToStopsAtARejectedRightAscension() = runTest {
        val transport = FakeTelescopeTransport().withMountSyncAcks()
        transport.enqueueInbound("0") // :Sr# rejected
        val connection = connectionWith(transport)
        connection.connect(TCP_ENDPOINT)

        val outcome = connection.slewTo(EquatorialCoordinates(Angle.ZERO, Angle.ZERO))

        assertIs<SlewOutcome.Rejected>(outcome)
        assertEquals(listOf(":Sr 00:00:00#"), transport.writtenCommands().drop(5))
    }

    @Test
    fun slewToSurfacesAMountRejection() = runTest {
        val transport = FakeTelescopeTransport().withMountSyncAcks()
        transport.enqueueInbound("1")
        transport.enqueueInbound("1")
        transport.enqueueInbound("1Object Below Horizon#")
        val connection = connectionWith(transport)
        connection.connect(TCP_ENDPOINT)

        val outcome = connection.slewTo(EquatorialCoordinates(Angle.ZERO, Angle.ZERO))

        assertEquals(SlewOutcome.Rejected("Object Below Horizon"), outcome)
    }

    @Test
    fun abortSlewWritesTheAbortCommandWhenConnected() = runTest {
        val transport = FakeTelescopeTransport().withMountSyncAcks()
        val connection = connectionWith(transport)
        connection.connect(TCP_ENDPOINT)

        connection.abortSlew()

        assertEquals(listOf(":Q#"), transport.writtenCommands().drop(5))
    }

    @Test
    fun abortSlewIsANoOpWithoutAConnection() = runTest {
        val transport = FakeTelescopeTransport()
        val connection = connectionWith(transport)

        connection.abortSlew()

        assertEquals(emptyList(), transport.writtenCommands())
    }

    @Test
    fun connectRunsMountSyncBeforePollingStartsWhenLocationIsKnown() = runTest {
        val transport = FakeTelescopeTransport()
        transport.enqueueInbound("18:36:56#") // :GR# link probe
        repeat(6) { transport.enqueueInbound("1") } // :SG :SC :SL :St :Sg :hR, all accepted
        transport.enqueueInbound("18:36:56#") // :GR#, again once polling starts
        transport.enqueueInbound("+38*47:01#") // :GD#
        val connection = connectionWith(transport, location = MutableStateFlow(TEST_LOCATION))

        connection.connect(TCP_ENDPOINT)

        // Regression guard for the ordering bug: the poll loop never touches the transport until
        // its own interval elapses (see pollPosition's doc), so by the time connect() itself
        // returns, exactly and only the link probe + 6 sync commands have been sent -- no
        // runCurrent() needed to force this boundary. :SC/:SL encode the real wall clock (see
        // runMountSync), so only their fixed-format prefix is asserted; every other command is
        // fully deterministic.
        val afterConnect = transport.writtenCommands()
        assertEquals(7, afterConnect.size)
        assertEquals(":GR#", afterConnect[0])
        assertEquals(":SG +00#", afterConnect[1])
        assertTrue(afterConnect[2].matches(Regex(":SC \\d{2}/\\d{2}/\\d{2}#")))
        assertTrue(afterConnect[3].matches(Regex(":SL \\d{2}:\\d{2}:\\d{2}#")))
        assertEquals(":St +51*30#", afterConnect[4])
        assertEquals(":Sg +000*06#", afterConnect[5])
        assertEquals(":hR#", afterConnect[6])

        val results = connection.mountSyncResults.value
        assertEquals(MountSyncStepOutcome.Success, results.single { it.step == MountSyncStep.LINK }.outcome)
        assertEquals(MountSyncStepOutcome.Success, results.single { it.step == MountSyncStep.TIME }.outcome)
        assertEquals(MountSyncStepOutcome.Success, results.single { it.step == MountSyncStep.SITE }.outcome)
        assertEquals(MountSyncStepOutcome.Success, results.single { it.step == MountSyncStep.UNPARK }.outcome)
        assertIs<MountSyncStepOutcome.Skipped>(results.single { it.step == MountSyncStep.TRACKING }.outcome)

        // Now let the poll loop's interval elapse -- its :GR#/:GD# must land *after* every sync
        // command, never interleaved with them.
        advanceTimeBy(POLL_INTERVAL_MILLIS)
        runCurrent()
        assertEquals(afterConnect + listOf(":GR#", ":GD#"), transport.writtenCommands())
    }

    @Test
    fun connectSurfacesAMountSyncStepRejectionWithoutAbortingLaterSteps() = runTest {
        val transport = FakeTelescopeTransport()
        transport.enqueueInbound("18:36:56#") // :GR# link probe
        transport.enqueueInbound("1") // :SG accepted
        transport.enqueueInbound("1") // :SC accepted
        transport.enqueueInbound("1") // :SL accepted
        transport.enqueueInbound("1") // :St accepted
        transport.enqueueInbound("1") // :Sg accepted
        transport.enqueueInbound("0") // :hR rejected -- e.g. mount was never parked
        val connection = connectionWith(transport, location = MutableStateFlow(TEST_LOCATION))

        connection.connect(TCP_ENDPOINT)

        val results = connection.mountSyncResults.value
        assertEquals(MountSyncStepOutcome.Success, results.single { it.step == MountSyncStep.TIME }.outcome)
        assertEquals(MountSyncStepOutcome.Success, results.single { it.step == MountSyncStep.SITE }.outcome)
        assertIs<MountSyncStepOutcome.Failed>(results.single { it.step == MountSyncStep.UNPARK }.outcome)
        // Connection state itself is untouched -- a routine rejection, never an error, per
        // MountSyncStepResult's doc.
        assertEquals(TelescopeConnectionState.Connected(TCP_ENDPOINT), connection.state.value)
    }

    @Test
    fun connectWithoutAKnownLocationStillSyncsTimeAndUnparkButSkipsSite() = runTest {
        val transport = FakeTelescopeTransport().withMountSyncAcks()
        val connection = connectionWith(transport, location = MutableStateFlow(null))

        connection.connect(TCP_ENDPOINT)

        assertEquals(TelescopeConnectionState.Connected(TCP_ENDPOINT), connection.state.value)
        val results = connection.mountSyncResults.value
        assertEquals(MountSyncStepOutcome.Success, results.single { it.step == MountSyncStep.TIME }.outcome)
        assertEquals(MountSyncStepOutcome.Skipped("No location yet"), results.single { it.step == MountSyncStep.SITE }.outcome)
        assertEquals(MountSyncStepOutcome.Success, results.single { it.step == MountSyncStep.UNPARK }.outcome)
        // No :St#/:Sg# written at all -- SITE was skipped outright, never attempted.
        assertEquals(listOf(":SG +00#", ":hR#"), transport.writtenCommands().filter { it == ":SG +00#" || it == ":hR#" })
        assertEquals(5, transport.writtenCommands().size)
    }

    @Test
    fun anUnresponsiveMountFailsTheConnectionInsteadOfHangingForever() = runTest {
        // Nothing queued at all: the :GR# link probe never gets a reply. Regression test for the
        // real production bug this uncovered -- AndroidBluetoothTelescopeTransport's
        // readAvailable() is a genuinely blocking Java InputStream.read() that neither coroutine
        // cancellation nor Thread.interrupt() can preempt, so a plain withTimeout() around it
        // silently never fired and the whole connect (sync runs before polling, see
        // Lx200TelescopeConnection's class doc) hung forever instead of failing after the
        // configured timeout. See Lx200Session.readWithTimeout's doc for the fix: a watchdog that
        // force-disconnects the transport, which is what actually unblocks a stuck read.
        val transport = FakeTelescopeTransport()
        val connection = connectionWith(transport, location = MutableStateFlow(TEST_LOCATION))

        // connect() is awaited directly on the test's own coroutine, so runTest's scheduler
        // auto-advances virtual time to satisfy it -- including running the read watchdog's delay
        // to completion -- meaning connect() doesn't return until the whole attempt has already
        // settled to Failed, no explicit advanceTimeBy needed.
        connection.connect(TCP_ENDPOINT)

        // The watchdog force-disconnects the transport to unblock the stuck read; watchForDrop
        // then reports that exactly like any other dropped connection. mountSyncResults is
        // deliberately *not* wiped by that path (see cancelExistingConnectionLocked's doc) -- it's
        // the one place that shows which command the mount never answered, the exact thing someone
        // debugging a real "why did my mount disconnect" needs to see.
        assertIs<TelescopeConnectionState.Failed>(connection.state.value)
        assertEquals(
            MountSyncStepOutcome.Failed("Timed out waiting for a reply"),
            connection.mountSyncResults.value.single { it.step == MountSyncStep.LINK }.outcome,
        )
        assertNull(connection.reportedPosition.value)
    }

    @Test
    fun aSilentLinkProbeSkipsEverySyncStepBehindItRatherThanCascadingFailures() = runTest {
        // The whole point of MountSyncStep.LINK: with the socket closed by the probe's own watchdog
        // (the only thing that unblocks a stuck read, see Lx200Session.readWithTimeout), every
        // later Set command would otherwise fail against a dead socket and read as several
        // independent format/rejection problems instead of the one real cause.
        val transport = FakeTelescopeTransport()
        val connection = connectionWith(transport, location = MutableStateFlow(TEST_LOCATION))

        connection.connect(TCP_ENDPOINT)

        assertEquals(listOf(":GR#"), transport.writtenCommands())
        val results = connection.mountSyncResults.value
        assertIs<MountSyncStepOutcome.Failed>(results.single { it.step == MountSyncStep.LINK }.outcome)
        listOf(MountSyncStep.TIME, MountSyncStep.SITE, MountSyncStep.UNPARK, MountSyncStep.TRACKING).forEach { step ->
            assertEquals(MountSyncStepOutcome.Skipped("Mount not responding"), results.single { it.step == step }.outcome)
        }
    }

    @Test
    fun aGarbledLinkProbeReplyFailsTheLinkStepInsteadOfBlamingLaterCommands() = runTest {
        // What a baud mismatch (or a non-LX200 device on the same SPP socket) looks like: bytes do
        // come back, terminated even, but they aren't an RA. Must fail LINK, not pass it.
        val transport = FakeTelescopeTransport()
        transport.enqueueInbound("Ã©?#")
        val connection = connectionWith(transport, location = MutableStateFlow(TEST_LOCATION))

        connection.connect(TCP_ENDPOINT)

        assertEquals(listOf(":GR#"), transport.writtenCommands())
        assertIs<MountSyncStepOutcome.Failed>(
            connection.mountSyncResults.value.single { it.step == MountSyncStep.LINK }.outcome,
        )
    }

    @Test
    fun connectionDropDuringPollingFlipsStateToFailed() = runTest {
        val transport = FakeTelescopeTransport().withMountSyncAcks()
        val connection = connectionWith(transport)
        connection.connect(TCP_ENDPOINT)
        assertEquals(TelescopeConnectionState.Connected(TCP_ENDPOINT), connection.state.value)

        // Simulates a real transport (TcpTelescopeTransport/AndroidBluetoothTelescopeTransport)
        // discovering a dropped socket on its own, independent of the poll loop.
        transport.disconnect() // FakeTelescopeTransport.disconnect() sets DISCONNECTED, not FAILED,
        runCurrent() // but the watcher treats either as a drop -- see watchForDrop.

        assertEquals(TelescopeConnectionState.Failed(TCP_ENDPOINT, "Connection lost"), connection.state.value)
        assertNull(connection.reportedPosition.value)
    }

    @Test
    fun deliberateDisconnectDoesNotRaceWithTheDropWatcher() = runTest {
        val transport = FakeTelescopeTransport().withMountSyncAcks()
        val connection = connectionWith(transport)
        connection.connect(TCP_ENDPOINT)

        connection.disconnect()
        runCurrent()

        // Regression guard: teardown() must cancel the drop watcher *after* its other cleanup, or
        // a self-cancelling watcher job could overwrite this deliberate Disconnected with Failed.
        assertEquals(TelescopeConnectionState.Disconnected, connection.state.value)
    }

    @Test
    fun disconnectWhileConnectIsStillSyncingCancelsItCleanlyInsteadOfRacing() = runTest {
        // Nothing queued at all: the :GR# link probe has no reply, so connect() is still
        // suspended inside runMountSync's read when disconnect() runs below -- this is exactly
        // "tap Disconnect while the sync checklist is still filling in", which without
        // lifecycleMutex/connectionJob (see Lx200TelescopeConnection's class doc) corrupted
        // mountSyncResults with stray Failed entries from the orphaned sync continuing to run
        // after disconnect()'s own teardown, and could leave a zombie poll job behind.
        val transport = FakeTelescopeTransport()
        val connection = connectionWith(transport, location = MutableStateFlow(TEST_LOCATION))

        val connectJob = launch { connection.connect(TCP_ENDPOINT) }
        runCurrent() // lets connect() reach the pending, reply-less :SG read

        connection.disconnect()
        connectJob.join()

        assertEquals(TelescopeConnectionState.Disconnected, connection.state.value)
        // Cleanly reset, not left holding whatever the cancelled sync attempt had published so far.
        assertEquals(emptyList(), connection.mountSyncResults.value)
        assertEquals(listOf(":GR#"), transport.writtenCommands())
    }

    @Test
    fun connectTimesOutAgainstAnUnresponsiveDevice() = runTest {
        val transport = FakeTelescopeTransport().apply { connectShouldHang = true }
        val connection = Lx200TelescopeConnection(
            scope = backgroundScope,
            tcpTransportFactory = { _, _ -> transport },
            bluetoothTransportFactory = { error("not used") },
            location = MutableStateFlow(null),
            connectTimeoutMillis = 5_000L,
        )

        connection.connect(TCP_ENDPOINT)
        advanceUntilIdle()

        assertIs<TelescopeConnectionState.Failed>(connection.state.value)
        assertTrue((connection.state.value as TelescopeConnectionState.Failed).reason.contains("Timed out"))
    }

    @Test
    fun setSlewRatePresetWritesTheCommandAndExpectsNoReply() = runTest {
        // Nothing enqueued on purpose: :SX93 answers nothing at all (see
        // Lx200Codec.setSlewRatePreset), so this must not wait on a reply -- if it did, the read
        // watchdog would fire and tear the whole connection down.
        val transport = FakeTelescopeTransport().withMountSyncAcks()
        val connection = connectionWith(transport)
        connection.connect(TCP_ENDPOINT)

        connection.setSlewRatePreset(SlewRatePreset.FASTEST)

        assertEquals(listOf(":SX93,1#"), transport.writtenCommands().drop(5))
        assertEquals(TelescopeConnectionState.Connected(TCP_ENDPOINT), connection.state.value)
    }

    @Test
    fun setTrackingReportsWhetherTheMountAcceptedIt() = runTest {
        val transport = FakeTelescopeTransport().withMountSyncAcks()
        transport.enqueueInbound("1") // :Te# accepted
        transport.enqueueInbound("0") // :Te# refused -- e.g. the mount is parked
        val connection = connectionWith(transport)
        connection.connect(TCP_ENDPOINT)

        assertTrue(connection.setTracking(enabled = true))
        assertFalse(connection.setTracking(enabled = true))
        assertEquals(listOf(":Te#", ":Te#"), transport.writtenCommands().drop(5))
    }

    @Test
    fun readTrackingEnabledParsesTheStatusReply() = runTest {
        val transport = FakeTelescopeTransport().withMountSyncAcks()
        transport.enqueueInbound("nNpHEo000#")
        val connection = connectionWith(transport)
        connection.connect(TCP_ENDPOINT)

        assertEquals(false, connection.readTrackingEnabled())
        assertEquals(listOf(":GU#"), transport.writtenCommands().drop(5))
    }

    @Test
    fun mountOptionCommandsAreNoOpsWithoutAConnection() = runTest {
        val transport = FakeTelescopeTransport()
        val connection = connectionWith(transport)

        connection.setSlewRatePreset(SlewRatePreset.SLOWEST)

        assertFalse(connection.setTracking(enabled = true))
        assertNull(connection.readTrackingEnabled())
        assertEquals(emptyList(), transport.writtenCommands())
    }

    /** Queues replies for the 5 mount-sync commands that always run regardless of location (:GR's
     *  link probe, then :SG, :SC, :SL, :hR -- see [Lx200TelescopeConnection.runMountSync]) so tests
     *  about connecting/slewing/aborting/dropping don't have to reason about sync at all. Must be
     *  called before any of the test's own [FakeTelescopeTransport.enqueueInbound] calls -- replies
     *  are strict FIFO. Safe to combine with the test's own scenario-specific replies with no risk
     *  of the poll loop stealing any of them: it never touches the transport until its own interval
     *  elapses (see [Lx200TelescopeConnection.pollPosition]'s doc). */
    private fun FakeTelescopeTransport.withMountSyncAcks(): FakeTelescopeTransport {
        enqueueInbound("18:36:56#") // :GR# link probe -- the one hash-terminated reply here
        // Bare, unterminated -- what a real mount actually sends, see Lx200Session.executeCharAck.
        // Queueing these as "1#" instead is what let the reply-shape bug through unnoticed: the
        // fake happily supplied the terminator the real mount never sends.
        repeat(4) { enqueueInbound("1") } // :SG :SC :SL :hR
        return this
    }

    private fun kotlinx.coroutines.test.TestScope.connectionWith(
        transport: FakeTelescopeTransport,
        location: StateFlow<ObserverLocation?> = MutableStateFlow(null),
    ) = Lx200TelescopeConnection(
        scope = backgroundScope,
        tcpTransportFactory = { _, _ -> transport },
        bluetoothTransportFactory = { error("not used in these tests") },
        location = location,
    )
}
