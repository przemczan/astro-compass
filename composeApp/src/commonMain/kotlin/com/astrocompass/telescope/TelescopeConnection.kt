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

/** Outcome of [TelescopeConnection.syncTo]. Separate from [SlewOutcome] because a refusal carries
 *  no mount-supplied text to show -- OnStepX answers `:CM#` with a bare `E1`..`E9` code, not a
 *  message (see [Lx200Codec.parseSyncAccepted]). */
sealed interface SyncOutcome {
    data object Synced : SyncOutcome
    data object Rejected : SyncOutcome
    data object NoConnection : SyncOutcome
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

    /** Arms an [starCount]-star alignment sequence, returning whether the mount accepted it --
     *  false also when there's no connection. Every [syncTo] after this contributes one point to
     *  the model instead of correcting the pointing origin, until [starCount] of them have landed.
     *
     *  **Destructive and un-abortable**: it resets the mount's home position (so the mount must
     *  physically be at home), discards its current model, and forces tracking on. The protocol
     *  offers no cancel -- see [Lx200Codec.beginAlignment]. */
    suspend fun beginAlignment(starCount: Int): Boolean

    /** Starts moving [direction] at the rate [setMoveRatePreset] last selected, and keeps moving
     *  until the matching [stopMove]. Fire-and-forget: OnStep answers none of the manual-motion
     *  commands, and a no-op without a connection. */
    suspend fun startMove(direction: TelescopeDirection)

    /** Stops the axis [direction] belongs to -- see [Lx200Codec.stopMove] for why this is
     *  per-axis rather than [abortSlew]'s blanket stop. */
    suspend fun stopMove(direction: TelescopeDirection)

    /** Sets the manual-move rate. Independent of [setSlewRatePreset], which is the GOTO speed. */
    suspend fun setMoveRatePreset(preset: MoveRatePreset)

    /** Whether the mount reports itself at its home position, or null with no connection or no
     *  answer. Read on demand, only while [com.astrocompass.ui.screens.AlignmentScreen] is offering
     *  to arm an alignment -- that is the one moment it decides anything, since [beginAlignment]
     *  redefines home as wherever the mount happens to be standing. */
    suspend fun readAtHome(): Boolean?

    /** Slews the mount to its home position. Fire-and-forget, with no success/failure to report --
     *  OnStep answers `:hC#` with nothing at all. A no-op without a connection. */
    suspend fun moveToHome()

    /** Persists a completed alignment model to the mount's non-volatile storage, returning whether
     *  the mount accepted it -- false also when there's no connection. Worth surfacing when it
     *  fails: the model is still live, it just won't survive a power cycle. */
    suspend fun saveAlignmentModel(): Boolean

    /** Tells the mount it is *already* pointed at [target] (of-date, same as [slewTo]). Only
     *  meaningful the instant the user has actually centered the star, which is why the alignment
     *  flow issues it on the confirming tap rather than when the finished model is saved.
     *
     *  Its effect depends on whether [beginAlignment] has armed a sequence: outside one it
     *  corrects the mount's pointing origin, inside one it adds one point to the model being
     *  built. That is one command with two meanings, and deliberate -- see [Lx200Codec.syncToTarget]. */
    suspend fun syncTo(target: EquatorialCoordinates): SyncOutcome

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
