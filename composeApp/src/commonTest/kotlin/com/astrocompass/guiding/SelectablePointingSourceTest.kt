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

private val TELESCOPE_DIRECTION = Vector3(1.0, 0.0, 0.0)
private val PHONE_DIRECTION = Vector3(0.0, 1.0, 0.0)

class SelectablePointingSourceTest {

    @Test
    fun manualModeServesThePhoneSourceEvenWithAReadyTelescope() = runTest {
        val telescope = telescopeSource().apply { setReady(true); setDirection(TELESCOPE_DIRECTION) }
        val phone = FakeSkyPointingSource().apply { setReady(true); setDirection(PHONE_DIRECTION) }
        val source = SelectablePointingSource(backgroundScope, MutableStateFlow(GuidingMode.MANUAL), telescope, phone)
        runCurrent()

        assertEquals(PHONE_DIRECTION, source.currentSkyDirection.value)
        assertEquals(PointingOrigin.PHONE_SENSORS, source.origin.value)
    }

    @Test
    fun telescopeModeServesTheTelescopeSource() = runTest {
        val telescope = telescopeSource().apply { setReady(true); setDirection(TELESCOPE_DIRECTION) }
        val phone = FakeSkyPointingSource().apply { setReady(true); setDirection(PHONE_DIRECTION) }
        val source = SelectablePointingSource(backgroundScope, MutableStateFlow(GuidingMode.TELESCOPE), telescope, phone)
        runCurrent()

        assertEquals(TELESCOPE_DIRECTION, source.currentSkyDirection.value)
        assertEquals(PointingOrigin.TELESCOPE, source.origin.value)
    }

    @Test
    fun telescopeModeStaysUnreadyWhileTheMountHasNotReportedYet() = runTest {
        // The whole reason this isn't a readiness-based fallback: a mount that hasn't reported a
        // position must leave the screen saying so, not quietly serve phone pointing under a
        // Telescope label.
        val telescope = telescopeSource() // never ready, no direction
        val phone = FakeSkyPointingSource().apply { setReady(true); setDirection(PHONE_DIRECTION) }
        val source = SelectablePointingSource(backgroundScope, MutableStateFlow(GuidingMode.TELESCOPE), telescope, phone)
        runCurrent()

        assertFalse(source.isReady.value)
        assertNull(source.currentSkyDirection.value)
    }

    @Test
    fun switchingModeSwitchesEveryPropertyTogether() = runTest {
        val telescope = telescopeSource().apply { setReady(true); setDirection(TELESCOPE_DIRECTION) }
        val phone = FakeSkyPointingSource().apply { setReady(false); setDirection(PHONE_DIRECTION) }
        val mode = MutableStateFlow(GuidingMode.TELESCOPE)
        val source = SelectablePointingSource(backgroundScope, mode, telescope, phone)
        runCurrent()

        mode.value = GuidingMode.MANUAL
        runCurrent()

        assertEquals(PHONE_DIRECTION, source.currentSkyDirection.value)
        assertEquals(PointingOrigin.PHONE_SENSORS, source.origin.value)
        assertFalse(source.isReady.value)
    }

    private fun telescopeSource() = FakeSkyPointingSource(MutableStateFlow(PointingOrigin.TELESCOPE))
}
