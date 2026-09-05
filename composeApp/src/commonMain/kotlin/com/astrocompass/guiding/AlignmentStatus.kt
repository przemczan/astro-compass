package com.astrocompass.guiding

import com.astrocompass.alignment.AlignmentType

/**
 * Where the phone's alignment stands right now, folding together what the user set up
 * ([AlignmentType], from the wizard) with what's actually driving pointing this moment
 * ([ReferenceOrigin], from [PointingService]'s live [AbsoluteReferenceState]).
 *
 * The two legitimately disagree in one case: [AlignmentType.PLATE_SOLVE] establishes no sky
 * reference in the wizard at all (see its own doc comment) -- pointing runs on the compass
 * fallback until [AutoPlateSolveRefiner] lands its first live solve, even though the camera
 * itself is already fully calibrated. A screen that only checked "is the live origin a star fit"
 * read that gap as "not calibrated" and told a user who had just finished the camera wizard to go
 * calibrate again.
 */
enum class AlignmentStatus {
    /** No calibration has ever been completed (or a [AlignmentType.SENSORS_ONLY] model went
     *  missing some other way) -- the wizard needs running. */
    NOT_CALIBRATED,

    /** A camera setup is calibrated and its background solver is (or will be, once Guidance opens)
     *  running, but no fix has landed yet this run -- pointing is on the compass fallback in the
     *  meantime. Nothing to fix: it resolves itself once the telescope holds still long enough for
     *  one solve. */
    AWAITING_FIRST_PLATE_SOLVE,

    /** A real fit -- a star alignment, or a landed plate solve -- is in effect. */
    CALIBRATED,
}

fun alignmentStatus(alignmentType: AlignmentType?, referenceOrigin: ReferenceOrigin?): AlignmentStatus = when {
    referenceOrigin == ReferenceOrigin.STAR_ALIGNMENT -> AlignmentStatus.CALIBRATED
    alignmentType == AlignmentType.PLATE_SOLVE -> AlignmentStatus.AWAITING_FIRST_PLATE_SOLVE
    else -> AlignmentStatus.NOT_CALIBRATED
}
