package com.astrocompass.guiding

import com.astrocompass.alignment.AlignmentModel
import com.astrocompass.platesolve.PlateSolveDiagnostics
import com.astrocompass.platesolve.PlateSolveResult

/**
 * A completed plate solve. [correctedModel] and [correctionDegrees] are computed once, at capture
 * time (from the orientation sensor reading and clock time actually true at the shutter -- not
 * re-read later, the same invariant [com.astrocompass.alignment.AlignmentPoint] enforces for manual
 * syncs), so what a caller inspects is exactly what applying it would do -- no risk of it silently
 * changing in between.
 *
 * [correctionDegrees] is the angle between where the app currently thinks the telescope points
 * (from the sensor stream/compass) and where this solve's own star match independently says the
 * crosshair points -- a pure vision-vs-IMU comparison. **It is not sensitive to [CameraMounting]
 * at all**: [correctedModel] is built with `cameraToDevice.conjugate()` and this angle is computed
 * by re-applying `cameraToDevice` to the same capture's orientation reading, so the two cancel
 * algebraically and [CameraMounting] drops out of the comparison entirely, regardless of whether
 * it's right or wrong. What a large value here actually means is either a false star match, or --
 * just as often, and not a fault -- a seed (typically the compass, before the first solve lands)
 * that was simply wrong by that much; magnetometer error of 20-30°+ near a telescope's own steel
 * and motors is ordinary, not exceptional. [AutoPlateSolveRefiner] bounds this against
 * `MAX_ACCEPTED_CORRECTION_DEGREES` to reject an implausible false match, sized from the search
 * geometry (see that constant's own doc comment) rather than from any assumption about compass
 * accuracy or camera mounting.
 *
 * [diagnostics] carries the detection/candidate/match star counts behind a *successful* solve --
 * useful for judging exposure/ISO headroom even when a solve landed, not just for explaining a
 * [PlateSolveOutcome.Failure].
 */
data class PlateSolveAttempt(
    val result: PlateSolveResult,
    val correctedModel: AlignmentModel,
    val correctionDegrees: Double,
    val diagnostics: PlateSolveDiagnostics = PlateSolveDiagnostics(),
)
