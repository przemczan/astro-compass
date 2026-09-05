package com.astrocompass.platesolve

import com.astrocompass.astro.Angle
import com.astrocompass.astro.AttitudeFit
import com.astrocompass.astro.Quaternion
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.coords.EquatorialCoordinates
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Identifies which catalog stars a set of detected image centroids are, and fits the resulting
 * camera orientation -- classic "plate solving", scoped to a single on-demand photo rather than
 * continuous tracking.
 *
 * Matching is based purely on the *relative* angular separation between pairs of detected stars
 * versus pairs of candidate catalog stars (a small geometric-hashing/RANSAC scheme), never on
 * predicted pixel position from an assumed camera roll. This is deliberate: [seedBoresight] only
 * needs to be roughly right (it narrows which catalog stars are worth considering), and the
 * fitted result is exact regardless of how the phone happened to be rotated around the camera's
 * own axis when the photo was taken. In particular, this means the algorithm never needs to know
 * how the image buffer's rows/columns relate to true device or gravity axes -- the highest-risk,
 * hardest-to-verify part of a naive camera-based approach -- because [CameraIntrinsics] already
 * reduces each detection to a self-consistent local direction (see its doc comment), and matching
 * only ever compares angles between those directions.
 */
object PlateSolver {

    /** Anchor pairs closer than this are skipped when generating match hypotheses -- too close
     *  together for their separation to reliably disambiguate which catalog pair they are. */
    private val MIN_PAIR_SEPARATION = Angle.ofDegrees(3.0)

    /** Only the brightest detections are used to seed match hypotheses; every detection (not just
     *  these) is still checked as a potential inlier once a hypothesis rotation exists. */
    private const val MAX_ANCHOR_STARS = 12

    /** Bounds the O(candidates^2) hypothesis search on a dense reference set; the brightest
     *  candidates within [searchRadius] are kept since they're also the most likely detections. */
    private const val MAX_CANDIDATES = 400

    private const val MIN_MATCHED_STARS = 4

