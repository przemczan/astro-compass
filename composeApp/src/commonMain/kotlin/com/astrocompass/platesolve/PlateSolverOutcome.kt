package com.astrocompass.platesolve

/** [PlateSolver.solve]'s result, success or failure -- always carries [PlateSolveDiagnostics] so a
 *  caller can log or display *why* a failure happened, not just that it did. */
sealed interface PlateSolverOutcome {
    data class Solved(val result: PlateSolveResult, val diagnostics: PlateSolveDiagnostics) : PlateSolverOutcome
    data class Failed(val reason: PlateSolveFailureReason, val diagnostics: PlateSolveDiagnostics) : PlateSolverOutcome
}
