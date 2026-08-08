package com.astrocompass.astro

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [com.astrocompass.alignment.AlignmentSolverTest] already exercises this extensively through
 *  [com.astrocompass.alignment.AlignmentSolver]; these cover the extracted utility directly, at
 *  its own layer, per this project's "every formula in astro/ is a plain JVM unit test" convention. */
class AttitudeFitTest {

    private fun assertSameRotation(expected: Quaternion, actual: Quaternion, toleranceDegrees: Double = 0.05) {
        val dot = expected.w * actual.w + expected.x * actual.x + expected.y * actual.y + expected.z * actual.z
        val angleBetween = kotlin.math.acos(kotlin.math.abs(dot).coerceIn(-1.0, 1.0)) * 2 * 180.0 / kotlin.math.PI
        assertTrue(angleBetween < toleranceDegrees, "Rotations differ by $angleBetween degrees")
    }

    @Test
    fun recoversAnArbitraryRotation_fromThreeCleanDirectionPairs() {
        val trueRotation = Quaternion.fromAxisAngle(Vector3(0.4, -0.7, 0.2), Angle.ofDegrees(58.0)).normalized()
        val reference = listOf(
            Vector3(1.0, 0.2, 0.1).normalized(),
            Vector3(-0.3, 1.0, 0.4).normalized(),
            Vector3(0.2, -0.5, 1.0).normalized(),
        )
        val measured = reference.map { trueRotation.conjugate().rotate(it) }

        val fitted = AttitudeFit.solve(measured, reference)

        assertSameRotation(trueRotation, fitted)
    }

    @Test
    fun requiresMatchingListSizes() {
        assertFailsWith<IllegalArgumentException> {
            AttitudeFit.solve(measured = listOf(Vector3.UNIT_X), reference = emptyList())
        }
    }

    @Test
    fun requiresAtLeastOnePair() {
        assertFailsWith<IllegalArgumentException> {
            AttitudeFit.solve(measured = emptyList(), reference = emptyList())
        }
    }
}
