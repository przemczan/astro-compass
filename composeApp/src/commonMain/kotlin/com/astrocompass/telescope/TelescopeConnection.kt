package com.astrocompass.telescope

import com.astrocompass.astro.coords.EquatorialCoordinates
import kotlinx.coroutines.flow.StateFlow

enum class TelescopeTransportKind { TCP, BLUETOOTH_CLASSIC }

/** Where to connect. Exactly one of ([host], [port]) or [bluetoothAddress] is populated,
 *  matching [kind]. */
data class TelescopeEndpoint(
    val kind: TelescopeTransportKind,
    val displayName: String,
    val host: String? = null,
    val port: Int? = null,
    val bluetoothAddress: String? = null,
)

sealed interface TelescopeConnectionState {
    data object Disconnected : TelescopeConnectionState
    data class Connecting(val endpoint: TelescopeEndpoint) : TelescopeConnectionState
    data class Connected(val endpoint: TelescopeEndpoint) : TelescopeConnectionState
    data class Failed(val endpoint: TelescopeEndpoint, val reason: String) : TelescopeConnectionState
}

/** The mount's self-reported position, polled at roughly 1 Hz. Assumed JNow (of-date) --
 *  see the telescope connectivity plan's coordinate-handling section: [SkyObject.equatorialAt][
 *  com.astrocompass.catalog.SkyObject.equatorialAt] already returns of-date coordinates too, so
 *  this converts straight to horizontal with no precession step in either direction, v1 has no
 *  way to detect a mount running in J2000 mode instead. */
data class TelescopeReport(val equatorialJNow: EquatorialCoordinates, val epochMillis: Long)

sealed interface SlewOutcome {
    data object Started : SlewOutcome
    data class Rejected(val reason: String) : SlewOutcome
    data object NoConnection : SlewOutcome
}

/**
 * What [com.astrocompass.AppContainer] and the UI consume -- a mount's live reported position
 * plus the ability to command it to slew. [Lx200TelescopeConnection] is the only implementation,
 * built on [Lx200Session] over either a TCP or a Bluetooth Classic SPP [TelescopeTransport].
 */
interface TelescopeConnection {
    val state: StateFlow<TelescopeConnectionState>

    /** Null before the first successful poll read after connecting. Staleness (a report that
     *  stopped updating without the connection itself dropping) is [TelescopePointingSource]'s
     *  concern, not this interface's -- this is always just the raw last-known report. */
    val reportedPosition: StateFlow<TelescopeReport?>

    suspend fun connect(endpoint: TelescopeEndpoint)
    suspend fun disconnect()

    /** [target] is expected to already be of-date (JNow) -- see [TelescopeReport]'s note; every
     *  caller today gets this for free from [com.astrocompass.catalog.SkyObject.equatorialAt]. */
    suspend fun slewTo(target: EquatorialCoordinates): SlewOutcome
    suspend fun abortSlew()
}
