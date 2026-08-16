package com.astrocompass.telescope

import com.astrocompass.astro.coords.EquatorialCoordinates
import com.astrocompass.astro.time.currentEpochMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val DEFAULT_POLL_INTERVAL_MILLIS = 1_000L

/**
 * [TelescopeConnection] over LX200. Owns one [Lx200Session] (and the [TelescopeTransport]
 * beneath it) plus one polling [Job] at a time -- [connect] tears down any existing connection
 * first, mirroring [com.astrocompass.guiding.AlignmentAbsoluteReference]'s single-slot shape.
 * [tcpTransportFactory]/[bluetoothTransportFactory] are injected rather than constructed here so
 * Android and iOS can supply different Bluetooth implementations (a real one vs. a stub), the
 * same pattern [com.astrocompass.sensors.OrientationSensor]'s platform implementations use.
 */
class Lx200TelescopeConnection(
    private val scope: CoroutineScope,
    private val tcpTransportFactory: (host: String, port: Int) -> TelescopeTransport,
    private val bluetoothTransportFactory: (address: String) -> TelescopeTransport,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
) : TelescopeConnection {

    private val _state = MutableStateFlow<TelescopeConnectionState>(TelescopeConnectionState.Disconnected)
    override val state: StateFlow<TelescopeConnectionState> = _state

    private val _reportedPosition = MutableStateFlow<TelescopeReport?>(null)
    override val reportedPosition: StateFlow<TelescopeReport?> = _reportedPosition

    private var transport: TelescopeTransport? = null
    private var session: Lx200Session? = null
    private var pollJob: Job? = null

    override suspend fun connect(endpoint: TelescopeEndpoint) {
        teardown()

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
        newTransport.connect()

        if (newTransport.state.value != TelescopeTransportState.CONNECTED) {
            transport = null
            _state.value = TelescopeConnectionState.Failed(endpoint, "Could not connect to ${endpoint.displayName}")
            return
        }

        val newSession = Lx200Session(newTransport)
        session = newSession
        _state.value = TelescopeConnectionState.Connected(endpoint)
        pollJob = scope.launch { pollPosition(newSession) }
    }

    override suspend fun disconnect() {
        teardown()
        _state.value = TelescopeConnectionState.Disconnected
    }

    override suspend fun slewTo(target: EquatorialCoordinates): SlewOutcome {
        val activeSession = session ?: return SlewOutcome.NoConnection

        val raAck = activeSession.executeHashTerminated(Lx200Codec.setTargetRightAscension(target.rightAscension))
        if (!Lx200Codec.parseTargetSetAck(raAck)) return SlewOutcome.Rejected("Mount rejected target right ascension")

        val decAck = activeSession.executeHashTerminated(Lx200Codec.setTargetDeclination(target.declination))
        if (!Lx200Codec.parseTargetSetAck(decAck)) return SlewOutcome.Rejected("Mount rejected target declination")

        return when (val ack = activeSession.executeSlew()) {
            SlewAck.Started -> SlewOutcome.Started
            is SlewAck.Rejected -> SlewOutcome.Rejected(ack.reason)
        }
    }

    override suspend fun abortSlew() {
        session?.executeNoReply(Lx200Codec.abortSlew())
    }

    /** Runs until [pollJob] is cancelled by [teardown]. A failed exchange (a dropped connection
     *  the transport hasn't yet surfaced through [TelescopeTransport.state]) is swallowed rather
     *  than tearing the loop down -- the next tick simply tries again, and a report that stops
     *  updating is exactly the staleness signal [TelescopePointingSource] watches for. */
    private suspend fun pollPosition(session: Lx200Session) {
        while (true) {
            runCatching {
                val ra = Lx200Codec.parseRightAscension(session.executeHashTerminated(Lx200Codec.getRightAscension()))
                val dec = Lx200Codec.parseDeclination(session.executeHashTerminated(Lx200Codec.getDeclination()))
                _reportedPosition.value = TelescopeReport(EquatorialCoordinates(ra, dec), currentEpochMillis())
            }
            delay(pollIntervalMillis)
        }
    }

    private suspend fun teardown() {
        pollJob?.cancel()
        pollJob = null
        session = null
        transport?.disconnect()
        transport = null
        _reportedPosition.value = null
    }
}
