@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.astrocompass.guiding

import com.astrocompass.alignment.AlignmentModel
import com.astrocompass.astro.Quaternion
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [AlignmentAbsoluteReference] stands in for both peers here -- what is under test is the
 *  age comparison, which depends only on `computedAtEpochMillis`, not on which mechanism made the
 *  fit. */
class FreshestAbsoluteReferenceTest {

    private fun modelAt(computedAtEpochMillis: Long, rmsResidualDegrees: Double) = AlignmentModel(
        sensorToSky = Quaternion.IDENTITY,
        points = emptyList(),
        rmsResidualDegrees = rmsResidualDegrees,
        computedAtEpochMillis = computedAtEpochMillis,
    )

    @Test
    fun withNoSourceAvailable_thereIsNoReference() = runTest {
        val reference = FreshestAbsoluteReference(
            backgroundScope,
            AlignmentAbsoluteReference(),
            AlignmentAbsoluteReference(),
        )
        runCurrent()

        assertNull(reference.current.value)
    }

    @Test
    fun withOnlyOneAvailable_thatOneIsUsedRegardlessOfOrder() = runTest {
        val later = AlignmentAbsoluteReference().apply { update(modelAt(5_000L, 3.0)) }
        val reference = FreshestAbsoluteReference(backgroundScope, AlignmentAbsoluteReference(), later)
        runCurrent()

        assertEquals(3.0, reference.current.value?.uncertaintyDegrees)
    }

    @Test
    fun withBothAvailable_theMoreRecentOneWins() = runTest {
        val older = AlignmentAbsoluteReference().apply { update(modelAt(1_000L, 1.0)) }
        val newer = AlignmentAbsoluteReference().apply { update(modelAt(9_000L, 2.0)) }
        val reference = FreshestAbsoluteReference(backgroundScope, older, newer)
        runCurrent()

        assertEquals(2.0, reference.current.value?.uncertaintyDegrees)
    }

    /** The case a fixed priority got wrong: a background plate solve that has stopped updating must
     *  not shadow an alignment the user has just finished. */
    @Test
    fun aFreshUpdateOnTheOtherSide_takesOver() = runTest {
        val stalePlateSolve = AlignmentAbsoluteReference().apply { update(modelAt(1_000L, 1.0)) }
        val starAlignment = AlignmentAbsoluteReference()
        val reference = FreshestAbsoluteReference(backgroundScope, stalePlateSolve, starAlignment)
        runCurrent()

        starAlignment.update(modelAt(60_000L, 4.0))
        runCurrent()

        assertEquals(4.0, reference.current.value?.uncertaintyDegrees)
    }
}
