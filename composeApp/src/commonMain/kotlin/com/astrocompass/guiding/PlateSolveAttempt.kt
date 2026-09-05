package com.astrocompass.guiding

import com.astrocompass.alignment.AlignmentModel
import com.astrocompass.platesolve.PlateSolveResult

/**
 * A completed plate solve. [correctedModel] and [correctionDegrees] are computed once, at capture
 * time (from the orientation sensor reading and clock time actually true at the shutter -- not
 * re-read later, the same invariant [com.astrocompass.alignment.AlignmentPoint] enforces for manual
 * syncs), so what a caller inspects is exactly what applying it would do -- no risk of it silently
 * changing in between.
 *
 * [correctionDegrees] is the angle between where the app currently thinks the telescope points
 * and where [correctedModel] would put it -- the practical signal for whether this solve (and the
 * configured [CameraMounting]) is trustworthy: a plausible drift correction is a few degrees, a
 * wrong camera-mounting preset is typically tens of degrees. The user-initiated solve puts that
 * number in front of the user before anything is applied; the background refiner has no user to
 * ask, and bounds it against its own threshold instead (see [AutoPlateSolveRefiner]).
 */
data class PlateSolveAttempt(
    val result: PlateSolveResult,
    val correctedModel: AlignmentModel,
    val correctionDegrees: Double,
)
