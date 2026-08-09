@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.astrocompass.guiding

import com.astrocompass.alignment.AlignmentModel
import com.astrocompass.astro.Quaternion
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Two [AlignmentAbsoluteReference]s stand in for "a star fit" and "the compass" here -- what is
 *  under test is the selection rule, which depends only on nullness, not on how either side
 *  produced its state. */
class PrioritizedAbsoluteReferenceTest {

    private fun modelWithResidual(rmsResidualDegrees: Double) = AlignmentModel(
        sensorToSky = Quaternion.IDENTITY,
        points = emptyList(),
        rmsResidualDegrees = rmsResidualDegrees,
        computedAtEpochMillis = 0L,
    )

    @Test
    fun withNeitherSideAvailable_thereIsNoReference() = runTest {
        val reference = PrioritizedAbsoluteReference(
            backgroundScope,
            preferred = AlignmentAbsoluteReference(),
            fallback = AlignmentAbsoluteReference(),
        )
        runCurrent()

        assertNull(reference.current.value)
    }

    @Test
    fun withOnlyTheFallbackAvailable_theFallbackShowsThrough() = runTest {
        val fallback = AlignmentAbsoluteReference().apply { update(modelWithResidual(9.0)) }
        val reference = PrioritizedAbsoluteReference(backgroundScope, AlignmentAbsoluteReference(), fallback)
        runCurrent()

        assertEquals(9.0, reference.current.value?.uncertaintyDegrees)
    }

    @Test
    fun withBothAvailable_thePreferredSideWinsOutright() = runTest {
        val preferred = AlignmentAbsoluteReference().apply { update(modelWithResidual(1.0)) }
        val fallback = AlignmentAbsoluteReference().apply { update(modelWithResidual(9.0)) }
        val reference = PrioritizedAbsoluteReference(backgroundScope, preferred, fallback)
        runCurrent()

        assertEquals(1.0, reference.current.value?.uncertaintyDegrees)
    }

    /** The `clearAlignment` path: dropping the star fit must land on the fallback, not on
     *  "no reference at all". */
    @Test
    fun clearingThePreferredSide_fallsBackRatherThanGoingNull() = runTest {
        val preferred = AlignmentAbsoluteReference().apply { update(modelWithResidual(1.0)) }
        val fallback = AlignmentAbsoluteReference().apply { update(modelWithResidual(9.0)) }
        val reference = PrioritizedAbsoluteReference(backgroundScope, preferred, fallback)
        runCurrent()

        preferred.update(null)
        runCurrent()

        assertEquals(9.0, reference.current.value?.uncertaintyDegrees)
    }
}
