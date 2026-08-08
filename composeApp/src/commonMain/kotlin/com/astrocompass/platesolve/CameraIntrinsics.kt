package com.astrocompass.platesolve

import com.astrocompass.astro.Vector3

/**
 * Pinhole camera model: focal length and principal point, all in pixels. Must come from the
 * camera's own reported calibration (e.g. Android's `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` +
 * `SENSOR_INFO_PHYSICAL_SIZE`) -- an assumed/hardcoded focal length would leave the projection's
 * scale unknown and matching could never converge.
 */
data class CameraIntrinsics(val focalLengthPx: Double, val principalPointX: Double, val principalPointY: Double) {

    /**
     * Unit vector in this camera's own local frame for the ray through pixel ([px], [py]).
     *
     * This frame is purely local and self-consistent: (0, 0, 1) is defined as the
     * principal-point (frame-center) direction, and nothing about its axes needs to correspond
     * to true device or sky axes. [PlateSolver] only ever compares directions computed with this
     * same convention against each other (or against catalog directions, via the fitted
     * rotation) -- never against a raw device-frame vector -- which is what lets plate-solving
     * avoid depending on the camera's physical mounting orientation within the phone.
     */
    fun pixelToDirection(px: Double, py: Double): Vector3 = Vector3(
        x = (px - principalPointX) / focalLengthPx,
        y = (principalPointY - py) / focalLengthPx,
        z = 1.0,
    ).normalized()
}
