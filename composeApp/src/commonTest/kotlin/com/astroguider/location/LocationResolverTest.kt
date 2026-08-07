package com.astroguider.location

import com.astroguider.astro.Angle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class FakeLocationProvider(initial: ObserverLocation? = null) : LocationProvider {
    private val _location = MutableStateFlow(initial)
    override val location: StateFlow<ObserverLocation?> = _location
    override fun start() = Unit
    override fun stop() = Unit
    fun emit(value: ObserverLocation?) { _location.value = value }
}

class LocationResolverTest {

    private fun location(lat: Double) = ObserverLocation(Angle.ofDegrees(lat), Angle.ofDegrees(0.0))

    @Test
    fun noManualLocation_usesGps() = runTest {
        val gps = FakeLocationProvider(location(51.5))
        val manual = MutableStateFlow<ObserverLocation?>(null)
        val resolver = LocationResolver(backgroundScope, gps, manual)

        assertEquals(51.5, resolver.resolved.value?.latitude?.degrees)
    }

    @Test
    fun manualLocation_alwaysWinsOverGps() = runTest {
        val gps = FakeLocationProvider(location(51.5))
        val manual = MutableStateFlow(location(10.0))
        val resolver = LocationResolver(backgroundScope, gps, manual)

        assertEquals(10.0, resolver.resolved.value?.latitude?.degrees)
    }

    @Test
    fun neitherSet_resolvesToNull() = runTest {
        val gps = FakeLocationProvider(null)
        val manual = MutableStateFlow<ObserverLocation?>(null)
        val resolver = LocationResolver(backgroundScope, gps, manual)

        assertNull(resolver.resolved.value)
    }
}
