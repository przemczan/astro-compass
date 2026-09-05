package com.astrocompass.alignment

import com.astrocompass.astro.Angle
import com.astrocompass.astro.AttitudeFit
import com.astrocompass.astro.Quaternion
import com.astrocompass.astro.Vector3
import kotlin.math.sqrt

/**
 * Fits [AlignmentModel.sensorToSky] from two or three star syncs, via Davenport's q-method: the
 * full 3-DOF rotation, which absorbs whatever the true relationship between the sensor frame and
 * the sky happens to be -- including a poorly-mounted or misconfigured pointing axis -- because
 * it never assumes anything about that relationship beyond "it is one fixed rotation". The
 * q-method (an eigenvector of a symmetric matrix) is used over Kabsch/SVD deliberately: it cannot
 * return a reflection, so there is no determinant-sign correction to get wrong.
 */
object AlignmentSolver {

    /** Also referenced by the alignment wizard's star step, to warn about a too-close pick before
     *  the user syncs rather than only at solve time. */
    val MIN_STAR_SEPARATION = Angle.ofDegrees(25.0)

    fun solve(points: List<AlignmentPoint>, nowEpochMillis: Long): AlignmentResult {
        if (points.size < 2) return AlignmentResult.Failure("At least 2 stars are required")

        val tooClose = findTooCloseSeparation(points)
        if (tooClose != null) {
            return AlignmentResult.Failure(
                "${tooClose.first} and ${tooClose.second} are only ${tooClose.third.degrees.toInt()}° apart " +
                    "-- pick stars at least ${MIN_STAR_SEPARATION.degrees.toInt()}° apart for a reliable fit"
            )
        }

        val rotation = solveQMethod(points)
        val rms = computeRmsResidualDegrees(rotation, points)
        return AlignmentResult.Success(
            AlignmentModel(
                sensorToSky = rotation,
                points = points,
                rmsResidualDegrees = rms,
                computedAtEpochMillis = nowEpochMillis,
            )
        )
    }

    /** Delegates to the shared [AttitudeFit] core -- see its doc comment for the q-method itself. */
    private fun solveQMethod(points: List<AlignmentPoint>): Quaternion =
        AttitudeFit.solve(measured = points.map { it.sensorDirection }, reference = points.map { it.skyDirection })

    private fun computeRmsResidualDegrees(sensorToSky: Quaternion, points: List<AlignmentPoint>): Double {
        val squaredErrors = points.map { point ->
            val predicted = sensorToSky.rotate(point.sensorDirection)
            val error = predicted.angleTo(point.skyDirection).degrees
            error * error
        }
        return sqrt(squaredErrors.sum() / squaredErrors.size)
    }

    private fun findTooCloseSeparation(points: List<AlignmentPoint>): Triple<String, String, Angle>? {
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                val separation = points[i].skyDirection.angleTo(points[j].skyDirection)
                if (separation < MIN_STAR_SEPARATION) {
                    return Triple(points[i].targetId, points[j].targetId, separation)
                }
            }
        }
        return null
    }
}
