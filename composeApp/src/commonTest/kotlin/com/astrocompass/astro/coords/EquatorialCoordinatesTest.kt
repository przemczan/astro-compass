package com.astrocompass.astro.coords

import com.astrocompass.astro.Angle
import com.astrocompass.astro.Vector3
import kotlin.test.Test
import kotlin.test.assertTrue

class EquatorialCoordinatesTest {

    private fun assertVectorClose(expected: Vector3, actual: Vector3, tolerance: Double = 1e-9) {
        assertTrue(
            kotlin.math.abs(expected.x - actual.x) < tolerance &&
                kotlin.math.abs(expected.y - actual.y) < tolerance &&
                kotlin.math.abs(expected.z - actual.z) < tolerance,
            "Expected ~$expected but was $actual",
        )
    }

    @Test
    fun toUnitVector_matchesCheckableReferenceDirections() {
        assertVectorClose(Vector3(1.0, 0.0, 0.0), EquatorialCoordinates(Angle.ofDegrees(0.0), Angle.ofDegrees(0.0)).toUnitVector())
        assertVectorClose(Vector3(0.0, 1.0, 0.0), EquatorialCoordinates(Angle.ofDegrees(90.0), Angle.ofDegrees(0.0)).toUnitVector())
        assertVectorClose(Vector3(0.0, 0.0, 1.0), EquatorialCoordinates(Angle.ofDegrees(0.0), Angle.ofDegrees(90.0)).toUnitVector())
    }

    @Test
    fun fromUnitVector_isTheExactInverseOfToUnitVector() {
        val originals = listOf(
            EquatorialCoordinates(Angle.ofDegrees(37.0), Angle.ofDegrees(-22.0)),
            EquatorialCoordinates(Angle.ofDegrees(310.5), Angle.ofDegrees(68.2)),
            EquatorialCoordinates(Angle.ofDegrees(0.001), Angle.ofDegrees(-89.9)),
        )
        for (original in originals) {
            val roundTripped = EquatorialCoordinates.fromUnitVector(original.toUnitVector())
            assertTrue(
                kotlin.math.abs(original.rightAscension.normalized().degrees - roundTripped.rightAscension.degrees) < 1e-6,
                "RA round-trip mismatch for $original -> $roundTripped",
            )
            assertTrue(
                kotlin.math.abs(original.declination.degrees - roundTripped.declination.degrees) < 1e-6,
                "Dec round-trip mismatch for $original -> $roundTripped",
            )
        }
    }
}