    fun solve(
        detections: List<StarCentroid>,
        intrinsics: CameraIntrinsics,
        seedBoresight: EquatorialCoordinates,
        searchRadius: Angle,
        referenceStars: List<ReferenceStar>,
        matchTolerance: Angle = Angle.ofDegrees(0.3),
    ): PlateSolverOutcome {
        if (detections.size < MIN_MATCHED_STARS) {
            return PlateSolverOutcome.Failed(
                PlateSolveFailureReason.TOO_FEW_DETECTIONS,
                PlateSolveDiagnostics(detectionCount = detections.size),
            )
        }

        val seedVector = seedBoresight.toUnitVector()
        val candidates = referenceStars
            .map { it to it.toUnitVector() }
            .filter { (_, direction) -> direction.angleTo(seedVector) <= searchRadius }
            .sortedBy { (star, _) -> star.magnitude }
            .take(MAX_CANDIDATES)
        if (candidates.size < MIN_MATCHED_STARS) {
            return PlateSolverOutcome.Failed(
                PlateSolveFailureReason.TOO_FEW_CANDIDATES,
                PlateSolveDiagnostics(detectionCount = detections.size, candidateCount = candidates.size),
            )
        }
        val candidateVectors = candidates.map { it.second }
        val candidateSeparationsDeg = Array(candidateVectors.size) { a ->
            DoubleArray(candidateVectors.size) { b ->
                if (a == b) 0.0 else candidateVectors[a].angleTo(candidateVectors[b]).degrees
            }
        }

        val sortedDetections = detections.sortedByDescending { it.brightness }
        val imageVectors = sortedDetections.map { intrinsics.pixelToDirection(it.pixelX, it.pixelY) }
        val anchorCount = minOf(imageVectors.size, MAX_ANCHOR_STARS)

        var bestInliers: List<Pair<Int, Int>> = emptyList()

        for (i in 0 until anchorCount) {
            for (j in i + 1 until anchorCount) {
                val sepImgDeg = imageVectors[i].angleTo(imageVectors[j]).degrees
                if (sepImgDeg < MIN_PAIR_SEPARATION.degrees) continue

                for (a in candidateVectors.indices) {
                    for (b in candidateVectors.indices) {
                        if (a == b) continue
                        if (abs(candidateSeparationsDeg[a][b] - sepImgDeg) > matchTolerance.degrees) continue

                        val hypothesis = AttitudeFit.solve(
                            measured = listOf(imageVectors[i], imageVectors[j]),
                            reference = listOf(candidateVectors[a], candidateVectors[b]),
                        )
                        val inliers = findInliers(hypothesis, imageVectors, candidateVectors, matchTolerance)
                        if (inliers.size > bestInliers.size) bestInliers = inliers
                    }
                }
            }
        }

        if (bestInliers.size < MIN_MATCHED_STARS) {
            return PlateSolverOutcome.Failed(
                PlateSolveFailureReason.NO_GEOMETRIC_MATCH,
                PlateSolveDiagnostics(detections.size, candidates.size, bestInliers.size),
            )
        }

        val finalRotation = AttitudeFit.solve(
            measured = bestInliers.map { (detectionIndex, _) -> imageVectors[detectionIndex] },
            reference = bestInliers.map { (_, candidateIndex) -> candidateVectors[candidateIndex] },
        )

        val centerEquatorial = EquatorialCoordinates.fromUnitVector(finalRotation.rotate(BORESIGHT_DIRECTION))

        val squaredErrorsDeg = bestInliers.map { (detectionIndex, candidateIndex) ->
            val predicted = finalRotation.rotate(imageVectors[detectionIndex])
            val error = predicted.angleTo(candidateVectors[candidateIndex]).degrees
            error * error
        }
        val rmsResidualDegrees = sqrt(squaredErrorsDeg.sum() / squaredErrorsDeg.size)

        val matchedStars = bestInliers.map { (detectionIndex, candidateIndex) ->
            MatchedStar(imageVectors[detectionIndex], candidates[candidateIndex].first)
        }
        return PlateSolverOutcome.Solved(
            PlateSolveResult(centerEquatorial, bestInliers.size, rmsResidualDegrees, matchedStars),
            PlateSolveDiagnostics(detections.size, candidates.size, bestInliers.size),
        )
    }

    /** Maps every detection through the hypothesis [rotation] and greedily pairs it with its
     *  nearest unclaimed candidate within [tolerance] -- the RANSAC consensus count for that
     *  hypothesis, and (for the winning hypothesis) the final match list. */
    private fun findInliers(
        rotation: Quaternion,
        imageVectors: List<Vector3>,
        candidateVectors: List<Vector3>,
        tolerance: Angle,
    ): List<Pair<Int, Int>> {
        val matches = mutableListOf<Pair<Int, Int>>()
        val usedCandidates = mutableSetOf<Int>()
        for (detectionIndex in imageVectors.indices) {
            val predicted = rotation.rotate(imageVectors[detectionIndex])
            var bestCandidate = -1
            var bestAngle = tolerance
            for (candidateIndex in candidateVectors.indices) {
                if (candidateIndex in usedCandidates) continue
                val angle = predicted.angleTo(candidateVectors[candidateIndex])
                if (angle < bestAngle) {
                    bestAngle = angle
                    bestCandidate = candidateIndex
                }
            }
            if (bestCandidate >= 0) {
                matches += detectionIndex to bestCandidate
                usedCandidates += bestCandidate
            }
        }
        return matches
    }

    /** The frame's principal-point direction in [CameraIntrinsics]' local convention -- see its
     *  doc comment for why (0, 0, 1) is always the crosshair regardless of image orientation. */
    private val BORESIGHT_DIRECTION = Vector3(0.0, 0.0, 1.0)
}
