package com.astrocompass.ui.skymap

import com.astrocompass.astro.Angle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkyMapViewportTest {

    private val base = SkyMapViewport(
        centerAzimuth = Angle.ofDegrees(180.0),
        centerAltitude = Angle.ofDegrees(0.0),
        fieldOfViewDegrees = 90.0,
    )

    @Test
    fun panningRight_decreasesAzimuth() {
        val panned = base.pannedBy(dxPixels = 100f, dyPixels = 0f, referenceSizePixels = 1000f)
        // 90 deg FOV over 1000px reference = 0.09 deg/px; at altitude 0, cos(alt) = 1.
        assertEquals(180.0 - 100 * 0.09, panned.centerAzimuth.degrees, absoluteTolerance = 1e-6)
    }

    @Test
    fun panningDown_increasesAltitude() {
        val panned = base.pannedBy(dxPixels = 0f, dyPixels = 100f, referenceSizePixels = 1000f)
        assertEquals(9.0, panned.centerAltitude.degrees, absoluteTolerance = 1e-6)
    }

    @Test
    fun altitudePanning_clampsAtThePoles() {
        val towardZenith = base.copy(centerAltitude = Angle.ofDegrees(85.0))
            .pannedBy(dxPixels = 0f, dyPixels = 10000f, referenceSizePixels = 1000f)
        assertEquals(90.0, towardZenith.centerAltitude.degrees, absoluteTolerance = 1e-9)

        val towardNadir = base.copy(centerAltitude = Angle.ofDegrees(-85.0))
            .pannedBy(dxPixels = 0f, dyPixels = -10000f, referenceSizePixels = 1000f)
        assertEquals(-90.0, towardNadir.centerAltitude.degrees, absoluteTolerance = 1e-9)
    }

    @Test
    fun azimuthPanning_wrapsAroundZero() {
        val panned = base.copy(centerAzimuth = Angle.ofDegrees(5.0))
            .pannedBy(dxPixels = 200f, dyPixels = 0f, referenceSizePixels = 1000f)
        // 5 - 18 = -13 -> normalized to 347.
        assertEquals(347.0, panned.centerAzimuth.degrees, absoluteTolerance = 1e-6)
    }

    @Test
    fun azimuthPanning_isFasterNearTheZenith() {
        val nearZenith = base.copy(centerAltitude = Angle.ofDegrees(89.0))
        val panned = nearZenith.pannedBy(dxPixels = 10f, dyPixels = 0f, referenceSizePixels = 1000f)
        val azimuthDelta = (nearZenith.centerAzimuth.degrees - panned.centerAzimuth.degrees)
        assertTrue(azimuthDelta > 10 * 0.09, "Expected amplified azimuth pan near zenith, got $azimuthDelta")
    }

    @Test
    fun zoomIn_narrowsFieldOfView() {
        val zoomed = base.zoomedBy(2f)
        assertEquals(45.0, zoomed.fieldOfViewDegrees, absoluteTolerance = 1e-9)
    }

    @Test
    fun zoomOut_wideningClampsAtMax() {
        val zoomed = base.copy(fieldOfViewDegrees = 170.0).zoomedBy(0.1f)
        assertEquals(180.0, zoomed.fieldOfViewDegrees, absoluteTolerance = 1e-9)
    }

    @Test
    fun zoomIn_narrowingClampsAtMin() {
        val zoomed = base.copy(fieldOfViewDegrees = 1.0).zoomedBy(100f)
        assertEquals(0.5, zoomed.fieldOfViewDegrees, absoluteTolerance = 1e-9)
    }
}
