package com.astrocompass.platesolve

import com.astrocompass.astro.Angle
import com.astrocompass.astro.Quaternion
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.coords.EquatorialCoordinates
import com.astrocompass.catalog.CatalogFormat
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Measures -- rather than assumes -- whether the star catalog the solver actually searches against
 * is dense enough to plate-solve a typical field. `stars.bin` itself (mag <= 7, see
 * `tools/build-catalogs.mjs`) now also carries unnamed field stars added for sky map density, but
 * `AppContainer.solveAgainstCatalog` filters back down to named/Bayer/Flamsteed-only before calling
 * [PlateSolver.solve] -- letting the full, denser catalog through would multiply the O(candidates^2)
 * hypothesis search by roughly (total stars / named stars)^2 per anchor pair in a typical field (this
 * test ran at ~250s against the unfiltered catalog before that filter was applied here to match, vs.
 * a fraction of a second filtered). This test re-applies the identical filter so it measures what the
 * solver really sees. It's read here as a plain JVM classpath resource, matching [CatalogFormatTest]'s
 * approach and its reasoning about why this can't live in `commonTest`.
 *
 * If this test ever starts failing on a real device photo (not simulated here), that's the
 * trigger described in the plate-solving plan to add a denser, unnamed-star-inclusive catalog
 * specifically for the solver -- not a reason to loosen [PlateSolver]'s matching itself.
 */
class PlateSolverCatalogDensityTest {

    private fun readResource(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
            ?: error("Resource not found on test classpath: $name")
        return stream.use { it.readBytes() }
    }

    private val intrinsics = CameraIntrinsics(focalLengthPx = 800.0, principalPointX = 500.0, principalPointY = 500.0)

    /** Rotation mapping the camera boresight (0,0,1) to [tangentVector], no roll -- sufficient
     *  for this density measurement, which only cares whether enough stars fall in frame. */
    private fun rotationAligning(tangentVector: Vector3): Quaternion {
        val axis = (Vector3.UNIT_Z cross tangentVector).normalized()
        return Quaternion.fromAxisAngle(axis, Vector3.UNIT_Z.angleTo(tangentVector))
    }

    @Test
    fun bundledStarCatalog_isDenseEnoughToSolveATypicalCameraField() {
        val stars = CatalogFormat.decodeStars(readResource("stars.bin"))
            .filter { it.properName.isNotEmpty() || it.bayer.isNotEmpty() || it.flamsteed != 0 }
        val referenceStars = stars.map { ReferenceStar(it.j2000.rightAscension, it.j2000.declination, it.magnitude) }

        // A patch of sky away from the crowded galactic plane and the poles. Deliberately a much
        // narrower cone than a phone's ~65 degree diagonal FOV: PlateSolver.solve()'s candidate
        // matching is O(anchorPairs x candidates^2) with a per-hypothesis cost (an eigensolve plus
        // an inlier scan) heavy enough that the real, dense catalog turns a wide search radius
        // into minutes -- thousands of coincidental candidate-pair separations pass the match
        // tolerance purely by chance once there are hundreds of real candidates in play. A wider
        // check would just prove the same "candidates scale with solid angle" fact at a much
        // higher constant cost -- if this narrow cone is comfortably dense, the full FOV is
        // denser still.
        val tangent = EquatorialCoordinates(Angle.ofDegrees(200.0), Angle.ofDegrees(30.0))
        val tangentVector = tangent.toUnitVector()
        val rotation = rotationAligning(tangentVector)

        val candidateCount = referenceStars.count { it.toUnitVector().angleTo(tangentVector) <= Angle.ofDegrees(10.0) }
        assertTrue(candidateCount >= 4, "Only $candidateCount bundled stars fall within the test field -- too few to solve")

        val detections = referenceStars.mapNotNull { star ->
            val cameraVec = rotation.conjugate().rotate(star.toUnitVector())
            if (cameraVec.z <= 0.0) return@mapNotNull null
            val px = intrinsics.principalPointX + intrinsics.focalLengthPx * cameraVec.x / cameraVec.z
            val py = intrinsics.principalPointY - intrinsics.focalLengthPx * cameraVec.y / cameraVec.z
            if (px !in 0.0..1000.0 || py !in 0.0..1000.0) return@mapNotNull null
            StarCentroid(px, py, brightness = 100.0 * 10.0.pow(-0.4 * star.magnitude))
        }

        val result = PlateSolver.solve(
            detections = detections,
            intrinsics = intrinsics,
            seedBoresight = tangent,
            searchRadius = Angle.ofDegrees(12.0),
            referenceStars = referenceStars,
        )

        assertNotNull(
            result,
            "Bundled stars.bin (named/Bayer/Flamsteed only, ${referenceStars.size} stars) was NOT dense enough to " +
                "solve a ${detections.size}-star synthetic field -- add a denser solver-specific catalog per the plan.",
        )
    }
}
