package com.astrocompass.telescope

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val DEFAULT_TIMEOUT_MILLIS = 3_000L

/** Thrown by [Lx200Session] when a reply doesn't arrive within the timeout -- deliberately not a
 *  [CancellationException] subtype, so callers (e.g. [Lx200TelescopeConnection.runSyncStep]) can
 *  tell "this one command was unresponsive" apart from genuine structured-concurrency cancellation
 *  without special-casing it. See [Lx200Session.readWithTimeout] for why plain `withTimeout` alone
 *  can't produce this reliably. */
class Lx200ReplyTimeoutException(message: String) : Exception(message)

/**
 * Sequences LX200 command/reply exchanges over a [TelescopeTransport]. LX200 is strictly
 * half-duplex with no message framing beyond terminators, so every write+read pair is
 * mutex-serialized here -- this is what makes it safe to run a ~1 Hz position poll
 * ([Lx200TelescopeConnection]) and a user-triggered slew on the same connection without one
 * corrupting the other's reply.
 *
 * [transport.readAvailable] returns arbitrary chunks with no guarantee they align to a reply
 * boundary; [buffer] carries any bytes read past the end of one reply forward to the next call.
 */
class Lx200Session(private val transport: TelescopeTransport) {
    private val mutex = Mutex()
    private var buffer: String = ""

    /** Sends [command] and reads a `#`-terminated reply, returning its body with the terminator
     *  stripped. Covers the Get commands (`:GR#`, `:GD#`, ...) -- but *not* the Set commands, whose
     *  acks are unterminated ([executeCharAck]), nor [executeSlew]'s `:MS#`. */
    suspend fun executeHashTerminated(command: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): String =
        mutex.withLock {
            transport.write(command.encodeToByteArray())
            readWithTimeout(timeoutMillis) { readUntilHash() }
        }

    /** Sends a Set command (`:Sr#`, `:Sd#`, `:SC#`, `:SL#`, `:SG#`, `:St#`, `:Sg#`, `:hR#`, ...)
     *  and reads its ack, returning the single reply character for [Lx200Codec.parseAck].
     *
     *  That ack is **one bare character with no `#` terminator** -- the same footgun [executeSlew]
     *  documents for `:MS#`'s success reply, and it applies to every Set command, not just that one.
     *  Confirmed on the wire against a real OnStep mount and in OnStepX's own source
     *  (`libApp/commands/ProcessCmds.cpp`): a numeric reply forces `suppressFrame`, so the `'#'`
     *  append is skipped. Reading these with [executeHashTerminated] instead is not a parse
     *  problem but a hang -- the read blocks waiting for a terminator the mount will never send,
     *  until the timeout tears the whole connection down. Notably `:SC#` sends nothing beyond that
     *  one character either: OnStepX does not reproduce classic Meade's extra "Updating Planetary
     *  Data#" strings, so no draining is needed to keep the next exchange's reply aligned. */
    suspend fun executeCharAck(command: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): String =
        mutex.withLock {
            transport.write(command.encodeToByteArray())
            readWithTimeout(timeoutMillis) { readExactly(1) }
        }

    /** Sends a command with no reply at all (`:Q#`, abort). */
    suspend fun executeNoReply(command: String) {
        mutex.withLock {
            transport.write(command.encodeToByteArray())
        }
    }

    /** Sends `:MS#` (or a caller-supplied variant) and interprets its reply. Success is a bare
     *  `'0'` with **no trailing `#`** (as with every Set command's ack, see [executeCharAck]) --
     *  reading exactly one byte first, and only continuing to read until `#` on the rejection path
     *  (`'1'`/`'2'` + reason text), is what avoids hanging forever waiting for a terminator that
     *  success never sends. Note the inverted digit vs. [executeCharAck]: `:MS#` answers `'0'` for
     *  *accepted*, where a Set command answers `'1'`. The leading `'1'`/`'2'` status
     *  digit is discarded from [SlewAck.Rejected.reason] -- the message text itself already says
     *  why ("Object Below Horizon"), so keeping the digit glued on would only make it worse to
     *  display. */
    suspend fun executeSlew(command: String = Lx200Codec.slewToTarget(), timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): SlewAck =
        mutex.withLock {
            transport.write(command.encodeToByteArray())
            readWithTimeout(timeoutMillis) {
                val first = readExactly(1)
                if (first == "0") {
                    SlewAck.Started
                } else {
                    SlewAck.Rejected(reason = readUntilHash())
                }
            }
        }

    /**
     * Bounds [read] to [timeoutMillis] -- but unlike a plain `withTimeout(timeoutMillis) { read() }`,
     * this actually works when [transport] is [AndroidBluetoothTelescopeTransport]: its
     * `readAvailable` blocks a real JVM thread inside `BluetoothSocket`'s `InputStream.read()`,
     * a raw blocking Java call that responds to neither coroutine cancellation nor
     * `Thread.interrupt()` (unlike NIO channels, `java.io` streams aren't interruptible). Plain
     * `withTimeout` would mark the read's job cancelled and then simply wait for it anyway, since
     * cancellation can only take effect at a suspension point the blocked thread never reaches --
     * in practice, the timeout silently never fires and the whole connect (sync runs before
     * polling, see [Lx200TelescopeConnection]'s class doc) hangs forever on the mount's first
     * unanswered command instead of failing after [timeoutMillis].
     *
     * The one thing that reliably unblocks a stuck `InputStream.read()` is closing the socket out
     * from under it, which [TelescopeTransport.disconnect] already does -- so this races the real
     * read against a watchdog that disconnects (and cancels the read) if [timeoutMillis] elapses
     * first. [TcpTelescopeTransport]'s suspend socket read is genuinely cancellable and never
     * needs the watchdog to fire, but applying this unconditionally keeps one code path correct on
     * both transports rather than needing to special-case which one this session is talking to.
     * The real cost: a timeout necessarily tears down the whole connection (there is no way to
     * abandon just the one stuck read on a socket that must be closed to unblock it), so a single
     * unresponsive command can no longer be a purely local, connection-preserving failure the way
     * a rejected ack is -- [watchForDrop][Lx200TelescopeConnection.watchForDrop] picks up the
     * resulting disconnect and reports it as a lost connection, same as any other drop.
     */
    private suspend fun <T> readWithTimeout(timeoutMillis: Long, read: suspend () -> T): T = coroutineScope {
        var timedOut = false
        val result = async { read() }
        val watchdog = launch {
            delay(timeoutMillis)
            timedOut = true
            result.cancel()
            runCatching { transport.disconnect() }
        }
        try {
            result.await()
        } catch (e: CancellationException) {
            if (timedOut) throw Lx200ReplyTimeoutException("Timed out waiting for a reply") else throw e
        } finally {
            watchdog.cancel()
        }
    }

    private suspend fun readExactly(count: Int): String {
        ensureBuffered(count)
        val result = buffer.substring(0, count)
        buffer = buffer.substring(count)
        return result
    }

    private suspend fun readUntilHash(): String {
        while (true) {
            val terminatorIndex = buffer.indexOf('#')
            if (terminatorIndex >= 0) {
                val result = buffer.substring(0, terminatorIndex)
                buffer = buffer.substring(terminatorIndex + 1)
                return result
            }
            buffer += transport.readAvailable().decodeToString()
        }
    }

    private suspend fun ensureBuffered(minLength: Int) {
        while (buffer.length < minLength) {
            buffer += transport.readAvailable().decodeToString()
        }
    }
}
