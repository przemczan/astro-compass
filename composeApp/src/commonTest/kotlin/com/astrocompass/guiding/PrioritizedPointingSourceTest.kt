@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.astrocompass.guiding

import com.astrocompass.astro.Vector3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [FakeSkyPointingSource] stands in for both "the telescope" and "the phone" here -- what is
 *  under test is the selection rule, which depends only on readiness, not on how either side
 *  produced its state. */
class PrioritizedPointingSourceTest {

    @Test
    fun withNeitherSideReady_isNotReadyAndHasNoDirection() = runTest {
        val source = PrioritizedPointingSource(backgroundScope, FakeSkyPointingSource(), FakeSkyPointingSource())
        runCurrent()

        assertFalse(source.isReady.value)
        assertNull(source.currentSkyDirection.value)
    }

    @Test
    fun withOnlyTheFallbackReady_theFallbackShowsThrough() = runTest {
        val preferred = FakeSkyPointingSource(origin = MutableStateFlow(PointingOrigin.TELESCOPE))
        val fallback = FakeSkyPointingSource().apply {
            setReady(true)
            setDirection(Vector3(0.0, 0.0, 1.0))
        }
        val source = PrioritizedPointingSource(backgroundScope, preferred, fallback)
        runCurrent()

        assertTrue(source.isReady.value)
        assertEquals(PointingOrigin.PHONE_SENSORS, source.origin.value)
        assertEquals(Vector3(0.0, 0.0, 1.0), source.currentSkyDirection.value)
    }

    @Test
    fun withBothReady_thePreferredSideWinsOutright() = runTest {
        val preferred = FakeSkyPointingSource(origin = MutableStateFlow(PointingOrigin.TELESCOPE)).apply {
            setReady(true)
            setDirection(Vector3(1.0, 0.0, 0.0))
        }
        val fallback = FakeSkyPointingSource().apply {
            setReady(true)
            setDirection(Vector3(0.0, 1.0, 0.0))
        }
        val source = PrioritizedPointingSource(backgroundScope, preferred, fallback)
        runCurrent()

        assertEquals(PointingOrigin.TELESCOPE, source.origin.value)
        assertEquals(Vector3(1.0, 0.0, 0.0), source.currentSkyDirection.value)
    }

    /** A dropped telescope connection must land on the phone-based fallback, not on "no
     *  pointing source at all" -- the counterpart of AbsoluteReference's clearAlignment test. */
    @Test
    fun theTelescopeGoingUnready_fallsBackToThePhoneRatherThanGoingNull() = runTest {
        val preferred = FakeSkyPointingSource(origin = MutableStateFlow(PointingOrigin.TELESCOPE)).apply {
            setReady(true)
            setDirection(Vector3(1.0, 0.0, 0.0))
        }
        val fallback = FakeSkyPointingSource().apply {
            setReady(true)
            setDirection(Vector3(0.0, 1.0, 0.0))
        }
        val source = PrioritizedPointingSource(backgroundScope, preferred, fallback)
        runCurrent()

        preferred.setReady(false)
        runCurrent()

        assertEquals(PointingOrigin.PHONE_SENSORS, source.origin.value)
        assertEquals(Vector3(0.0, 1.0, 0.0), source.currentSkyDirection.value)
    }
}
