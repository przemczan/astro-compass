package com.astrocompass.alignment

import com.astrocompass.astro.Angle
import com.astrocompass.astro.AttitudeFit
import com.astrocompass.astro.Quaternion
import com.astrocompass.astro.Vector3
import kotlin.math.atan2
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

    /** Also referenced by [com.astrocompass.ui.screens.AlignmentScreen]'s sky map, to warn about a
     *  too-close pick before the user syncs rather than only failing at "Compute alignment" time. */
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

    /**
     * One-tap drift remedy: corrects *only* the yaw of an already-fit [existingModel], leaving
     * whatever pitch/roll-plane mounting offset the original 2-3 star fit absorbed untouched.
     * Replacing the whole model with a fresh yaw-only solve (as [solve] would) throws that
     * mounting correction away and silently reintroduces its full error -- see the "why re-sync
     * can stay yaw-only" corollary on this class's doc comment.
     */
    fun resync(existingModel: AlignmentModel, point: AlignmentPoint, nowEpochMillis: Long): AlignmentResult {
        val predictedSkyDirection = existingModel.sensorToSky.rotate(point.sensorDirection)
        val yawCorrection = solveYawOnly(point.skyDirection, predictedSkyDirection)
        val rotation = (yawCorrection * existingModel.sensorToSky).normalized()

        // A yaw-only correction doesn't change how well the original fit's points agree with
        // each other, so the original point list and RMS carry over unchanged. Recomputing RMS
        // against these older points here would just measure the drift this resync corrects,
        // not the fit quality.
        return AlignmentResult.Success(
            existingModel.copy(sensorToSky = rotation, computedAtEpochMillis = nowEpochMillis)
        )
    }

    /** Rotation purely about the "up" axis: sensor-frame and ENU share the same up axis (both
     *  gravity-referenced), so only the horizontal (azimuth-like) offset is unknown. Used only by
     *  [resync] -- there is no from-scratch 1-star alignment path. */
    private fun solveYawOnly(skyDirection: Vector3, sensorDirection: Vector3): Quaternion {
        val skyYaw = atan2(skyDirection.x, skyDirection.y)
        val sensorYaw = atan2(sensorDirection.x, sensorDirection.y)
        // A +phi rotation about +Z (right-hand rule) *decreases* atan2(x, y) by phi, so the
        // angle that carries sensorYaw to skyYaw is (sensorYaw - skyYaw), not the reverse.
        return Quaternion.fromAxisAngle(Vector3.UNIT_Z, Angle.ofRadians(sensorYaw - skyYaw))
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
