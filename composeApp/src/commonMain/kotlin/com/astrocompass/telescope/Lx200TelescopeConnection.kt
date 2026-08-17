package com.astrocompass.telescope

import com.astrocompass.astro.coords.EquatorialCoordinates
import com.astrocompass.astro.time.currentEpochMillis
import com.astrocompass.location.ObserverLocation
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private const val DEFAULT_POLL_INTERVAL_MILLIS = 1_000L
private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000L

/**
 * [TelescopeConnection] over LX200. Owns one [Lx200Session] (and the [TelescopeTransport]
 * beneath it) plus one polling [Job] at a time -- [connect] tears down any existing connection
 * first, mirroring [com.astrocompass.guiding.AlignmentAbsoluteReference]'s single-slot shape.
 * [tcpTransportFactory]/[bluetoothTransportFactory] are injected rather than constructed here so
 * Android and iOS can supply different Bluetooth implementations (a real one vs. a stub), the
 * same pattern [com.astrocompass.sensors.OrientationSensor]'s platform implementations use.
 *
 * [connect] also always runs the mount-sync sequence (time, site, unpark -- see [MountSyncStep])
 * against [location]'s current value, strictly *before* [pollJob] starts: [Lx200Session] serializes
 * each individual command/reply exchange, but nothing serializes *which* caller's exchange goes
 * next, so running sync concurrently with polling risked a sync command reading a reply the poll
 * loop's `:GR#`/`:GD#` was waiting on (or vice versa). No separate sync step exists on this
 * interface for exactly that reason -- it is entirely [connect]'s responsibility, not a second call
 * a caller could accidentally interleave with the poll loop it started. A connect before [location]
 * has a value yet (e.g. GPS hasn't produced a first fix) still syncs time and unparks -- only the
 * site step needs a location, and reports why it alone was skipped rather than the whole sequence
 * silently not running.
 *
 * [connect] runs its work ([performConnect]) as a tracked, cancellable [connectionJob] rather than
 * inline in the caller's own coroutine: [state] flips to [TelescopeConnectionState.Connected]
 * *before* the (potentially many-second) sync sequence finishes, so [disconnect] -- or a fresh
 * [connect] -- can legitimately be called while sync is still running. Without [connectionJob] to
 * cancel, that second call would run concurrently with [performConnect] and both would mutate
 * [transport]/[session]/[_mountSyncResults] with no synchronization -- exactly what happened before
 * this existed: tapping Disconnect mid-sync corrupted the in-flight sync's results instead of
 * cleanly stopping it. [lifecycleMutex] serializes the handful of statements that read-modify-write
 * [connectionJob] itself (across [connect]/[disconnect]/[watchForDrop]'s own teardown), not the
 * whole connect duration -- [connect] only holds it long enough to cancel any previous attempt and
 * launch the new one, then awaits completion outside the lock so a concurrent [disconnect] is never
 * stuck waiting behind a slow or unresponsive mount.
 */
