package com.astrocompass.platesolve

import com.astrocompass.astro.Vector3

/** One successfully matched star from a solve: its detected direction in the camera's own local
 *  frame (see [CameraIntrinsics]' doc comment), paired with the catalog star it was identified
 *  as. Lets a caller re-derive a rotation in a different reference frame (e.g. ENU-of-date, for
 *  [com.astrocompass.alignment.PlateSolveAlignment]) without re-running the matching search. */
data class MatchedStar(val imageDirection: Vector3, val referenceStar: ReferenceStar)
