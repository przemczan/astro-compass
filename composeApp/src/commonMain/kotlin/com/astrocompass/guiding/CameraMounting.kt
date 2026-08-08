package com.astrocompass.guiding

import com.astrocompass.astro.Angle
import com.astrocompass.astro.Quaternion
import com.astrocompass.astro.Vector3

/** One self-consistent (proper-rotation) mapping of boresight -> `BACK_FACE` -- an arbitrary but
 *  valid starting point; the [CameraMounting] entries are this composed with the four in-plane
 *  rotations that are the actual unresolved ambiguity. */
private val BASE_ROTATION = Quaternion.fromAxisAngle(Vector3.UNIT_X, Angle.ofDegrees(180.0))

/**
 * The fixed rotation from the rear camera's own local frame (see
 * [com.astrocompass.platesolve.CameraIntrinsics]' doc comment -- local +Z is the boresight) to the
 * phone's IMU body frame (Android sensor convention, same as [TelescopeAxis]).
 *
 * The boresight direction itself isn't in question -- a rear camera always looks out the back of
 * the phone, [Vector3.UNIT_Z] -> device `BACK_FACE`. What *is* genuinely ambiguous is the 0/90/
 * 180/270 degree rotation left in the image plane after Android's `SENSOR_ORIENTATION` correction
 * is applied -- and unlike [TelescopeAxis], which a 2-3 star fit absorbs almost entirely, a wrong
 * value here is not absorbed by anything: it biases [com.astrocompass.alignment.PlateSolveAlignment]'s
 * result directly, and differently depending on where the phone was pointed. It cannot be derived
 * from reasoning alone (no camera geometry argument distinguishes the four in-plane rotations
 * without an actual device to test against) -- only an on-device check can: point the camera at a
 * known object, plate-solve, and see whether the reported correction is small and plausible or
 * way off. If it's way off, try a different preset here.
 */
enum class CameraMounting(val label: String, val cameraToDevice: Quaternion) {
    ROTATION_0("0°", BASE_ROTATION),
    ROTATION_90("90°", (BASE_ROTATION * Quaternion.fromAxisAngle(Vector3.UNIT_Z, Angle.ofDegrees(90.0))).normalized()),
    ROTATION_180("180°", (BASE_ROTATION * Quaternion.fromAxisAngle(Vector3.UNIT_Z, Angle.ofDegrees(180.0))).normalized()),
    ROTATION_270("270°", (BASE_ROTATION * Quaternion.fromAxisAngle(Vector3.UNIT_Z, Angle.ofDegrees(270.0))).normalized());

    companion object {
        val DEFAULT = ROTATION_0
    }
}
