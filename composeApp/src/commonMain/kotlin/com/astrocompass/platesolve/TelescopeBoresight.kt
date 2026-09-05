package com.astrocompass.platesolve

/**
 * Where the telescope's optical axis falls within the camera's frame, as fractions of the
 * *upright* [CapturedFrame] width/height (0.5, 0.5 = dead center) -- set once by the alignment
 * wizard's camera-calibration branch and read back by a future plate-solve consumer.
 *
 * Deliberately not [CameraIntrinsics.principalPointX]/[CameraIntrinsics.principalPointY]: that
 * pair is the lens' own optical center, which [CameraIntrinsics.pixelToDirection] uses to build
 * every star ray that [com.astrocompass.alignment.PlateSolveAlignment] fits a rotation from --
 * overwriting it with a mechanical boresight offset would tilt the whole projection rather than
 * just recording where the telescope points within it.
 *
 * Recorded in the un-mirrored image space the calibration preview shows, which is the same space
 * [CapturedFrame] arrives in -- a setup that folds its optical path through a mirror flips both
 * alike, so nothing here needs to know about one.
 */
data class TelescopeBoresight(val xFraction: Float, val yFraction: Float)
