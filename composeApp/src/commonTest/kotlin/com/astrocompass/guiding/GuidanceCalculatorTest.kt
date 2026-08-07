package com.astrocompass.guiding

import com.astrocompass.astro.Angle
import com.astrocompass.astro.coords.HorizontalCoordinates
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuidanceCalculatorTest {

    private fun enu(azDeg: Double, altDeg: Double) =
        HorizontalCoordinates(Angle.ofDegrees(azDeg), Angle.ofDegrees(altDeg)).toEnu()

    @Test
    fun targetDirectlyAbove_pointsArrowUp() {
        val current = enu(azDeg = 100.0, altDeg = 40.0)
        val target = enu(azDeg = 100.0, altDeg = 55.0)

        val guidance = GuidanceCalculator.compute(current, target, onTargetToleranceDegrees = 1.0)
        assertEquals(15.0, guidance.altitudeDeltaDegrees, absoluteTolerance = 1e-6)
        assertEquals(0.0, guidance.crossTrackDeltaDegrees, absoluteTolerance = 1e-6)
        assertEquals(0.0, guidance.arrowAngleDegrees, absoluteTolerance = 1e-6)
        assertEquals(15.0, guidance.separationDegrees, absoluteTolerance = 1e-6)
    }

    @Test
    fun targetToTheEast_atSameAltitude_pointsArrowRight() {
        val current = enu(azDeg = 100.0, altDeg = 0.0)
        val target = enu(azDeg = 110.0, altDeg = 0.0)

        val guidance = GuidanceCalculator.compute(current, target, onTargetToleranceDegrees = 1.0)
        assertEquals(0.0, guidance.altitudeDeltaDegrees, absoluteTolerance = 1e-6)
        assertTrue(guidance.crossTrackDeltaDegrees > 0)
        assertEquals(90.0, guidance.arrowAngleDegrees, absoluteTolerance = 1e-6)
    }

    @Test
    fun targetToTheWest_atSameAltitude_pointsArrowLeft() {
        val current = enu(azDeg = 100.0, altDeg = 0.0)
        val target = enu(azDeg = 90.0, altDeg = 0.0)

        val guidance = GuidanceCalculator.compute(current, target, onTargetToleranceDegrees = 1.0)
        assertTrue(guidance.crossTrackDeltaDegrees < 0)
        assertEquals(270.0, guidance.arrowAngleDegrees, absoluteTolerance = 1e-6)
    }

    @Test
    fun nearZenith_crossTrackIsMuchSmallerThanRawAzimuthDelta_andTracksSeparation() {
        val rawAzimuthDelta = 30.0
        val current = enu(azDeg = 200.0, altDeg = 80.0)
        val target = enu(azDeg = 200.0 + rawAzimuthDelta, altDeg = 80.0)

        val guidance = GuidanceCalculator.compute(current, target, onTargetToleranceDegrees = 1.0)
        assertTrue(abs(guidance.crossTrackDeltaDegrees) < rawAzimuthDelta / 2)
        // altitude is unchanged, so separation should be close to the cross-track magnitude.
        assertEquals(guidance.separationDegrees, abs(guidance.crossTrackDeltaDegrees), absoluteTolerance = 0.1)
    }

    @Test
    fun onTarget_whenSeparationWithinTolerance() {
        val current = enu(azDeg = 50.0, altDeg = 45.0)
        val target = enu(azDeg = 50.3, altDeg = 45.0)
        val guidance = GuidanceCalculator.compute(current, target, onTargetToleranceDegrees = 1.0)
        assertTrue(guidance.isOnTarget)
    }

    @Test
    fun notOnTarget_whenSeparationExceedsTolerance() {
        val current = enu(azDeg = 50.0, altDeg = 45.0)
        val target = enu(azDeg = 55.0, altDeg = 45.0)
        val guidance = GuidanceCalculator.compute(current, target, onTargetToleranceDegrees = 1.0)
        assertTrue(!guidance.isOnTarget)
    }
}
