package com.astrocompass.platesolve

import com.astrocompass.astro.Angle
import com.astrocompass.astro.Quaternion
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.coords.EquatorialCoordinates
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end recovery tests: build a synthetic star field around a known "true" camera
 * orientation (including an arbitrary roll, to prove the solver never assumes upright framing),
 * render it through a pinhole model into detections, and check [PlateSolver.solve] recovers the
 * true frame-center direction -- the same synthetic-recovery convention [AlignmentSolverTest]
 * uses for the sensor-sync fit this shares its core math with.
 */
class PlateSolverTest {

    private val intrinsics = CameraIntrinsics(focalLengthPx = 800.0, principalPointX = 500.0, principalPointY = 500.0)
    private val imageWidth = 1000.0
    private val imageHeight = 1000.0

    /** Rotation from the camera's local frame (see [CameraIntrinsics]) to the equatorial frame,
     *  such that the boresight (0,0,1) maps to ([tangentRa], [tangentDec]) with the given [rollDeg]
     *  twist about that axis -- everything a real photo's orientation could vary. */
    private fun trueRotation(tangentRa: Angle, tangentDec: Angle, rollDeg: Double): Quaternion {
        val tangentVector = EquatorialCoordinates(tangentRa, tangentDec).toUnitVector()
        val axis = (Vector3.UNIT_Z cross tangentVector).normalized()
        val boresightAlignment = Quaternion.fromAxisAngle(axis, Vector3.UNIT_Z.angleTo(tangentVector))
        val roll = Quaternion.fromAxisAngle(tangentVector, Angle.ofDegrees(rollDeg))
        return (roll * boresightAlignment).normalized()
    }

    /** A star scattered within [maxRadiusDeg] of the tangent point -- a plain degree-offset
     *  sample, not a physically uniform spherical distribution, which is fine for exercising the
     *  matcher against a varied field. */
    private fun scatteredStar(tangentRa: Angle, tangentDec: Angle, maxRadiusDeg: Double, random: Random): EquatorialCoordinates {
        val raOffset = (random.nextDouble(-1.0, 1.0) * maxRadiusDeg) / kotlin.math.cos(tangentDec.radians)
        val decOffset = random.nextDouble(-1.0, 1.0) * maxRadiusDeg
        return EquatorialCoordinates(tangentRa + Angle.ofDegrees(raOffset), tangentDec + Angle.ofDegrees(decOffset))
    }

    private fun brightnessForMagnitude(magnitude: Float): Double = 100.0 * 10.0.pow(-0.4 * magnitude)

    /** Projects a true sky direction through [rotation] and the pinhole model; null if it falls
     *  behind the camera or outside the frame -- exactly what a real photo would fail to capture. */
    private fun projectToFrame(equatorial: EquatorialCoordinates, rotation: Quaternion): StarCentroid? {
        val cameraVec = rotation.conjugate().rotate(equatorial.toUnitVector())
        if (cameraVec.z <= 0.0) return null
        val px = intrinsics.principalPointX + intrinsics.focalLengthPx * cameraVec.x / cameraVec.z
        val py = intrinsics.principalPointY - intrinsics.focalLengthPx * cameraVec.y / cameraVec.z
        if (px !in 0.0..imageWidth || py !in 0.0..imageHeight) return null
        return StarCentroid(px, py, brightness = 0.0)
    }

    private class SyntheticField(val referenceStars: List<ReferenceStar>, val detections: List<StarCentroid>)

    private fun buildField(
        rotation: Quaternion,
        tangentRa: Angle,
        tangentDec: Angle,
        starCount: Int,
        random: Random,
        pixelNoise: Double = 0.0,
        dropoutFraction: Double = 0.0,
        falseDetectionCount: Int = 0,
    ): SyntheticField {
        val referenceStars = mutableListOf<ReferenceStar>()
        val detections = mutableListOf<StarCentroid>()
        repeat(starCount) {
            val equatorial = scatteredStar(tangentRa, tangentDec, maxRadiusDeg = 35.0, random)
            val magnitude = random.nextDouble(2.0, 6.5).toFloat()
            referenceStars += ReferenceStar(equatorial.rightAscension, equatorial.declination, magnitude)

            if (random.nextDouble() < dropoutFraction) return@repeat
            val projected = projectToFrame(equatorial, rotation) ?: return@repeat
            val noisyX = projected.pixelX + if (pixelNoise > 0.0) random.nextDouble(-pixelNoise, pixelNoise) else 0.0
            val noisyY = projected.pixelY + if (pixelNoise > 0.0) random.nextDouble(-pixelNoise, pixelNoise) else 0.0
            detections += StarCentroid(noisyX, noisyY, brightnessForMagnitude(magnitude))
        }
        repeat(falseDetectionCount) {
            detections += StarCentroid(
                random.nextDouble(0.0, imageWidth),
                random.nextDouble(0.0, imageHeight),
                brightness = random.nextDouble(1.0, 50.0),
            )
        }
        return SyntheticField(referenceStars, detections)
    }

