@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.astrocompass.guiding

import com.astrocompass.alignment.AlignmentModel
import com.astrocompass.astro.Angle
import com.astrocompass.astro.Quaternion
import com.astrocompass.astro.coords.EquatorialCoordinates
import com.astrocompass.platesolve.PlateSolveFailureReason
import com.astrocompass.platesolve.PlateSolveResult
import com.astrocompass.sensors.FakeOrientationSensor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Comfortably longer than one full cycle (stillness hold + the gap after a solve), so a loop that
 *  is going to publish has had every chance to. */
private const val ENOUGH_FOR_SEVERAL_CYCLES_MILLIS = 30_000L

class AutoPlateSolveRefinerTest {

    private fun attempt(correctionDegrees: Double, rmsResidualDegrees: Double = 0.2) = PlateSolveOutcome.Success(
        PlateSolveAttempt(
            result = PlateSolveResult(
                centerEquatorial = EquatorialCoordinates(Angle.ofDegrees(0.0), Angle.ofDegrees(0.0)),
                matchedStarCount = 8,
                rmsResidualDegrees = rmsResidualDegrees,
                matchedStars = emptyList(),
            ),
            correctedModel = AlignmentModel(
                sensorToSky = Quaternion.IDENTITY,
                points = emptyList(),
                rmsResidualDegrees = rmsResidualDegrees,
                computedAtEpochMillis = 1_000L,
            ),
            correctionDegrees = correctionDegrees,
        ),
    )

    private val failure = PlateSolveOutcome.Failure(PlateSolveFailureReason.TOO_FEW_DETECTIONS)

    /** A sensor held perfectly still, which is what the loop waits for before each solve. */
    private fun stillSensor() = FakeOrientationSensor().apply { emit(Quaternion.IDENTITY, 0L) }

    @Test
    fun aPlausibleCorrection_becomesTheAbsoluteReference() = runTest {
        val firstSuccesses = mutableListOf<PlateSolveAttempt>()
        val refiner = AutoPlateSolveRefiner(
            scope = backgroundScope,
            orientationSensor = stillSensor(),
            boresightDeviceVector = MutableStateFlow(TelescopeAxis.DEFAULT.deviceVector),
            attemptSolve = { attempt(correctionDegrees = 2.0) },
            onFirstSuccess = { firstSuccesses += it },
            nowEpochMillis = { testScheduler.currentTime },
        )

        refiner.setActive(true)
        advanceTimeBy(ENOUGH_FOR_SEVERAL_CYCLES_MILLIS)
        refiner.setActive(false)

        val state = assertNotNull(refiner.current.value)
        assertEquals(ReferenceOrigin.STAR_ALIGNMENT, state.origin)
        assertEquals(0.2, state.uncertaintyDegrees)
        // Many solves land, but only the first is worth persisting -- the rest live in the flow.
        assertEquals(1, firstSuccesses.size)
    }

    /** An honest compass-seeded first solve routinely implies a correction of this size --
     *  magnetometer error near a telescope's own steel/motors is ordinary, not a sign of a false
     *  match -- so it must be accepted, not just a small "drift" correction. */
    @Test
    fun aLargeButPlausibleFirstCorrection_isStillAccepted() = runTest {
        val refiner = AutoPlateSolveRefiner(
            scope = backgroundScope,
            orientationSensor = stillSensor(),
            boresightDeviceVector = MutableStateFlow(TelescopeAxis.DEFAULT.deviceVector),
            attemptSolve = { attempt(correctionDegrees = 30.0) },
            nowEpochMillis = { testScheduler.currentTime },
        )

        refiner.setActive(true)
        advanceTimeBy(ENOUGH_FOR_SEVERAL_CYCLES_MILLIS)
        refiner.setActive(false)

        assertNotNull(refiner.current.value)
        assertEquals(PlateSolveStatus.SUCCEEDED, refiner.status.value)
    }

    /** No user is reviewing these, so an implausibly large correction -- a false match that
     *  happened to pass its own inlier check -- has to be dropped by the refiner itself. */
    @Test
    fun anImplausiblyLargeCorrection_isRejected() = runTest {
        var persisted = 0
        val refiner = AutoPlateSolveRefiner(
            scope = backgroundScope,
            orientationSensor = stillSensor(),
            boresightDeviceVector = MutableStateFlow(TelescopeAxis.DEFAULT.deviceVector),
            attemptSolve = { attempt(correctionDegrees = 90.0) },
            onFirstSuccess = { persisted++ },
            nowEpochMillis = { testScheduler.currentTime },
        )

        refiner.setActive(true)
        advanceTimeBy(ENOUGH_FOR_SEVERAL_CYCLES_MILLIS)
        refiner.setActive(false)

        assertNull(refiner.current.value)
        assertEquals(0, persisted)
    }

