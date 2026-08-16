package com.astrocompass.telescope

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

private const val DEFAULT_TIMEOUT_MILLIS = 3_000L

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
     *  stripped. Covers every LX200 query/set command except [executeSlew]'s `:MS#`, whose
     *  success reply breaks this convention. */
    suspend fun executeHashTerminated(command: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): String =
        mutex.withLock {
            transport.write(command.encodeToByteArray())
            withTimeout(timeoutMillis) { readUntilHash() }
        }

    /** Sends a command with no reply at all (`:Q#`, abort). */
    suspend fun executeNoReply(command: String) {
        mutex.withLock {
            transport.write(command.encodeToByteArray())
        }
    }

    /** Sends `:MS#` (or a caller-supplied variant) and interprets its reply. Success is a bare
     *  `'0'` with **no trailing `#`** -- reading exactly one byte first, and only continuing to
     *  read until `#` on the rejection path (`'1'`/`'2'` + reason text), is what avoids hanging
     *  forever waiting for a terminator that success never sends. The leading `'1'`/`'2'` status
     *  digit is discarded from [SlewAck.Rejected.reason] -- the message text itself already says
     *  why ("Object Below Horizon"), so keeping the digit glued on would only make it worse to
     *  display. */
    suspend fun executeSlew(command: String = Lx200Codec.slewToTarget(), timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): SlewAck =
        mutex.withLock {
            transport.write(command.encodeToByteArray())
            withTimeout(timeoutMillis) {
                val first = readExactly(1)
                if (first == "0") {
                    SlewAck.Started
                } else {
                    SlewAck.Rejected(reason = readUntilHash())
                }
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
