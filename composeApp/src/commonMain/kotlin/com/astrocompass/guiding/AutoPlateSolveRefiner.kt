package com.astrocompass.guiding

import com.astrocompass.astro.time.currentEpochMillis
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

/** Corrections beyond this are rejected rather than applied. A solve only searches
 *  `PLATE_SOLVE_SEARCH_RADIUS` around where the app already thinks it points, so it cannot
 *  legitimately claim to have found something further away than that; a "solve" that does is a
 *  false match, or the wrong [CameraMounting] preset. Nobody is watching to catch either one here,
 *  which is why the bound exists at all. */
private const val MAX_ACCEPTED_CORRECTION_DEGREES = 15.0

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
 */
class AutoPlateSolveRefiner(
    private val scope: CoroutineScope,
    private val orientationSensor: OrientationSensor,
    private val telescopeAxis: StateFlow<TelescopeAxis>,
    private val attemptSolve: suspend () -> PlateSolveAttempt?,
    private val onFirstSuccess: (PlateSolveAttempt) -> Unit = {},
    /** Wall clock, injectable so a test can run the loop on virtual time. Never
     *  [com.astrocompass.sensors.DeviceOrientation.timestampMillis] -- see [StillnessTracker]. */
    private val nowEpochMillis: () -> Long = ::currentEpochMillis,
) : AbsoluteReference {
    private val _current = MutableStateFlow<AbsoluteReferenceState?>(null)
    override val current: StateFlow<AbsoluteReferenceState?> = _current

    private var loop: Job? = null

    /**
     * Starts or stops the solve loop. Owned by this class's own [scope] rather than the caller's,
     * so a screen recomposing -- or being torn down mid-exposure -- can neither cancel an in-flight
     * capture nor leave a second loop running beside the first.
     *
     * Deactivating deliberately keeps the last published reference: the fit it describes is still
     * true after the user leaves the guidance screen, and dropping it would visibly jump every map
     * back to the compass.
     */
    fun setActive(active: Boolean) {
        if (active == (loop?.isActive == true)) return
        loop?.cancel()
        loop = if (active) scope.launch { runSolveLoop() } else null
    }

    // Ended by cancellation alone: every iteration suspends in awaitStillness, the solve, or the
    // delay, so setActive(false) lands promptly without a flag to check.
    private suspend fun runSolveLoop() {
        val stillness = StillnessTracker(MAX_MOVEMENT_DEGREES, STILLNESS_HOLD_MILLIS)
        while (true) {
            awaitStillness(stillness)
            attemptSolve()?.takeIf { it.correctionDegrees <= MAX_ACCEPTED_CORRECTION_DEGREES }?.let(::publish)
            // Whatever the outcome, the telescope may well have been moved during the solve -- and
            // has certainly been still throughout it, which is not evidence about the next frame.
            stillness.reset()
            delay(SOLVE_INTERVAL_MILLIS)
        }
    }

    private suspend fun awaitStillness(stillness: StillnessTracker) {
        while (true) {
            val orientation = orientationSensor.orientation.value
            val direction = orientation?.deviceToWorld?.rotate(telescopeAxis.value.deviceVector)
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
