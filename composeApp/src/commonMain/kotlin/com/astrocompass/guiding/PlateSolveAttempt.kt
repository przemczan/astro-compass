package com.astrocompass.guiding

import com.astrocompass.alignment.AlignmentModel
import com.astrocompass.platesolve.PlateSolveResult

/**
 * A completed plate solve, still awaiting the user's confirmation before it's saved as the new
 * alignment. [correctedModel] and [correctionDegrees] are computed once, at capture time (from
 * the orientation sensor reading and clock time actually true at the shutter -- not re-read
 * later, the same invariant [com.astrocompass.alignment.AlignmentPoint] enforces for manual
 * syncs), so the Result card the user reviews is exactly what gets applied -- no risk of it
 * silently changing between review and confirmation.
 *
 * [correctionDegrees] is the angle between where the app currently thinks the telescope points
 * and where [correctedModel] would put it -- the practical signal for whether this solve (and the
 * configured [CameraMounting]) is trustworthy: a plausible drift correction is a few degrees, a
 * wrong camera-mounting preset is typically tens of degrees.
 */
data class PlateSolveAttempt(
    val result: PlateSolveResult,
    val correctedModel: AlignmentModel,
    val correctionDegrees: Double,
)
