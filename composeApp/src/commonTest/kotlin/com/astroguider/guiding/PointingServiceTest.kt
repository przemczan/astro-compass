@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.astroguider.guiding

import com.astroguider.alignment.AlignmentModel
import com.astroguider.astro.Quaternion
import com.astroguider.astro.Vector3
import com.astroguider.sensors.FakeOrientationSensor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PointingServiceTest {

    private val identityModel = AlignmentModel(
        sensorToSky = Quaternion.IDENTITY,
        points = emptyList(),
        rmsResidualDegrees = 0.0,
        computedAtEpochMillis = 0L,
    )

    @Test
    fun beforeAnySyncOrReading_currentSkyDirectionIsNull_andNotAligned() = runTest {
        val sensor = FakeOrientationSensor()
        val reference = AlignmentAbsoluteReference()
        val service = PointingService(backgroundScope, sensor, reference, MutableStateFlow(TelescopeAxis.TOP_EDGE))
        runCurrent()

        assertNull(service.currentSkyDirection.value)
        assertFalse(service.isAligned.value)
    }

    @Test
    fun afterSyncAndReading_currentSkyDirectionIsTheRotatedTelescopeAxis() = runTest {
        val sensor = FakeOrientationSensor()
        val reference = AlignmentAbsoluteReference()
        val service = PointingService(backgroundScope, sensor, reference, MutableStateFlow(TelescopeAxis.TOP_EDGE))

        reference.update(identityModel)
        sensor.emit(Quaternion.IDENTITY, timestampMillis = 0L)
        runCurrent()

        assertTrue(service.isAligned.value)
        // Identity sensor reading + identity alignment: the pointing direction is exactly the
        // configured telescope axis (TOP_EDGE = (0, 1, 0)).
        assertEquals(Vector3(0.0, 1.0, 0.0), service.currentSkyDirection.value)
    }
}
