package com.astrocompass.alignment

import com.astrocompass.astro.Angle
import com.astrocompass.astro.Quaternion
import com.astrocompass.astro.Vector3
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ground truth here is the definition of east-positive declination itself ("magnetic north lies
 *  D degrees east of true north"), plus synthetic rotations whose answer is known by
 *  construction -- never a recomputation using [CompassAlignment]'s own formula. */
class CompassAlignmentTest {

    /** Azimuth measured the same way the ENU frame defines it: clockwise from +Y (north). */
    private fun azimuthOf(direction: Vector3): Double =
        Angle.ofRadians(atan2(direction.x, direction.y)).normalized().degrees

    private fun yawRotation(degrees: Double) =
        Quaternion.fromAxisAngle(Vector3.UNIT_Z, Angle.ofDegrees(degrees))

    /** The sign test. With the sensor frame already *being* the magnetic frame, a telescope
     *  pointing along the frame's north axis points at magnetic north -- which, with 10 degrees
     *  of east declination, is true azimuth +10, not -10. */
    @Test
    fun eastDeclination_putsMagneticNorthEastOfTrueNorth() {
        val sensorToSky = CompassAlignment.sensorToSky(
            sensorDeviceToWorld = Quaternion.IDENTITY,
            magneticDeviceToWorld = Quaternion.IDENTITY,
            declination = Angle.ofDegrees(10.0),
        )

        assertEquals(10.0, azimuthOf(sensorToSky.rotate(Vector3.UNIT_Y)), 1e-9)
    }

    @Test
    fun westDeclination_putsMagneticNorthWestOfTrueNorth() {
        val sensorToSky = CompassAlignment.sensorToSky(
            sensorDeviceToWorld = Quaternion.IDENTITY,
            magneticDeviceToWorld = Quaternion.IDENTITY,
            declination = Angle.ofDegrees(-6.0),
        )

        assertEquals(354.0, azimuthOf(sensorToSky.rotate(Vector3.UNIT_Y)), 1e-9)
    }

    /** The sensor frame's yaw reference is arbitrary -- that is the whole reason this class
     *  exists -- so recovering it from the two streams' disagreement is the other half of the
     *  job, on top of declination. */
    @Test
    fun arbitrarySensorYaw_isRecoveredOnTopOfDeclination() {
        // The two streams describe the same device attitude 30 degrees of yaw apart, which places
        // the sensor frame's north axis at magnetic azimuth -30, hence true azimuth -30 + 10.
        val sensorToSky = CompassAlignment.sensorToSky(
            sensorDeviceToWorld = yawRotation(70.0),
            magneticDeviceToWorld = yawRotation(100.0),
            declination = Angle.ofDegrees(10.0),
        )

        assertEquals(340.0, azimuthOf(sensorToSky.rotate(Vector3.UNIT_Y)), 1e-9)
    }

    /** Tilt disagreement between the two composite sensors is discarded rather than fitted: the
     *  result stays a pure yaw, so "up" in the sensor frame is still "up" in the sky frame. */
    @Test
    fun tiltDisagreementBetweenStreams_leavesTheUpAxisAlone() {
        val tilted = Quaternion.fromAxisAngle(Vector3.UNIT_X, Angle.ofDegrees(4.0))

        val sensorToSky = CompassAlignment.sensorToSky(
            sensorDeviceToWorld = Quaternion.IDENTITY,
            magneticDeviceToWorld = tilted,
            declination = Angle.ZERO,
        )

        val up = sensorToSky.rotate(Vector3.UNIT_Z)
        assertEquals(0.0, up.angleTo(Vector3.UNIT_Z).degrees, 1e-9)
    }
}
