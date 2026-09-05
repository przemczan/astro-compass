package com.astrocompass.guiding

import com.astrocompass.platesolve.PlateSolveDiagnostics
import com.astrocompass.platesolve.PlateSolveFailureReason

/** [com.astrocompass.AppContainer.attemptPlateSolve]'s result, success or failure -- unlike a plain
 *  nullable [PlateSolveAttempt], a [Failure] always says *why*, so [AutoPlateSolveRefiner] (and, in
 *  turn, the app bar's plate-solve indicator) has something more useful to show than "nothing
 *  happened". */
sealed interface PlateSolveOutcome {
    data class Success(val attempt: PlateSolveAttempt) : PlateSolveOutcome
    data class Failure(
        val reason: PlateSolveFailureReason,
        val diagnostics: PlateSolveDiagnostics = PlateSolveDiagnostics(),
        /** Only set for [PlateSolveFailureReason.CORRECTION_TOO_LARGE] -- the correction that was
         *  computed and then rejected. */
        val correctionDegrees: Double? = null,
    ) : PlateSolveOutcome
}

/** How the background plate solver stands right now, for the app bar's status dot: grey before any
 *  attempt this run, blue while a capture/solve is in flight, green/red after the most recent one.
 *  Deliberately not derived from [PlateSolveOutcome] alone -- SOLVING has no outcome yet. */
enum class PlateSolveStatus {
    IDLE,
    SOLVING,
    SUCCEEDED,
    FAILED,
}
