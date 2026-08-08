package com.astrocompass.platesolve

import com.astrocompass.astro.coords.EquatorialCoordinates

/** Result of a successful plate solve: the true sky coordinate under the frame's principal
 *  point (the "crosshair"), plus fit-quality metrics and the matched stars themselves --
 *  [matchedStars] lets a caller re-derive a rotation in a different reference frame (see
 *  [com.astrocompass.alignment.PlateSolveAlignment]) without re-running the matching search. */
data class PlateSolveResult(
    val centerEquatorial: EquatorialCoordinates,
    val matchedStarCount: Int,
    val rmsResidualDegrees: Double,
    val matchedStars: List<MatchedStar>,
)
