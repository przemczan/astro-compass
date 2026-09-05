package com.astrocompass.guiding

import com.astrocompass.astro.Vector3
import com.astrocompass.astro.time.currentEpochMillis
import com.astrocompass.platesolve.PlateSolveFailureReason
import com.astrocompass.sensors.OrientationSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** How far the telescope may wander and still count as still. Generous relative to the camera's
 *  own field, since what this guards against is a slew or a hand-nudge, not tracking motion. */
private const val MAX_MOVEMENT_DEGREES = 1.0

/** How long it has to stay within [MAX_MOVEMENT_DEGREES] before a photo is worth taking. */
private const val STILLNESS_HOLD_MILLIS = 2_000L

/** How often the stillness test samples the sensor. Well under [STILLNESS_HOLD_MILLIS] so the hold
 *  is measured, not merely sampled at its endpoints. Polled rather than collected because
 *  [kotlinx.coroutines.flow.StateFlow] conflates and drops equal values -- a perfectly still phone
 *  can stop emitting entirely, which is precisely the case this has to detect. */
private const val STILLNESS_POLL_INTERVAL_MILLIS = 250L

/** The gap between one solve finishing and the next stillness test starting. Not a period: a full
 *  cycle is this plus the hold, the exposure, opening the camera, and the solve itself. */
private const val SOLVE_INTERVAL_MILLIS = 5_000L

/** Corrections beyond this are rejected rather than applied -- see [PlateSolveAttempt]'s doc
 *  comment for what [PlateSolveAttempt.correctionDegrees] actually measures (a pure vision-vs-IMU
 *  comparison, **not** sensitive to [CameraMounting]: that transform cancels out of the
 *  computation algebraically, so a wrong preset there can never show up as a large correction
 *  here). What this bound actually catches is a false [PlateSolver] match -- one geometrically
 *  self-consistent enough to pass its own inlier check, but matched against the wrong patch of
 *  sky. A real solve's crosshair position can legitimately land up to (roughly) the catalog
 *  search radius (`AppContainer.PLATE_SOLVE_SEARCH_RADIUS`, 15 degrees) plus half the camera's
 *  own field of view away from wherever the seed (often just the compass, before the first solve
 *  lands) happened to be -- a phone's rear lens is commonly 60-80 degrees diagonal, so half-FOV
 *  alone can plausibly be 30-40 degrees. This is sized generously above that combined bound
 *  (15 + ~35) specifically so an honest, large compass error doesn't get thrown away alongside
 *  the false matches it exists to catch -- a magnetometer error of 20-30+ degrees near a
 *  telescope's own steel and motors is ordinary, not a sign anything is broken. */
private const val MAX_ACCEPTED_CORRECTION_DEGREES = 50.0

/**
 * Keeps the sensors honest with the camera: while guiding a plate-solve setup, this photographs
 * the sky and re-solves it every few seconds the telescope holds still, publishing each solve as
 * the app's absolute reference.
 *
 * Pointing itself still comes from the sensor stream through [PointingService] -- this only
 * replaces the fixed rotation that stream is anchored to, so the on-screen position stays smooth
 * between solves and silently re-truths itself on each one. The user is told nothing, because
 * there is nothing for them to do.
 *
 * It reports [ReferenceOrigin.STAR_ALIGNMENT]: a plate solve is a real 3-DOF fit against real
 * stars, and the distinction that enum draws is between a fit and the compass's guess.
 *
 * Nothing is persisted per solve. [PlateSolveAttempt]s that pass the correction check land here and
 * nowhere else, because writing each one through
 * [com.astrocompass.AppContainer.saveAlignment] would rewrite stored state every few seconds; the
 * container persists the first success alone, purely so the next launch starts warm.
 *
 * [status] and [lastOutcome] exist purely for the app bar's plate-solve indicator -- they play no
 * part in [current]/[AbsoluteReference] and nothing here reads them back.
 */