class Lx200TelescopeConnection(
    private val scope: CoroutineScope,
    private val tcpTransportFactory: (host: String, port: Int) -> TelescopeTransport,
    private val bluetoothTransportFactory: (address: String) -> TelescopeTransport,
    private val location: StateFlow<ObserverLocation?>,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    private val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
) : TelescopeConnection {

    private val _state = MutableStateFlow<TelescopeConnectionState>(TelescopeConnectionState.Disconnected)
    override val state: StateFlow<TelescopeConnectionState> = _state

    private val _reportedPosition = MutableStateFlow<TelescopeReport?>(null)
    override val reportedPosition: StateFlow<TelescopeReport?> = _reportedPosition

    private val _mountSyncResults = MutableStateFlow<List<MountSyncStepResult>>(emptyList())
    override val mountSyncResults: StateFlow<List<MountSyncStepResult>> = _mountSyncResults

    private var transport: TelescopeTransport? = null
    private var session: Lx200Session? = null
    private var connectionJob: Job? = null
    private var pollJob: Job? = null
    private var dropWatchJob: Job? = null
    private val lifecycleMutex = Mutex()

    override suspend fun connect(endpoint: TelescopeEndpoint) {
        val job = lifecycleMutex.withLock {
            cancelExistingConnectionLocked()
            _mountSyncResults.value = emptyList() // starting fresh -- see the doc on that reset below
            scope.launch { performConnect(endpoint) }.also { connectionJob = it }
        }
        job.join()
    }

    override suspend fun disconnect() {
        lifecycleMutex.withLock { cancelExistingConnectionLocked() }
        _mountSyncResults.value = emptyList() // user-initiated -- same "starting fresh" reasoning as connect()
        _state.value = TelescopeConnectionState.Disconnected
    }

    private suspend fun performConnect(endpoint: TelescopeEndpoint) {
        val newTransport = when (endpoint.kind) {
            TelescopeTransportKind.TCP -> {
                val host = endpoint.host
                val port = endpoint.port
                if (host == null || port == null) {
                    _state.value = TelescopeConnectionState.Failed(endpoint, "Missing host/port")
                    return
                }
                tcpTransportFactory(host, port)
            }

            TelescopeTransportKind.BLUETOOTH_CLASSIC -> {
                val address = endpoint.bluetoothAddress
                if (address == null) {
                    _state.value = TelescopeConnectionState.Failed(endpoint, "Missing Bluetooth device")
                    return
                }
                bluetoothTransportFactory(address)
            }
        }

        transport = newTransport
        _state.value = TelescopeConnectionState.Connecting(endpoint)

        // withTimeoutOrNull cancels the *coroutine* waiting on connect(), not necessarily the
        // underlying I/O -- TcpTelescopeTransport's suspend socket connect is genuinely
        // cancellable, but AndroidBluetoothTelescopeTransport's connect() blocks a real JVM
        // thread on BluetoothSocket.connect(), which coroutine cancellation cannot interrupt. In
        // that case the abandoned attempt finishes on its own later against a transport instance
        // nothing is watching any more; disconnect() below is a no-op for it (there's no socket
        // to close yet), so this is a bounded, harmless leak of one stray attempt, not a stuck
        // app -- the alternative (closing the transport mid-attempt from another thread) isn't
        // exposed by [TelescopeTransport]'s contract.
        val timedOut = withTimeoutOrNull(connectTimeoutMillis) { newTransport.connect() } == null
        if (timedOut) {
            transport = null
            _state.value = TelescopeConnectionState.Failed(endpoint, "Timed out connecting to ${endpoint.displayName}")
            runCatching { newTransport.disconnect() }
            return
        }

        if (newTransport.state.value != TelescopeTransportState.CONNECTED) {
            transport = null
            _state.value = TelescopeConnectionState.Failed(endpoint, "Could not connect to ${endpoint.displayName}")
            return
        }

        val newSession = Lx200Session(newTransport)
        session = newSession
        _state.value = TelescopeConnectionState.Connected(endpoint)
        // Started before the sync sequence below: watching transport.state doesn't touch the
        // session's command/reply channel, so it can't race pollPosition/runMountSync the way two
        // *session* users would.
        dropWatchJob = scope.launch { watchForDrop(newTransport, endpoint) }

        runMountSync(newSession, location.value, currentEpochMillis())

        pollJob = scope.launch { pollPosition(newSession) }
    }

    override suspend fun slewTo(target: EquatorialCoordinates): SlewOutcome {
        val activeSession = session ?: return SlewOutcome.NoConnection

        val raAck = activeSession.executeCharAck(Lx200Codec.setTargetRightAscension(target.rightAscension))
        if (!Lx200Codec.parseAck(raAck)) return SlewOutcome.Rejected("Mount rejected target right ascension")

        val decAck = activeSession.executeCharAck(Lx200Codec.setTargetDeclination(target.declination))
        if (!Lx200Codec.parseAck(decAck)) return SlewOutcome.Rejected("Mount rejected target declination")

        return when (val ack = activeSession.executeSlew()) {
            SlewAck.Started -> SlewOutcome.Started
            is SlewAck.Rejected -> SlewOutcome.Rejected(ack.reason)
        }
    }

    override suspend fun abortSlew() {
        session?.executeNoReply(Lx200Codec.abortSlew())
    }

    override suspend fun setSlewRatePreset(preset: SlewRatePreset) {
        withSession(Unit) { it.executeNoReply(Lx200Codec.setSlewRatePreset(preset)) }
    }

    override suspend fun setTracking(enabled: Boolean): Boolean = withSession(false) {
        Lx200Codec.parseAck(it.executeCharAck(Lx200Codec.setTracking(enabled)))
    }

    override suspend fun readTrackingEnabled(): Boolean? = withSession(null) {
        Lx200Codec.parseTrackingEnabled(it.executeHashTerminated(Lx200Codec.getStatus()))
    }

    /** Shared shape for the on-demand option commands above: with no connection, or when the mount
     *  times out or answers something unparseable, they report [fallback] rather than throwing --
     *  they're driven straight from a user tapping a control, where a failure is routine and a
     *  crash is not. [CancellationException] is rethrown for the same reason [runSyncStep] rethrows
     *  it: a closing sheet or a torn-down connection must still unwind. */
    private suspend fun <T> withSession(fallback: T, command: suspend (Lx200Session) -> T): T {
        val activeSession = session ?: return fallback
        return try {
            command(activeSession)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fallback
        }
    }

    /** Called only from [connect], strictly before [pollJob] starts -- see the class doc for why
     *  that ordering, not just the sync commands themselves, is the important part here. Always
     *  runs (never skipped as a whole): only [MountSyncStep.SITE] actually needs [siteLocation],
     *  so a connect before the first GPS/manual location fix still gets the mount's clock and
     *  unpark, with [MountSyncStep.SITE] alone reporting why it was skipped -- an empty
     *  [mountSyncResults] would otherwise silently look like sync never ran at all.
     *
     *  Every step publishes an entry even when [MountSyncStep.LINK] fails and the rest never go on
     *  the wire, for that same reason. */
    private suspend fun runMountSync(activeSession: Lx200Session, siteLocation: ObserverLocation?, nowEpochMillis: Long) {
        val results = mutableListOf<MountSyncStepResult>()
        fun publish(step: MountSyncStep, outcome: MountSyncStepOutcome) {
            results += MountSyncStepResult(step, outcome)
            _mountSyncResults.value = results.toList()
        }

        val linkOutcome = runSyncStep("Mount did not answer") {
            // Parses the reply rather than just reading bytes: a garbled reply (wrong baud, a
            // non-LX200 device answering) must fail this step, not pass it and then mislead the
            // later steps into looking like format problems.
            Lx200Codec.parseRightAscension(activeSession.executeHashTerminated(Lx200Codec.getRightAscension()))
            true
        }
        publish(MountSyncStep.LINK, linkOutcome)
        if (linkOutcome != MountSyncStepOutcome.Success) {
            val unreachable = MountSyncStepOutcome.Skipped("Mount not responding")
            listOf(MountSyncStep.TIME, MountSyncStep.SITE, MountSyncStep.UNPARK, MountSyncStep.TRACKING)
                .forEach { publish(it, unreachable) }
            return
        }

        val utc = Instant.fromEpochMilliseconds(nowEpochMillis).toLocalDateTime(TimeZone.UTC)
        publish(MountSyncStep.TIME, runSyncStep("Mount rejected time sync") {
            Lx200Codec.parseAck(activeSession.executeCharAck(Lx200Codec.setUtcOffset(0))) &&
                Lx200Codec.parseAck(activeSession.executeCharAck(Lx200Codec.setDate(utc.year, utc.month.ordinal + 1, utc.day))) &&
                Lx200Codec.parseAck(activeSession.executeCharAck(Lx200Codec.setTime(utc.hour, utc.minute, utc.second)))
        })

        publish(
            MountSyncStep.SITE,
            if (siteLocation == null) {
                MountSyncStepOutcome.Skipped("No location yet")
            } else {
                runSyncStep("Mount rejected site location") {
                    Lx200Codec.parseAck(activeSession.executeCharAck(Lx200Codec.setSiteLatitude(siteLocation.latitude))) &&
                        Lx200Codec.parseAck(activeSession.executeCharAck(Lx200Codec.setSiteLongitude(siteLocation.longitude)))
                }
            },
        )

        publish(MountSyncStep.UNPARK, runSyncStep("Mount rejected unpark") {
            Lx200Codec.parseAck(activeSession.executeCharAck(Lx200Codec.unpark()))
        })

        // See MountSyncStep.TRACKING's doc: no mount-independent sidereal rate exists to send
        // safely, so this is deliberately not attempted rather than guessed.
        publish(
            MountSyncStep.TRACKING,
            MountSyncStepOutcome.Skipped("Not automated -- unpark resumes the mount's own tracking state"),
        )
    }

    /** A command timing out ([Lx200Session.readWithTimeout]) surfaces as a plain
     *  [Lx200ReplyTimeoutException], not a [CancellationException] -- see that class's doc for
     *  why -- so it falls into the same bucket as a rejected ack or any other [Exception] below: a
     *  routine, bounded per-step failure, not different from one, so later steps still run.
     *  [CancellationException] itself is deliberately rethrown, not swallowed: a *genuine* external
     *  cancellation ([connectionJob] being cancelled by [cancelExistingConnectionLocked], or the
     *  app shutting down) must still propagate and unwind the sequence -- catching it here would be
     *  the structured-concurrency hazard [runCatching] alone had, and is exactly what
     *  [cancelExistingConnectionLocked] relies on to stop a cancelled sync promptly instead of
     *  running it to completion first. */
    private suspend fun runSyncStep(rejectionReason: String, send: suspend () -> Boolean): MountSyncStepOutcome =
        try {
            if (send()) MountSyncStepOutcome.Success else MountSyncStepOutcome.Failed(rejectionReason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MountSyncStepOutcome.Failed(e.message ?: "Mount sync step failed")
        }

    /** Runs until [pollJob] is cancelled by [cancelExistingConnectionLocked]. Waits out
     *  [pollIntervalMillis] *before* its first read, not just between reads -- besides spacing
     *  reads evenly from the moment polling actually starts, this means [pollJob] never has
     *  anything to do the instant it's launched, so it can never opportunistically race
     *  [connect]'s own [performConnect]/`job.join()` for the transport (see [Lx200TelescopeConnection]'s
     *  class doc on why sync must finish first). A failed exchange (a dropped connection the
     *  transport hasn't yet surfaced through [TelescopeTransport.state]) is swallowed rather than
     *  tearing the loop down -- the next tick simply tries again, and a report that stops updating
     *  is exactly the staleness signal [TelescopePointingSource] watches for. */
    private suspend fun pollPosition(session: Lx200Session) {
        while (true) {
            delay(pollIntervalMillis)
            runCatching {
                val ra = Lx200Codec.parseRightAscension(session.executeHashTerminated(Lx200Codec.getRightAscension()))
                val dec = Lx200Codec.parseDeclination(session.executeHashTerminated(Lx200Codec.getDeclination()))
                _reportedPosition.value = TelescopeReport(EquatorialCoordinates(ra, dec), currentEpochMillis())
            }
        }
    }

    /** Observes the transport after a successful connect: both [TcpTelescopeTransport] and the
     *  Android Bluetooth transport genuinely flip their own [TelescopeTransportState] to [FAILED]
     *  on a real I/O error or peer disconnect (verified in their `write`/`readAvailable`), so this
     *  is a real signal, not a heuristic. Without it, [state] stays [Connected] forever after a
     *  mid-session drop -- [pollPosition]'s failures are swallowed by design, and nothing else
     *  ever re-checks the transport, which would leave a GOTO button live against a dead socket. */
    private suspend fun watchForDrop(watchedTransport: TelescopeTransport, endpoint: TelescopeEndpoint) {
        watchedTransport.state
            .filter { it == TelescopeTransportState.FAILED || it == TelescopeTransportState.DISCONNECTED }
            .collect {
                if (_state.value is TelescopeConnectionState.Connected) {
                    _state.value = TelescopeConnectionState.Failed(endpoint, "Connection lost")
                    // dropWatchJob (this coroutine) is never connectionJob itself -- watchForDrop
                    // is launched as its own sibling job from within performConnect, not nested
                    // inside it -- so cancelAndJoin-ing connectionJob here can't self-join.
                    lifecycleMutex.withLock { cancelExistingConnectionLocked() }
                }
            }
    }

    /** Cancels and awaits whatever the previous [connect] call left running -- fully established,
     *  or still mid-[performConnect] (mid-sync) -- then clears every field it touched. Must be
     *  called with [lifecycleMutex] held: [connectionJob], [pollJob], [dropWatchJob], [session],
     *  and [transport] are all mutated by more than one coroutine ([connect], [disconnect],
     *  [watchForDrop]), and this is the one place all three funnel through before touching any of
     *  them. [dropWatchJob] is cancelled last, after every other suspending cleanup step: when
     *  [watchForDrop] itself is the caller (a dropped connection tearing itself down),
     *  self-cancelling any earlier would abandon whatever in this function hadn't run yet.
     *
     *  Deliberately does *not* clear [mountSyncResults]: [connect] and [disconnect] do that
     *  themselves (a fresh connect attempt, or a user-initiated disconnect, both legitimately want
     *  a blank checklist), but [watchForDrop] calling this alone must leave it exactly as it was --
     *  an unresponsive mount produces a checklist showing which step never got a reply right before
     *  the drop, and wiping that is exactly the diagnostic information someone debugging "why did
     *  my mount disconnect" needs most. */
    private suspend fun cancelExistingConnectionLocked() {
        connectionJob?.cancelAndJoin()
        connectionJob = null

        pollJob?.cancel()
        pollJob = null
        session = null
        transport?.disconnect()
        transport = null
        _reportedPosition.value = null
        dropWatchJob?.cancel()
        dropWatchJob = null
    }
}
