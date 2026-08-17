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

/** One step of the mount-sync sequence [Lx200TelescopeConnection.connect] runs automatically after
 *  a successful connect (see [TelescopeConnection.mountSyncResults]). [MountSyncStepOutcome.Skipped]
 *  is distinct from [MountSyncStepOutcome.Failed]: it means the step was deliberately not
 *  attempted, not that the mount rejected it -- see [TRACKING]'s doc for the one step that is. */
enum class MountSyncStep {
    /** Runs first, and every later step is [MountSyncStepOutcome.Skipped] if it fails: a single
     *  parameter-free `:GR#` query, proving the mount actually speaks LX200 on this link before any
     *  Set-command's wire format can be blamed for a silent mount. A transport can report
     *  [TelescopeTransportState.CONNECTED] on a link that carries no LX200 at all (an RFCOMM socket
     *  opens against any SPP device, at any baud, whether or not a controller is listening on the
     *  other side), so without this the first failure surfaces as a confusing "Mount rejected time
     *  sync" -- and, because one timed-out read must close the socket to unblock at all (see
     *  [Lx200Session.readWithTimeout]), as a cascade of further failures against a dead socket
     *  behind it. */
    LINK,

    TIME, SITE, UNPARK,

    /** Always [MountSyncStepOutcome.Skipped], never attempted: OnStep's own tracking-rate command
     *  (`:ST[Hz]#`) takes a raw stepper-timer frequency with no mount-independent "sidereal"
     *  constant to send safely -- guessing one risks silently mistracking rather than failing
     *  loudly. Unpark on OnStep already resumes whatever tracking state was active when the mount
     *  was parked, so [UNPARK] carries this instead. */
    TRACKING,
}

sealed interface MountSyncStepOutcome {
    data object Success : MountSyncStepOutcome
    data class Failed(val reason: String) : MountSyncStepOutcome
    data class Skipped(val reason: String) : MountSyncStepOutcome
}

data class MountSyncStepResult(val step: MountSyncStep, val outcome: MountSyncStepOutcome)

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

    /**
     * Fills in step-by-step as [connect]'s automatic mount-sync sequence runs (time, site,
     * unpark -- see [Lx200TelescopeConnection]'s class doc); empty only before that sequence has
     * ever run, reset to empty by [connect]/[disconnect] before it runs again. A successful
     * [connect] always ends with every [MountSyncStep] entry present, even without a known
     * location yet -- [MountSyncStep.SITE] alone reports [MountSyncStepOutcome.Skipped] rather
     * than the whole sequence silently not running. Purely informational for
     * [com.astrocompass.ui.screens.TelescopeScreen] -- nothing in the app gates on this, and it
     * never blocks or reverses [state]: an already-unparked mount rejecting [MountSyncStep.UNPARK]
     * is routine, not an error. Tracking-enable is deliberately [MountSyncStepOutcome.Skipped]
     * rather than attempted -- see [MountSyncStep.TRACKING]'s doc.
     */
    val mountSyncResults: StateFlow<List<MountSyncStepResult>>

    suspend fun connect(endpoint: TelescopeEndpoint)
    suspend fun disconnect()

    /** [target] is expected to already be of-date (JNow) -- see [TelescopeReport]'s note; every
     *  caller today gets this for free from [com.astrocompass.catalog.SkyObject.equatorialAt]. */
    suspend fun slewTo(target: EquatorialCoordinates): SlewOutcome
    suspend fun abortSlew()

    /** Sets the mount's GOTO speed. Fire-and-forget, with no success/failure to report: OnStep
     *  answers this command with nothing at all, and silently ignores it while a slew or guide is
     *  already running -- see [Lx200Codec.setSlewRatePreset]. A no-op without a connection, so
     *  [com.astrocompass.AppContainer] can push the stored preference at it unconditionally. */
    suspend fun setSlewRatePreset(preset: SlewRatePreset)

    /** Starts/stops sidereal tracking, returning whether the mount accepted it -- false also when
     *  there's no connection. OnStep refuses to *start* tracking on a parked mount, which is the
     *  rejection worth surfacing to the user. */
    suspend fun setTracking(enabled: Boolean): Boolean

    /** The mount's current tracking state, read on demand rather than polled -- only
     *  [com.astrocompass.ui.components.TelescopeOptionsSheet] needs it, and only while it's open,
     *  so there's nothing to gain from adding a second periodic command alongside the position
     *  poll. Null when there's no connection or the mount didn't answer. */
    suspend fun readTrackingEnabled(): Boolean?
}