class AutoPlateSolveRefiner(
    private val scope: CoroutineScope,
    private val orientationSensor: OrientationSensor,
    /** Where the telescope's optical axis points, in device/IMU frame -- see
     *  [PointingService]'s own doc comment on this same parameter for why it's a plain [Vector3]
     *  rather than [TelescopeAxis]. */
    private val boresightDeviceVector: StateFlow<Vector3>,
    private val attemptSolve: suspend () -> PlateSolveOutcome,
    private val onFirstSuccess: (PlateSolveAttempt) -> Unit = {},
    /** Wall clock, injectable so a test can run the loop on virtual time. Never
     *  [com.astrocompass.sensors.DeviceOrientation.timestampMillis] -- see [StillnessTracker]. */
    private val nowEpochMillis: () -> Long = ::currentEpochMillis,
) : AbsoluteReference {
    private val _current = MutableStateFlow<AbsoluteReferenceState?>(null)
    override val current: StateFlow<AbsoluteReferenceState?> = _current

    private val _status = MutableStateFlow(PlateSolveStatus.IDLE)
    val status: StateFlow<PlateSolveStatus> = _status

    /** The most recent attempt's full detail -- null until the first one this run. Kept even while
     *  [status] is [PlateSolveStatus.SOLVING], so a tapped indicator still shows the previous
     *  attempt's numbers rather than going blank mid-capture. */
    private val _lastOutcome = MutableStateFlow<PlateSolveOutcome?>(null)
    val lastOutcome: StateFlow<PlateSolveOutcome?> = _lastOutcome

    private var loop: Job? = null

    /**
     * Starts or stops the solve loop. Owned by this class's own [scope] rather than the caller's,
     * so a screen recomposing -- or being torn down mid-exposure -- can neither cancel an in-flight
     * capture nor leave a second loop running beside the first.
     *
     * Deactivating deliberately keeps the last published reference: the fit it describes is still
     * true after the user leaves the guidance screen, and dropping it would visibly jump every map
     * back to the compass. [status] and [lastOutcome] are kept for the same reason -- except a
     * cancellation mid-[SOLVING][PlateSolveStatus.SOLVING], which has no real outcome to keep and
     * would otherwise show as a stuck "solving now" the next time this reactivates.
     */
    fun setActive(active: Boolean) {
        if (active == (loop?.isActive == true)) return
        loop?.cancel()
        if (!active && _status.value == PlateSolveStatus.SOLVING) _status.value = PlateSolveStatus.IDLE
        loop = if (active) scope.launch { runSolveLoop() } else null
    }

    // Ended by cancellation alone: every iteration suspends in awaitStillness, the solve, or the
    // delay, so setActive(false) lands promptly without a flag to check.
    private suspend fun runSolveLoop() {
        val stillness = StillnessTracker(MAX_MOVEMENT_DEGREES, STILLNESS_HOLD_MILLIS)
        while (true) {
            awaitStillness(stillness)
            _status.value = PlateSolveStatus.SOLVING
            when (val outcome = attemptSolve()) {
                is PlateSolveOutcome.Success -> {
                    if (outcome.attempt.correctionDegrees <= MAX_ACCEPTED_CORRECTION_DEGREES) {
                        publish(outcome.attempt)
                        _lastOutcome.value = outcome
                        _status.value = PlateSolveStatus.SUCCEEDED
                    } else {
                        _lastOutcome.value = PlateSolveOutcome.Failure(
                            PlateSolveFailureReason.CORRECTION_TOO_LARGE,
                            outcome.attempt.diagnostics,
                            correctionDegrees = outcome.attempt.correctionDegrees,
                        )
                        _status.value = PlateSolveStatus.FAILED
                    }
                }
                is PlateSolveOutcome.Failure -> {
                    _lastOutcome.value = outcome
                    _status.value = PlateSolveStatus.FAILED
                }
            }
            // Whatever the outcome, the telescope may well have been moved during the solve -- and
            // has certainly been still throughout it, which is not evidence about the next frame.
            stillness.reset()
            delay(SOLVE_INTERVAL_MILLIS)
        }
    }

    private suspend fun awaitStillness(stillness: StillnessTracker) {
        while (true) {
            val orientation = orientationSensor.orientation.value
            val direction = orientation?.deviceToWorld?.rotate(boresightDeviceVector.value)
            if (direction != null && stillness.update(direction, nowEpochMillis())) return
            delay(STILLNESS_POLL_INTERVAL_MILLIS)
        }
    }

    private fun publish(attempt: PlateSolveAttempt) {
        if (_current.value == null) onFirstSuccess(attempt)
        _current.value = AbsoluteReferenceState(
            sensorToSky = attempt.correctedModel.sensorToSky,
            establishedAtEpochMillis = attempt.correctedModel.computedAtEpochMillis,
            uncertaintyDegrees = attempt.correctedModel.rmsResidualDegrees,
            origin = ReferenceOrigin.STAR_ALIGNMENT,
        )
    }
}
