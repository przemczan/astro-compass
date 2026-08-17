@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.astrocompass.telescope

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Lx200SessionTest {

    @Test
    fun returnsReplyBodyWithTerminatorStripped() = runTest {
        val transport = FakeTelescopeTransport().also { it.connect() }
        transport.enqueueInbound("18:36:56#")
        val session = Lx200Session(transport)

        assertEquals("18:36:56", session.executeHashTerminated(":GR#"))
        assertEquals(listOf(":GR#"), transport.writtenCommands())
    }

    @Test
    fun reassemblesAReplySplitAcrossMultipleReads() = runTest {
        val transport = FakeTelescopeTransport().also { it.connect() }
        transport.enqueueInbound("18:3")
        transport.enqueueInbound("6:5")
        transport.enqueueInbound("6#")
        val session = Lx200Session(transport)

        assertEquals("18:36:56", session.executeHashTerminated(":GR#"))
    }

    @Test
    fun leavesLeftoverBytesBufferedForTheNextExchange() = runTest {
        val transport = FakeTelescopeTransport().also { it.connect() }
        // Both replies arrive in a single chunk -- the second exchange must not need another
        // transport read at all, proving leftover bytes past the first '#' are carried over.
        transport.enqueueInbound("18:36:56#+38*47:01#")
        val session = Lx200Session(transport)

        assertEquals("18:36:56", session.executeHashTerminated(":GR#"))
        assertEquals("+38*47:01", session.executeHashTerminated(":GD#"))
    }

    @Test
    fun setCommandAckReadsExactlyOneByteWithNoTerminator() = runTest {
        val transport = FakeTelescopeTransport().also { it.connect() }
        // Bare '1', nothing else -- what a real mount sends for every Set command, and the shape
        // that made executeHashTerminated hang here forever waiting on a '#' that never comes.
        transport.enqueueInbound("1")
        val session = Lx200Session(transport)

        assertTrue(Lx200Codec.parseAck(session.executeCharAck(":SG +00#")))
        assertEquals(listOf(":SG +00#"), transport.writtenCommands())
    }

    @Test
    fun consecutiveSetCommandAcksStayAlignedWithTheirOwnCommands() = runTest {
        val transport = FakeTelescopeTransport().also { it.connect() }
        // Both acks in one chunk: reading exactly one byte must leave the second for the second
        // command, not swallow it -- an off-by-one here would silently ack the wrong command.
        transport.enqueueInbound("10")
        val session = Lx200Session(transport)

        assertTrue(Lx200Codec.parseAck(session.executeCharAck(":St +51*30#")))
        assertFalse(Lx200Codec.parseAck(session.executeCharAck(":Sg +000*06#")))
    }

    @Test
    fun slewSuccessReadsExactlyOneByteWithNoTerminator() = runTest {
        val transport = FakeTelescopeTransport().also { it.connect() }
        // Bare '0', nothing else -- the classic LX200 :MS# success footgun.
        transport.enqueueInbound("0")
        val session = Lx200Session(transport)

        assertEquals(SlewAck.Started, session.executeSlew())
    }

    @Test
    fun slewRejectionReadsReasonUntilTerminator() = runTest {
        val transport = FakeTelescopeTransport().also { it.connect() }
        transport.enqueueInbound("1Object Below Horizon#")
        val session = Lx200Session(transport)

        assertEquals(SlewAck.Rejected("Object Below Horizon"), session.executeSlew())
    }

    @Test
    fun noReplyCommandOnlyWritesAndNeverReads() = runTest {
        val transport = FakeTelescopeTransport().also { it.connect() }
        val session = Lx200Session(transport)

        session.executeNoReply(":Q#")

        assertEquals(listOf(":Q#"), transport.writtenCommands())
    }

    @Test
    fun serializesConcurrentExchangesSoWritesNeverInterleave() = runTest {
        val transport = FakeTelescopeTransport().also { it.connect() }
        val session = Lx200Session(transport)

        val resultA = CompletableDeferred<String>()
        val resultB = CompletableDeferred<String>()
        val jobA = launch { resultA.complete(session.executeHashTerminated(":GR#")) }
        val jobB = launch { resultB.complete(session.executeHashTerminated(":GD#")) }

        // Let both coroutines run until they block. B must still be waiting on the mutex --
        // it isn't released until A's whole write+read exchange completes -- so only A's
        // command should have reached the transport.
        //
        // runCurrent(), not advanceUntilIdle(): each pending read races a watchdog (see
        // Lx200Session.readWithTimeout) whose delay() is a future-timed task on the test
        // scheduler. advanceUntilIdle() fast-forwards virtual time through the *entire* scheduled
        // queue -- including that not-yet-due watchdog -- so it would fire it and force-disconnect
        // the transport before enqueueInbound ever supplies its reply. runCurrent() only drains
        // tasks that are ready *now*, leaving the still-pending watchdog alone.
        runCurrent()
        assertEquals(listOf(":GR#"), transport.writtenCommands())

        transport.enqueueInbound("18:36:56#")
        runCurrent()
        assertEquals("18:36:56", resultA.await())
        assertEquals(listOf(":GR#", ":GD#"), transport.writtenCommands())

        transport.enqueueInbound("+38*47:01#")
        runCurrent()
        assertEquals("+38*47:01", resultB.await())

        jobA.join()
        jobB.join()
    }
}
