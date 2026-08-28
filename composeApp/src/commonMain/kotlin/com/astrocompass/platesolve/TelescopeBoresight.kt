package com.astrocompass.platesolve

/**
 * Where the telescope's optical axis falls within the camera's frame, as fractions of the
 * *upright* [CapturedFrame] width/height (0.5, 0.5 = dead center) -- set once by
 * [com.astrocompass.ui.screens.PhoneCalibrationScreen] and read back by a future plate-solve
 * consumer.
 *
 * Deliberately not [CameraIntrinsics.principalPointX]/[CameraIntrinsics.principalPointY]: that
 * pair is the lens' own optical center, which [CameraIntrinsics.pixelToDirection] uses to build
 * every star ray that [com.astrocompass.alignment.PlateSolveAlignment] fits a rotation from --
 * overwriting it with a mechanical boresight offset would tilt the whole projection rather than
 * just recording where the telescope points within it.
 *
 * Captured in whatever image space the wizard's preview showed at the time -- if
 * [com.astrocompass.settings.AppPreferences.phoneCalibrationUsesMirror] is set, that's *mirrored*
 * image coordinates. A future consumer that un-mirrors the captured luminance before matching must
 * un-mirror this the same way (`xFraction -> 1 - xFraction`) or the boresight ends up on the wrong
 * side of the frame.
 */
data class TelescopeBoresight(val xFraction: Float, val yFraction: Float)