    @Test
    fun recoversKnownAttitude_fromCleanSyntheticStarField() {
        val tangentRa = Angle.ofDegrees(180.0)
        val tangentDec = Angle.ofDegrees(20.0)
        val rotation = trueRotation(tangentRa, tangentDec, rollDeg = 37.0)
        val field = buildField(rotation, tangentRa, tangentDec, starCount = 40, random = Random(1))

        val result = PlateSolver.solve(
            detections = field.detections,
            intrinsics = intrinsics,
            seedBoresight = EquatorialCoordinates(tangentRa, tangentDec),
            searchRadius = Angle.ofDegrees(40.0),
            referenceStars = field.referenceStars,
        )

        assertNotNull(result)
        assertTrue(result.matchedStarCount >= 8, "Expected at least 8 matched stars, got ${result.matchedStarCount}")
        assertTrue(result.rmsResidualDegrees < 0.05, "RMS residual too high: ${result.rmsResidualDegrees}")
        assertAngularSeparationBelow(EquatorialCoordinates(tangentRa, tangentDec), result.centerEquatorial, 0.05)
    }

    @Test
    fun recoversKnownAttitude_withPixelNoiseAndFalseDetections() {
        val tangentRa = Angle.ofDegrees(45.0)
        val tangentDec = Angle.ofDegrees(-15.0)
        val rotation = trueRotation(tangentRa, tangentDec, rollDeg = -112.0)
        val field = buildField(
            rotation, tangentRa, tangentDec, starCount = 40, random = Random(2),
            pixelNoise = 1.5, falseDetectionCount = 6,
        )

        val result = PlateSolver.solve(
            detections = field.detections,
            intrinsics = intrinsics,
            seedBoresight = EquatorialCoordinates(tangentRa + Angle.ofDegrees(5.0), tangentDec - Angle.ofDegrees(4.0)),
            searchRadius = Angle.ofDegrees(40.0),
            referenceStars = field.referenceStars,
            matchTolerance = Angle.ofDegrees(0.35),
        )

        assertNotNull(result)
        assertTrue(result.matchedStarCount >= 6, "Expected at least 6 matched stars, got ${result.matchedStarCount}")
        assertAngularSeparationBelow(EquatorialCoordinates(tangentRa, tangentDec), result.centerEquatorial, 0.3)
    }

    @Test
    fun recoversKnownAttitude_withStarDropouts() {
        val tangentRa = Angle.ofDegrees(300.0)
        val tangentDec = Angle.ofDegrees(55.0)
        val rotation = trueRotation(tangentRa, tangentDec, rollDeg = 5.0)
        val field = buildField(
            rotation, tangentRa, tangentDec, starCount = 50, random = Random(3),
            dropoutFraction = 0.4,
        )

        val result = PlateSolver.solve(
            detections = field.detections,
            intrinsics = intrinsics,
            seedBoresight = EquatorialCoordinates(tangentRa, tangentDec),
            searchRadius = Angle.ofDegrees(40.0),
            referenceStars = field.referenceStars,
        )

        assertNotNull(result)
        assertAngularSeparationBelow(EquatorialCoordinates(tangentRa, tangentDec), result.centerEquatorial, 0.1)
    }

    @Test
    fun returnsNull_whenTooFewStarsDetected() {
        val result = PlateSolver.solve(
            detections = listOf(StarCentroid(500.0, 500.0, 10.0), StarCentroid(510.0, 500.0, 10.0)),
            intrinsics = intrinsics,
            seedBoresight = EquatorialCoordinates(Angle.ofDegrees(0.0), Angle.ofDegrees(0.0)),
            searchRadius = Angle.ofDegrees(40.0),
            referenceStars = emptyList(),
        )
        assertNull(result)
    }

    @Test
    fun returnsNull_whenSeedIsFarFromTheTrueField() {
        val tangentRa = Angle.ofDegrees(180.0)
        val tangentDec = Angle.ofDegrees(20.0)
        val rotation = trueRotation(tangentRa, tangentDec, rollDeg = 0.0)
        val field = buildField(rotation, tangentRa, tangentDec, starCount = 40, random = Random(4))

        val result = PlateSolver.solve(
            detections = field.detections,
            intrinsics = intrinsics,
            seedBoresight = EquatorialCoordinates(tangentRa + Angle.ofDegrees(140.0), tangentDec),
            searchRadius = Angle.ofDegrees(10.0),
            referenceStars = field.referenceStars,
        )
        assertNull(result)
    }

    private fun assertAngularSeparationBelow(expected: EquatorialCoordinates, actual: EquatorialCoordinates, maxDegrees: Double) {
        val separation = expected.toUnitVector().angleTo(actual.toUnitVector()).degrees
        assertTrue(separation < maxDegrees, "Expected within $maxDegrees° but was $separation° off")
    }
}