    @Test
    fun aFailedSolve_leavesTheReferenceAlone() = runTest {
        val refiner = AutoPlateSolveRefiner(
            scope = backgroundScope,
            orientationSensor = stillSensor(),
            boresightDeviceVector = MutableStateFlow(TelescopeAxis.DEFAULT.deviceVector),
            attemptSolve = { failure },
            nowEpochMillis = { testScheduler.currentTime },
        )

        refiner.setActive(true)
        advanceTimeBy(ENOUGH_FOR_SEVERAL_CYCLES_MILLIS)
        refiner.setActive(false)

        assertNull(refiner.current.value)
    }

    @Test
    fun statusAndLastOutcome_trackTheMostRecentAttempt() = runTest {
        val refiner = AutoPlateSolveRefiner(
            scope = backgroundScope,
            orientationSensor = stillSensor(),
            boresightDeviceVector = MutableStateFlow(TelescopeAxis.DEFAULT.deviceVector),
            attemptSolve = { failure },
            nowEpochMillis = { testScheduler.currentTime },
        )

        assertEquals(PlateSolveStatus.IDLE, refiner.status.value)

        refiner.setActive(true)
        advanceTimeBy(ENOUGH_FOR_SEVERAL_CYCLES_MILLIS)
        refiner.setActive(false)

        assertEquals(PlateSolveStatus.FAILED, refiner.status.value)
        val outcome = assertIs<PlateSolveOutcome.Failure>(refiner.lastOutcome.value)
        assertEquals(PlateSolveFailureReason.TOO_FEW_DETECTIONS, outcome.reason)
    }

    @Test
    fun aRejectedLargeCorrection_reportsFailedWithTheCorrectionItself() = runTest {
        val refiner = AutoPlateSolveRefiner(
            scope = backgroundScope,
            orientationSensor = stillSensor(),
            boresightDeviceVector = MutableStateFlow(TelescopeAxis.DEFAULT.deviceVector),
            attemptSolve = { attempt(correctionDegrees = 90.0) },
            nowEpochMillis = { testScheduler.currentTime },
        )

        refiner.setActive(true)
        advanceTimeBy(ENOUGH_FOR_SEVERAL_CYCLES_MILLIS)
        refiner.setActive(false)

        assertEquals(PlateSolveStatus.FAILED, refiner.status.value)
        val outcome = assertIs<PlateSolveOutcome.Failure>(refiner.lastOutcome.value)
        assertEquals(PlateSolveFailureReason.CORRECTION_TOO_LARGE, outcome.reason)
        assertEquals(90.0, outcome.correctionDegrees)
    }

    /** The fit stays true after the user leaves the guidance screen; clearing it would drop every
     *  map back to the compass for no reason. */
    @Test
    fun deactivating_keepsTheLastPublishedReference() = runTest {
        val refiner = AutoPlateSolveRefiner(
            scope = backgroundScope,
            orientationSensor = stillSensor(),
            boresightDeviceVector = MutableStateFlow(TelescopeAxis.DEFAULT.deviceVector),
            attemptSolve = { attempt(correctionDegrees = 1.0) },
            nowEpochMillis = { testScheduler.currentTime },
        )

        refiner.setActive(true)
        advanceTimeBy(ENOUGH_FOR_SEVERAL_CYCLES_MILLIS)
        refiner.setActive(false)
        advanceTimeBy(ENOUGH_FOR_SEVERAL_CYCLES_MILLIS)

        assertNotNull(refiner.current.value)
    }

    /** Nothing is photographed until the telescope holds still -- a sensor that never reports
     *  leaves the loop waiting rather than solving on whatever it last saw. */
    @Test
    fun withoutASensorReading_nothingIsSolved() = runTest {
        var solves = 0
        val refiner = AutoPlateSolveRefiner(
            scope = backgroundScope,
            orientationSensor = FakeOrientationSensor(),
            boresightDeviceVector = MutableStateFlow(TelescopeAxis.DEFAULT.deviceVector),
            attemptSolve = { solves++; attempt(correctionDegrees = 1.0) },
            nowEpochMillis = { testScheduler.currentTime },
        )

        refiner.setActive(true)
        advanceTimeBy(ENOUGH_FOR_SEVERAL_CYCLES_MILLIS)
        refiner.setActive(false)

        assertEquals(0, solves)
    }
}
