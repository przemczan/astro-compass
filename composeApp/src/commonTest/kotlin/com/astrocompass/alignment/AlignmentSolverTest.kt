package com.astrocompass.alignment

import com.astrocompass.astro.Angle
import com.astrocompass.astro.Quaternion
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.coords.HorizontalCoordinates
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AlignmentSolverTest {

    private fun sky(azDeg: Double, altDeg: Double): Vector3 =
        HorizontalCoordinates(Angle.ofDegrees(azDeg), Angle.ofDegrees(altDeg)).toEnu()

    /** Same rotation iff the quaternions are equal up to sign (unit quaternions double-cover
     *  rotations: q and -q represent the identical rotation). */
    private fun assertSameRotation(expected: Quaternion, actual: Quaternion, toleranceDegrees: Double = 0.05) {
        val dot = expected.w * actual.w + expected.x * actual.x + expected.y * actual.y + expected.z * actual.z
        val angleBetween = kotlin.math.acos(kotlin.math.abs(dot).coerceIn(-1.0, 1.0)) * 2 * 180.0 / kotlin.math.PI
        assertTrue(angleBetween < toleranceDegrees, "Rotations differ by $angleBetween degrees")
    }

    private fun syntheticPoint(trueRotation: Quaternion, skyDir: Vector3, targetId: String, noise: Quaternion = Quaternion.IDENTITY) =
        AlignmentPoint(
            skyDirection = skyDir,
            sensorDirection = noise.rotate(trueRotation.conjugate().rotate(skyDir)),
            capturedAtEpochMillis = 0L,
            targetId = targetId,
            source = AlignmentSource.MANUAL_SYNC,
        )

    @Test
    fun singlePoint_isRejected() {
        val trueRotation = Quaternion.fromAxisAngle(Vector3.UNIT_Z, Angle.ofDegrees(17.0))
        val point = syntheticPoint(trueRotation, sky(azDeg = 45.0, altDeg = 50.0), "star1")

        val result = AlignmentSolver.solve(listOf(point), nowEpochMillis = 0L)
        assertIs<AlignmentResult.Failure>(result)
    }

    @Test
    fun twoStars_recoverAnArbitraryFullRotation() {
        // A rotation that is not pure yaw -- only a full 3-DOF fit can find this.
        val trueRotation = Quaternion.fromAxisAngle(Vector3(1.0, 0.5, 0.3), Angle.ofDegrees(22.0)).normalized()
        val points = listOf(
            syntheticPoint(trueRotation, sky(azDeg = 20.0, altDeg = 60.0), "star1"),
            syntheticPoint(trueRotation, sky(azDeg = 150.0, altDeg = 35.0), "star2"),
        )

        val result = AlignmentSolver.solve(points, nowEpochMillis = 0L)
        assertIs<AlignmentResult.Success>(result)
        assertSameRotation(trueRotation, result.model.sensorToSky)
        assertTrue(result.model.rmsResidualDegrees < 0.01)
    }

    @Test
    fun threeStars_recoverAnArbitraryFullRotation() {
        val trueRotation = Quaternion.fromAxisAngle(Vector3(0.2, -0.8, 0.4), Angle.ofDegrees(-35.0)).normalized()
        val points = listOf(
            syntheticPoint(trueRotation, sky(azDeg = 10.0, altDeg = 70.0), "star1"),
            syntheticPoint(trueRotation, sky(azDeg = 130.0, altDeg = 40.0), "star2"),
            syntheticPoint(trueRotation, sky(azDeg = 260.0, altDeg = 20.0), "star3"),
        )

        val result = AlignmentSolver.solve(points, nowEpochMillis = 0L)
        assertIs<AlignmentResult.Success>(result)
        assertSameRotation(trueRotation, result.model.sensorToSky)
        assertTrue(result.model.rmsResidualDegrees < 0.01)
    }

    @Test
    fun tooCloseStarPair_isRejected() {
        val trueRotation = Quaternion.IDENTITY
        val points = listOf(
            syntheticPoint(trueRotation, sky(azDeg = 100.0, altDeg = 50.0), "star1"),
            syntheticPoint(trueRotation, sky(azDeg = 105.0, altDeg = 52.0), "star2"), // a few degrees apart
        )

        val result = AlignmentSolver.solve(points, nowEpochMillis = 0L)
        assertIs<AlignmentResult.Failure>(result)
        assertTrue(result.reason.contains("star1") || result.reason.contains("star2"))
    }

    @Test
    fun wellSeparatedStars_areAccepted() {
        val trueRotation = Quaternion.IDENTITY
        val points = listOf(
            syntheticPoint(trueRotation, sky(azDeg = 0.0, altDeg = 50.0), "star1"),
            syntheticPoint(trueRotation, sky(azDeg = 90.0, altDeg = 50.0), "star2"),
        )
        assertIs<AlignmentResult.Success>(AlignmentSolver.solve(points, nowEpochMillis = 0L))
    }

    @Test
    fun rmsResidual_isNearZeroForNoiseFreeInput_andGrowsWithInjectedNoise() {
        val trueRotation = Quaternion.fromAxisAngle(Vector3(0.3, 0.6, 0.1), Angle.ofDegrees(12.0)).normalized()
        val cleanPoints = listOf(
            syntheticPoint(trueRotation, sky(azDeg = 15.0, altDeg = 65.0), "star1"),
            syntheticPoint(trueRotation, sky(azDeg = 140.0, altDeg = 30.0), "star2"),
            syntheticPoint(trueRotation, sky(azDeg = 280.0, altDeg = 45.0), "star3"),
        )
        val cleanResult = AlignmentSolver.solve(cleanPoints, nowEpochMillis = 0L)
        assertIs<AlignmentResult.Success>(cleanResult)
        assertTrue(cleanResult.model.rmsResidualDegrees < 0.01)

        val noise = Quaternion.fromAxisAngle(Vector3.UNIT_X, Angle.ofDegrees(2.0))
        val noisyPoints = listOf(
            syntheticPoint(trueRotation, sky(azDeg = 15.0, altDeg = 65.0), "star1", noise = noise),
            syntheticPoint(trueRotation, sky(azDeg = 140.0, altDeg = 30.0), "star2"),
            syntheticPoint(trueRotation, sky(azDeg = 280.0, altDeg = 45.0), "star3"),
        )
        val noisyResult = AlignmentSolver.solve(noisyPoints, nowEpochMillis = 0L)
        assertIs<AlignmentResult.Success>(noisyResult)
        assertTrue(noisyResult.model.rmsResidualDegrees > cleanResult.model.rmsResidualDegrees)
    }

    @Test
    fun noPoints_isRejected() {
        assertIs<AlignmentResult.Failure>(AlignmentSolver.solve(emptyList(), nowEpochMillis = 0L))
    }
}
