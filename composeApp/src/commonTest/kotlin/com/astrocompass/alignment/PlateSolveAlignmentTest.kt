package com.astrocompass.alignment

import com.astrocompass.astro.Angle
import com.astrocompass.astro.AttitudeFit
import com.astrocompass.astro.Quaternion
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.coords.CoordinateTransforms
import com.astrocompass.astro.coords.EquatorialCoordinates
import com.astrocompass.astro.coords.Precession
import com.astrocompass.astro.time.AstroTime
import com.astrocompass.location.ObserverLocation
import com.astrocompass.platesolve.CameraIntrinsics
import com.astrocompass.platesolve.MatchedStar
import com.astrocompass.platesolve.PlateSolveResult
import com.astrocompass.platesolve.PlateSolver
import com.astrocompass.platesolve.ReferenceStar
import com.astrocompass.platesolve.StarCentroid
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end recovery test, same convention as [com.astrocompass.platesolve.PlateSolverTest] and
 * [AlignmentSolverTest]: build a synthetic star field around a known "true" full pointing chain
 * (sensor->sky, device->world, camera->device), render it into a photo, plate-solve it, and check
 * [PlateSolveAlignment.solve] recovers the true `sensorToSky` -- proving a single photo, unlike a
 * single manual sync, is enough to redo the whole 3-DOF alignment.
 */
class PlateSolveAlignmentTest {

    private val intrinsics = CameraIntrinsics(focalLengthPx = 800.0, principalPointX = 500.0, principalPointY = 500.0)
    private val imageWidth = 1000.0
    private val imageHeight = 1000.0
    private val location = ObserverLocation(latitude = Angle.ofDegrees(45.0), longitude = Angle.ofDegrees(-70.0))

    // An arbitrary date well after J2000, so the test exercises real precession handling rather
    // than trivially passing when it would be the identity.
    private val nowEpochMillis = 1_800_000_000_000L

    /** Same up-to-sign quaternion comparison as [AlignmentSolverTest]. */
    private fun assertSameRotation(expected: Quaternion, actual: Quaternion, toleranceDegrees: Double) {
        val dot = expected.w * actual.w + expected.x * actual.x + expected.y * actual.y + expected.z * actual.z
        val angleBetween = acos(abs(dot).coerceIn(-1.0, 1.0)) * 2 * 180.0 / kotlin.math.PI
        assertTrue(angleBetween < toleranceDegrees, "Rotations differ by $angleBetween degrees")
    }

    /** Empirically derives the "ENU-of-date -> J2000 equatorial" rotation at [location]/
     *  [nowEpochMillis] by fitting it from sample directions through the same tested transforms
     *  [PlateSolveAlignment] itself uses -- avoids hand-deriving a combined precession+horizontal
     *  quaternion formula just for this test. */
    private fun enuToJ2000Rotation(): Quaternion {
        val julianDay = AstroTime.julianDay(nowEpochMillis)
        val julianCenturies = AstroTime.julianCenturiesJ2000(julianDay)
        val lst = AstroTime.localSiderealTime(julianDay, location.longitude)
        val sampleJ2000 = listOf(
            EquatorialCoordinates(Angle.ofDegrees(10.0), Angle.ofDegrees(40.0)),
            EquatorialCoordinates(Angle.ofDegrees(140.0), Angle.ofDegrees(-10.0)),
            EquatorialCoordinates(Angle.ofDegrees(250.0), Angle.ofDegrees(60.0)),
            EquatorialCoordinates(Angle.ofDegrees(300.0), Angle.ofDegrees(5.0)),
        )
        val enuVectors = sampleJ2000.map { j2000 ->
            val ofDate = Precession.j2000ToDate(j2000, julianCenturies)
            CoordinateTransforms.equatorialToHorizontal(ofDate, lst, location.latitude).toEnu()
        }
        val j2000Vectors = sampleJ2000.map { it.toUnitVector() }
        return AttitudeFit.solve(measured = enuVectors, reference = j2000Vectors)
    }

