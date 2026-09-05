@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.astrocompass.guiding

import com.astrocompass.alignment.AlignmentModel
import com.astrocompass.astro.Angle
import com.astrocompass.astro.Quaternion
import com.astrocompass.astro.coords.EquatorialCoordinates
import com.astrocompass.platesolve.PlateSolveResult
import com.astrocompass.sensors.FakeOrientationSensor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Comfortably longer than one full cycle (stillness hold + the gap after a solve), so a loop that
 *  is going to publish has had every chance to. */
private const val ENOUGH_FOR_SEVERAL_CYCLES_MILLIS = 30_000L

class AutoPlateSolveRefinerTest {

    private fun attempt(correctionDegrees: Double, rmsResidualDegrees: Double = 0.2) = PlateSolveAttempt(
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
    )

    /** A sensor held perfectly still, which is what the loop waits for before each solve. */
    private fun stillSensor() = FakeOrientationSensor().apply { emit(Quaternion.IDENTITY, 0L) }

    @Test
    fun aPlausibleCorrection_becomesTheAbsoluteReference() = runTest {
        val firstSuccesses = mutableListOf<PlateSolveAttempt>()
        val refiner = AutoPlateSolveRefiner(
            scope = backgroundScope,
            orientationSensor = stillSensor(),
            telescopeAxis = MutableStateFlow(TelescopeAxis.DEFAULT),
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

    /** No user is reviewing these, so an implausibly large correction -- a false match, or the
     *  wrong camera-mounting preset -- has to be dropped by the refiner itself. */
    @Test
    fun anImplausiblyLargeCorrection_isRejected() = runTest {
        var persisted = 0
        val refiner = AutoPlateSolveRefiner(
            scope = backgroundScope,
            orientationSensor = stillSensor(),
            telescopeAxis = MutableStateFlow(TelescopeAxis.DEFAULT),
            attemptSolve = { attempt(correctionDegrees = 40.0) },
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
            telescopeAxis = MutableStateFlow(TelescopeAxis.DEFAULT),
            attemptSolve = { null },
            nowEpochMillis = { testScheduler.currentTime },
        )

        refiner.setActive(true)
        advanceTimeBy(ENOUGH_FOR_SEVERAL_CYCLES_MILLIS)
        refiner.setActive(false)

        assertNull(refiner.current.value)
    }

    /** The fit stays true after the user leaves the guidance screen; clearing it would drop every
     *  map back to the compass for no reason. */
    @Test
    fun deactivating_keepsTheLastPublishedReference() = runTest {
        val refiner = AutoPlateSolveRefiner(
            scope = backgroundScope,
            orientationSensor = stillSensor(),
            telescopeAxis = MutableStateFlow(TelescopeAxis.DEFAULT),
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
            telescopeAxis = MutableStateFlow(TelescopeAxis.DEFAULT),
            attemptSolve = { solves++; attempt(correctionDegrees = 1.0) },
            nowEpochMillis = { testScheduler.currentTime },
        )

        refiner.setActive(true)
        advanceTimeBy(ENOUGH_FOR_SEVERAL_CYCLES_MILLIS)
        refiner.setActive(false)

        assertEquals(0, solves)
    }
}
