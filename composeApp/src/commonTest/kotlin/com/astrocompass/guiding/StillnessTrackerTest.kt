package com.astrocompass.guiding

import com.astrocompass.astro.Angle
import com.astrocompass.astro.Vector3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TOLERANCE_DEGREES = 1.0
private const val HOLD_MILLIS = 2_000L

class StillnessTrackerTest {

    /** A unit vector [offsetDegrees] away from due north on the horizon -- the axis doesn't matter,
     *  only the angle between successive readings does. */
    private fun direction(offsetDegrees: Double): Vector3 {
        val radians = Angle.ofDegrees(offsetDegrees).radians
        return Vector3(sin(radians), cos(radians), 0.0)
    }

    private fun tracker() = StillnessTracker(TOLERANCE_DEGREES, HOLD_MILLIS)

    @Test
    fun theFirstReadingIsNeverAlreadyStill() {
        assertFalse(tracker().update(direction(0.0), 0L))
    }

    @Test
    fun holdingWithinToleranceForLongEnough_reportsStill() {
        val tracker = tracker()
        tracker.update(direction(0.0), 0L)
        assertFalse(tracker.update(direction(0.4), 1_000L))
        assertTrue(tracker.update(direction(0.6), 2_000L))
    }

    @Test
    fun movingBeyondTolerance_restartsTheHold() {
        val tracker = tracker()
        tracker.update(direction(0.0), 0L)
        tracker.update(direction(5.0), 1_900L)

        // The hold now runs from the move, not from the original anchor, so the instant the old one
        // would have matured is still too early.
        assertFalse(tracker.update(direction(5.0), 2_000L))
        assertTrue(tracker.update(direction(5.0), 3_900L))
    }

    /** A slow drift stays within tolerance step to step while leaving the starting direction far
     *  behind -- measuring against the anchor rather than the previous reading is what catches it. */
    @Test
    fun aSlowDriftNeverCountsAsStill() {
        val tracker = tracker()
        var nowEpochMillis = 0L
        repeat(20) { step ->
            assertFalse(tracker.update(direction(step * 0.9), nowEpochMillis))
            nowEpochMillis += 500L
        }
    }

    @Test
    fun resetting_discardsTheHoldInProgress() {
        val tracker = tracker()
        tracker.update(direction(0.0), 0L)
        tracker.reset()

        assertFalse(tracker.update(direction(0.0), 5_000L))
    }
}