    private fun projectToFrame(j2000Direction: Vector3, cameraToJ2000: Quaternion): StarCentroid? {
        val cameraVec = cameraToJ2000.conjugate().rotate(j2000Direction)
        if (cameraVec.z <= 0.0) return null
        val px = intrinsics.principalPointX + intrinsics.focalLengthPx * cameraVec.x / cameraVec.z
        val py = intrinsics.principalPointY - intrinsics.focalLengthPx * cameraVec.y / cameraVec.z
        if (px !in 0.0..imageWidth || py !in 0.0..imageHeight) return null
        return StarCentroid(px, py, brightness = 100.0)
    }

    @Test
    fun recoversTrueSensorToSky_fromAPlateSolvedSyntheticPhoto() {
        val sensorToSkyTrue = Quaternion.fromAxisAngle(Vector3(0.2, 0.7, 0.4), Angle.ofDegrees(28.0)).normalized()
        val deviceToWorldTrue = Quaternion.fromAxisAngle(Vector3(-0.3, 0.1, 0.9), Angle.ofDegrees(63.0)).normalized()
        val cameraToDeviceTrue = Quaternion.fromAxisAngle(Vector3.UNIT_Z, Angle.ofDegrees(-90.0)).normalized()

        // The real relationship (in ENU) between camera-local directions and true sky, versus
        // what PlateSolver actually fits against (J2000 equatorial) -- see enuToJ2000Rotation().
        val cameraToEnuTrue = (sensorToSkyTrue * deviceToWorldTrue * cameraToDeviceTrue).normalized()
        val cameraToJ2000True = (enuToJ2000Rotation() * cameraToEnuTrue).normalized()

        val tangentJ2000 = cameraToJ2000True.rotate(Vector3.UNIT_Z)
        val tangentEquatorial = EquatorialCoordinates.fromUnitVector(tangentJ2000)

        val random = Random(7)
        val referenceStars = mutableListOf<ReferenceStar>()
        val detections = mutableListOf<StarCentroid>()
        repeat(40) {
            val raOffset = (random.nextDouble(-1.0, 1.0) * 30.0) / cos(tangentEquatorial.declination.radians)
            val decOffset = random.nextDouble(-1.0, 1.0) * 30.0
            val ra = tangentEquatorial.rightAscension + Angle.ofDegrees(raOffset)
            val dec = tangentEquatorial.declination + Angle.ofDegrees(decOffset)
            val magnitude = random.nextDouble(2.0, 6.5).toFloat()
            referenceStars += ReferenceStar(ra, dec, magnitude)

            val centroid = projectToFrame(EquatorialCoordinates(ra, dec).toUnitVector(), cameraToJ2000True) ?: return@repeat
            detections += centroid
        }

        val plateSolveResult = PlateSolver.solve(
            detections = detections,
            intrinsics = intrinsics,
            seedBoresight = tangentEquatorial,
            searchRadius = Angle.ofDegrees(40.0),
            referenceStars = referenceStars,
        )
        assertNotNull(plateSolveResult)
        assertTrue(plateSolveResult.matchedStars.size >= 8, "Expected at least 8 matched stars, got ${plateSolveResult.matchedStars.size}")

        val result = PlateSolveAlignment.solve(
            plateSolveResult = plateSolveResult,
            deviceToWorld = deviceToWorldTrue,
            cameraToDevice = cameraToDeviceTrue,
            location = location,
            nowEpochMillis = nowEpochMillis,
        )

        assertIs<AlignmentResult.Success>(result)
        assertSameRotation(sensorToSkyTrue, result.model.sensorToSky, toleranceDegrees = 0.1)
    }

    @Test
    fun tooFewMatchedStars_isRejected() {
        val lonelyMatch = MatchedStar(Vector3.UNIT_Z, ReferenceStar(Angle.ofDegrees(0.0), Angle.ofDegrees(0.0), 1.0f))
        val plateSolveResult = PlateSolveResult(
            centerEquatorial = EquatorialCoordinates(Angle.ofDegrees(0.0), Angle.ofDegrees(0.0)),
            matchedStarCount = 1,
            rmsResidualDegrees = 0.0,
            matchedStars = listOf(lonelyMatch),
        )

        val result = PlateSolveAlignment.solve(
            plateSolveResult = plateSolveResult,
            deviceToWorld = Quaternion.IDENTITY,
            cameraToDevice = Quaternion.IDENTITY,
            location = location,
            nowEpochMillis = nowEpochMillis,
        )

        assertIs<AlignmentResult.Failure>(result)
    }
}
