package com.astrocompass.ui.skymap

import com.astrocompass.astro.Angle
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.coords.EquatorialCoordinates
import com.astrocompass.astro.coords.HorizontalCoordinates
import com.astrocompass.astro.projection.PlanePoint
import com.astrocompass.astro.projection.StereographicProjection
import com.astrocompass.astro.ephemeris.SolarSystemBody
import com.astrocompass.catalog.DeepSkyObject
import com.astrocompass.catalog.SkyObjectType
import com.astrocompass.catalog.SolarSystemObject
import com.astrocompass.catalog.StarObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkyMapSceneTest {

    private val centerAzimuth = Angle.ofDegrees(180.0)
    private val centerAltitude = Angle.ofDegrees(45.0)
    private val centerDirection = HorizontalCoordinates(centerAzimuth, centerAltitude).toEnu()

    private fun star(id: Int, magnitude: Float, direction: Vector3 = centerDirection) =
        StarObject(
            hygId = id,
            hip = 0,
            properName = "Star$id",
            bayer = "",
            flamsteed = 0,
            constellation = "",
            j2000 = EquatorialCoordinates(Angle.ZERO, Angle.ZERO),
            magnitude = magnitude,
        ) to direction

    private fun viewport(fieldOfViewDegrees: Double) =
        SkyMapViewport(centerAzimuth, centerAltitude, fieldOfViewDegrees)

    @Test
    fun objectFarOutsideView_isCulled() {
        val farAway = HorizontalCoordinates(Angle.ofDegrees(0.0), Angle.ofDegrees(-45.0)).toEnu()
        val directions = listOf(star(1, 1f), star(2, 1f, farAway))

        val scene = SkyMapScene.build(directions, viewport(10.0), canvasWidth = 1000f, canvasHeight = 1000f)

        assertEquals(1, scene.size)
        assertEquals("Star1", (scene.single().skyObject as StarObject).properName)
    }

    @Test
    fun solarSystemObjects_alwaysVisibleDespiteHavingNoRealMagnitude() {
        // Planets/Sun/Moon carry Float.NaN for magnitude (no live apparent-magnitude calc) -- the
        // same NaN value many magnitude-less DSOs use to mean "hide me until zoomed way in". A
        // planet must not be swept up in that rule.
        val planet = SolarSystemObject(SolarSystemBody.JUPITER) to centerDirection

        val wide = SkyMapScene.build(listOf(planet), viewport(180.0), canvasWidth = 1000f, canvasHeight = 1000f)

        assertEquals(1, wide.size)
    }

    @Test
    fun faintDeepSkyObject_visibleAtNarrowFieldOfView() {
        // The catalog's DSOs run much fainter than stars.bin's mag-7 build-time cutoff (median
        // ~14) -- a magnitude-10 galaxy must still show up once zoomed in close enough.
        val galaxy = DeepSkyObject(
            catalogDesignation = "NGC0001",
            messier = 0,
            type = SkyObjectType.GALAXY,
            j2000 = EquatorialCoordinates(Angle.ZERO, Angle.ZERO),
            magnitude = 10f,
            constellation = "",
            commonName = "",
        ) to centerDirection

        val scene = SkyMapScene.build(listOf(galaxy), viewport(1.0), canvasWidth = 1000f, canvasHeight = 1000f)

        assertEquals(1, scene.size)
    }

    @Test
    fun objectJustBeyondTheUnmarginedEdge_isStillIncluded() {
        // Objects are culled to VIEWPORT_BOUNDS_MARGIN times the canvas's exact half-extent, not
        // the exact extent itself -- matching the margin the Compose layer gives constellation
        // lines/the horizon, so neither system reaches further toward the edge than the other.
        val fieldOfViewDegrees = 90.0
        val canvasSize = 1000f
        val pixelsPerUnit = SkyMapScene.pixelsPerPlaneUnit(fieldOfViewDegrees, canvasSize)
        val unmarginedHalfWidthUnits = (canvasSize / 2.0) / pixelsPerUnit

        val projection = StereographicProjection(centerDirection)
        val justBeyondOldEdge = projection.unproject(PlanePoint(x = unmarginedHalfWidthUnits * 1.2, y = 0.0))
        val directions = listOf(star(1, 1f, justBeyondOldEdge))

        val scene = SkyMapScene.build(directions, viewport(fieldOfViewDegrees), canvasWidth = canvasSize, canvasHeight = canvasSize)

        assertEquals(1, scene.size)
    }

    @Test
    fun magnitudeLimit_risesAsFieldOfViewNarrows() {
        val directions = listOf(star(1, 6.5f))

        val wide = SkyMapScene.build(directions, viewport(180.0), canvasWidth = 1000f, canvasHeight = 1000f)
        val narrow = SkyMapScene.build(directions, viewport(1.0), canvasWidth = 1000f, canvasHeight = 1000f)

        assertTrue(wide.isEmpty(), "Expected a mag 6.5 star hidden at full-sky zoom")
        assertEquals(1, narrow.size)
    }

    @Test
    fun starAlpha_fadesGraduallyAsFieldOfViewNarrows() {
        // A mag 5 star sits inside starMagnitudeLimitFor's fade band at wide-ish FOV and fully
        // inside the limit once zoomed in past its own reveal point -- alpha should ramp smoothly
        // (never popping straight from 0 to 1) rather than the object simply appearing/disappearing.
        val directions = listOf(star(1, 5f))

        val hidden = SkyMapScene.build(directions, viewport(180.0), canvasWidth = 1000f, canvasHeight = 1000f)
        val fading = SkyMapScene.build(directions, viewport(90.0), canvasWidth = 1000f, canvasHeight = 1000f)
        val revealed = SkyMapScene.build(directions, viewport(40.0), canvasWidth = 1000f, canvasHeight = 1000f)

        assertTrue(hidden.isEmpty(), "Expected a mag 5 star fully hidden at full-sky zoom")
        assertEquals(1, fading.size)
        assertTrue(fading.single().alpha in 0f..1f)
        assertTrue(fading.single().alpha < 1f, "Expected a partial fade mid-zoom, not full brightness yet")
        assertEquals(1, revealed.size)
        assertEquals(1f, revealed.single().alpha, "Expected full brightness once zoomed in past the star's reveal point")
    }

    @Test
    fun starAlpha_isFullBrightnessComfortablyInsideTheLimit() {
        val directions = listOf(star(1, 0f))

        val scene = SkyMapScene.build(directions, viewport(180.0), canvasWidth = 1000f, canvasHeight = 1000f)

        assertEquals(1f, scene.single().alpha)
    }

    @Test
    fun drawCap_keepsOnlyTheBrightest() {
        val directions = listOf(0f, 0.5f, 1f, 1.5f, 2f).mapIndexed { index, mag -> star(index, mag) }

        val scene = SkyMapScene.build(directions, viewport(180.0), canvasWidth = 1000f, canvasHeight = 1000f, maxObjects = 3)

        assertEquals(3, scene.size)
        assertEquals(listOf(0f, 0.5f, 1f), scene.map { it.skyObject.magnitude })
    }

    @Test
    fun nearest_picksTheCloserCandidate() {
        val near = ProjectedObject(star(1, 1f).first, PlanePoint(0.1, 0.0), Vector3.UNIT_Z)
        val far = ProjectedObject(star(2, 1f).first, PlanePoint(0.5, 0.0), Vector3.UNIT_Z)

        val hit = SkyMapScene.nearest(listOf(far, near), PlanePoint(0.0, 0.0), maxDistance = 1.0)

        assertEquals(near, hit)
    }

    @Test
    fun nearest_returnsNullBeyondMaxDistance() {
        val candidate = ProjectedObject(star(1, 1f).first, PlanePoint(1.0, 0.0), Vector3.UNIT_Z)

        val hit = SkyMapScene.nearest(listOf(candidate), PlanePoint(0.0, 0.0), maxDistance = 0.5)

        assertNull(hit)
    }
}
