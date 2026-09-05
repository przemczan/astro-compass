package com.astrocompass.alignment

import com.astrocompass.astro.Angle
import com.astrocompass.astro.Quaternion
import com.astrocompass.astro.Vector3
import kotlin.math.atan2

/**
 * Derives a `sensorToSky` rotation from the magnetometer alone, with no star syncs at all -- the
 * rough fallback that lets the app point somewhere approximately right before the user has
 * aligned anything.
 *
 * Unlike [AlignmentSolver] and [PlateSolveAlignment] this returns a bare [Quaternion] rather than
 * an [AlignmentModel]: there are no points behind it, no residual to report, and nothing here
 * should ever be persisted as an alignment. It is recomputed live from the sensors by
 * [com.astrocompass.guiding.CompassAbsoluteReference] instead.
 *
 * **What it cannot do**: the result is a pure yaw, so it corrects only *which way is north*. A
 * 2-3 star fit additionally absorbs the phone-to-telescope mounting offset (see [AlignmentSolver]);
 * nothing absorbs it here, so a phone mounted a few degrees off in pitch stays that far off in
 * altitude for as long as compass mode is the active reference.
 */
object CompassAlignment {

    /**
     * [sensorDeviceToWorld] and [magneticDeviceToWorld] must be simultaneous readings of the same
     * device attitude, expressed in the active sensor's frame and in the magnetometer-referenced
     * frame respectively. [declination] is east-positive.
     */
    fun sensorToSky(
        sensorDeviceToWorld: Quaternion,
        magneticDeviceToWorld: Quaternion,
        declination: Angle,
    ): Quaternion {
        val sensorToMagnetic = magneticDeviceToWorld * sensorDeviceToWorld.conjugate()
        return Quaternion.fromAxisAngle(Vector3.UNIT_Z, yawOf(sensorToMagnetic) - declination)
    }

    /** The horizontal component of [rotation], as a rotation angle about +Z. Both frames involved
     *  are gravity-referenced, so the true relationship between them is a pure yaw and any tilt
     *  in [rotation] is disagreement between two composite sensors -- noise, not signal, and
     *  deliberately discarded. The [atan2] is negated because a +phi rotation about +Z
     *  (right-hand rule) *decreases* atan2(x, y) by phi. */
    private fun yawOf(rotation: Quaternion): Angle {
        val north = rotation.rotate(Vector3.UNIT_Y)
        return Angle.ofRadians(-atan2(north.x, north.y))
    }
}
