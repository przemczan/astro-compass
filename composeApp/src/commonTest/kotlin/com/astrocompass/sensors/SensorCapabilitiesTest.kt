package com.astrocompass.sensors

import kotlin.test.Test
import kotlin.test.assertEquals

class SensorCapabilitiesTest {

    @Test
    fun gyroscopePresent_selectsGameRotationVector_regardlessOfMagnetometer() {
        val withMag = SensorCapabilities(hasGyroscope = true, hasMagnetometer = true, hasAccelerometer = true)
        val withoutMag = SensorCapabilities(hasGyroscope = true, hasMagnetometer = false, hasAccelerometer = true)
        assertEquals(SensorSource.GAME_ROTATION_VECTOR, withMag.defaultSource())
        assertEquals(SensorSource.GAME_ROTATION_VECTOR, withoutMag.defaultSource())
    }

    @Test
    fun noGyroscope_fallsBackToGeomagneticRotationVector() {
        val capabilities = SensorCapabilities(hasGyroscope = false, hasMagnetometer = true, hasAccelerometer = true)
        assertEquals(SensorSource.GEOMAGNETIC_ROTATION_VECTOR, capabilities.defaultSource())
    }
}
