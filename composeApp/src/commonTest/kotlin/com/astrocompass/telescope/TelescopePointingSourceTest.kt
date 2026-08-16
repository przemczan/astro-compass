@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.astrocompass.telescope

import com.astrocompass.astro.Angle
import com.astrocompass.astro.coords.EquatorialCoordinates
import com.astrocompass.astro.time.currentEpochMillis
import com.astrocompass.location.ObserverLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SOME_LOCATION = ObserverLocation(latitude = Angle.ofDegrees(51.5), longitude = Angle.ZERO)
private const val STALE_THRESHOLD_MILLIS = 5_000L
private val SOME_ENDPOINT = TelescopeEndpoint(TelescopeTransportKind.TCP, "Test mount", host = "127.0.0.1", port = 4030)

class TelescopePointingSourceTest {

    @Test
    fun notReadyBeforeAnyReport() = runTest {
        val connection = FakeTelescopeConnection()
        val location = MutableStateFlow<ObserverLocation?>(SOME_LOCATION)
        val source = TelescopePointingSource(backgroundScope, connection, location, STALE_THRESHOLD_MILLIS)
        runCurrent()

        assertFalse(source.isReady.value)
        assertNull(source.currentSkyDirection.value)
    }

    @Test
    fun notReadyWhenNotConnectedEvenWithAFreshReport() = runTest {
        val connection = FakeTelescopeConnection()
        connection.setReportedPosition(TelescopeReport(EquatorialCoordinates(Angle.ZERO, Angle.ZERO), currentEpochMillis()))
        val location = MutableStateFlow<ObserverLocation?>(SOME_LOCATION)
        val source = TelescopePointingSource(backgroundScope, connection, location, STALE_THRESHOLD_MILLIS)
        runCurrent()

        assertFalse(source.isReady.value)
    }

    @Test
    fun readyWithAFreshReportWhileConnected() = runTest {
        val connection = FakeTelescopeConnection()
        connection.setState(TelescopeConnectionState.Connected(SOME_ENDPOINT))
        connection.setReportedPosition(
            TelescopeReport(EquatorialCoordinates(Angle.ofHms(18, 36, 56.0), Angle.ofDms(38, 47, 1.0)), currentEpochMillis()),
        )
        val location = MutableStateFlow<ObserverLocation?>(SOME_LOCATION)
        val source = TelescopePointingSource(backgroundScope, connection, location, STALE_THRESHOLD_MILLIS)
        runCurrent()

        assertTrue(source.isReady.value)
        val direction = assertNotNull(source.currentSkyDirection.value)
        val magnitude = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        assertTrue(magnitude in 0.999..1.001, "expected a unit ENU vector, got magnitude $magnitude")
    }

    @Test
    fun notReadyWhenTheLastReportIsOlderThanTheStaleThreshold() = runTest {
        val connection = FakeTelescopeConnection()
        connection.setState(TelescopeConnectionState.Connected(SOME_ENDPOINT))
        connection.setReportedPosition(
            TelescopeReport(EquatorialCoordinates(Angle.ZERO, Angle.ZERO), currentEpochMillis() - STALE_THRESHOLD_MILLIS - 10_000),
        )
        val location = MutableStateFlow<ObserverLocation?>(SOME_LOCATION)
        val source = TelescopePointingSource(backgroundScope, connection, location, STALE_THRESHOLD_MILLIS)
        runCurrent()

        assertFalse(source.isReady.value)
    }

    @Test
    fun noDirectionWithoutALocationEvenWhenReady() = runTest {
        val connection = FakeTelescopeConnection()
        connection.setState(TelescopeConnectionState.Connected(SOME_ENDPOINT))
        connection.setReportedPosition(TelescopeReport(EquatorialCoordinates(Angle.ZERO, Angle.ZERO), currentEpochMillis()))
        val location = MutableStateFlow<ObserverLocation?>(null)
        val source = TelescopePointingSource(backgroundScope, connection, location, STALE_THRESHOLD_MILLIS)
        runCurrent()

        assertNull(source.currentSkyDirection.value)
    }
}
