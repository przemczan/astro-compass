package com.astrocompass.platesolve

/** Star counts from one solve attempt, carried on both [PlateSolverOutcome.Solved] and
 *  [PlateSolverOutcome.Failed] so a caller can tell, e.g., "17 stars detected but no geometric
 *  match" from "2 stars detected" -- two failures with the same null result but very different
 *  fixes (camera intrinsics/mounting vs. exposure). */
data class PlateSolveDiagnostics(
    val detectionCount: Int = 0,
    val candidateCount: Int = 0,
    val matchedStarCount: Int = 0,
)

/** Every reason a plate-solve attempt can come back without a usable fit, from the outermost
 *  prerequisite check down to the geometric match itself -- see [PlateSolver.solve] for the three
 *  it raises directly, and [com.astrocompass.AppContainer.attemptPlateSolve] /
 *  [com.astrocompass.guiding.AutoPlateSolveRefiner] for the rest. */
enum class PlateSolveFailureReason {
    /** No pointing direction yet to seed the catalog search around. */
    NO_POINTING_REFERENCE,

    /** No observer location set. */
    NO_LOCATION,

    /** [CameraCapture.captureFrame] returned null -- see its own platform logs (e.g. logcat tag
     *  "PlateSolveCamera" on Android) for the underlying cause. */
    CAMERA_CAPTURE_FAILED,

    /** The orientation sensor has no reading yet. */
    ORIENTATION_UNAVAILABLE,

    /** Fewer star-like blobs were detected in the frame than [PlateSolver] needs to even attempt a
     *  match -- most often too short an exposure, too high an ISO's noise floor, or a badly
     *  out-of-focus lens for the sky's actual brightness. */
    TOO_FEW_DETECTIONS,

    /** Fewer catalog stars fall within the search radius of the seed direction than [PlateSolver]
     *  needs -- a sparse sky region, or a seed direction far enough off that the real field isn't
     *  being searched at all. */
    TOO_FEW_CANDIDATES,

    /** Both detections and candidates existed but no geometric hypothesis matched enough of them --
     *  usually wrong camera intrinsics/mounting rather than a exposure problem. */
    NO_GEOMETRIC_MATCH,

    /** Stars matched, but deriving the sensor-to-sky attitude from them failed. */
    ALIGNMENT_FIT_FAILED,

    /** The whole attempt exceeded its time budget before finishing. */
    TIMEOUT,

    /** A solve landed but implied a correction beyond what a legitimate drift could produce --
     *  rejected rather than applied. See `MAX_ACCEPTED_CORRECTION_DEGREES`. */
    CORRECTION_TOO_LARGE,
}
