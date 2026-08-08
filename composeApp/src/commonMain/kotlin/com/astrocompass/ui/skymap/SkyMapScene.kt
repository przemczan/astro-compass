package com.astrocompass.ui.skymap

import com.astrocompass.astro.Angle
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.coords.HorizontalCoordinates
import com.astrocompass.astro.projection.PlanePoint
import com.astrocompass.astro.projection.StereographicProjection
import com.astrocompass.catalog.SkyObject
import com.astrocompass.catalog.SolarSystemObject
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/** Bounds how many objects [SkyMapScene.build] ever returns. Nothing scales the draw count down
 *  automatically, and a dense field (the galactic plane, the Virgo cluster) could stay slow
 *  regardless of the magnitude-vs-FOV curve below -- the same hazard `PLATE_SOLVE_TIMEOUT_MILLIS`
 *  guards against on the plate-solve path (see `AppContainer`). */
private const val DEFAULT_MAX_DRAWN_OBJECTS = 600

/** Objects with no known magnitude ([Float.NaN], e.g. many dark nebulae) are treated as very
 *  faint for the FOV-based limit below, unlike [com.astrocompass.catalog.CatalogSearch]'s
 *  "never dropped by magnitude" convention -- a text search's result set is already small from
 *  the query match, but an unbounded set of magnitude-less DSOs would clutter a wide-FOV map
 *  long before [DEFAULT_MAX_DRAWN_OBJECTS] kicks in. [SolarSystemObject] is the one exception:
 *  its magnitude is *also* always [Float.NaN] (this app doesn't compute a live apparent
 *  magnitude), but there are only a handful of them and they're exactly the kind of prominent,
 *  always-relevant object a sky map should never hide -- see [effectiveMagnitude]. */
private const val NO_MAGNITUDE_SENTINEL = 99f

/** Guarantees a [SolarSystemObject] passes the FOV magnitude limit at every zoom level and sorts
 *  first (so the [DEFAULT_MAX_DRAWN_OBJECTS] cap never drops it either) -- brighter than any real
 *  star or DSO magnitude in the catalog. */
private const val SOLAR_SYSTEM_BODY_SENTINEL = -10f

/** One catalog object placed on the map: its projected plane position plus the ENU direction it
 *  came from (kept for markers/hit-testing that need the direction, not just the pixel spot). */
data class ProjectedObject(
    val skyObject: SkyObject,
    val point: PlanePoint,
    val direction: Vector3,
)

object SkyMapScene {

    /** [build] culls objects to slightly *more* than the canvas's exact bounds, matching how the
     *  Compose layer culls constellation lines/the horizon (also to this same margin) -- both rely
     *  on the `Canvas` composable's own clip to make the true edge crisp, rather than culling each
     *  system to a different rectangle. Culling objects to the exact bounds while lines got this
     *  same slack made objects visibly stop short of where lines still reached. */
    const val VIEWPORT_BOUNDS_MARGIN = 1.5

    /** Pixels per unit of the projection's plane, given the current field of view and the
     *  canvas's reference side (the shorter one -- the side [fieldOfViewDegrees] is measured
     *  across, so a wider canvas shows more sky rather than a stretched view). Shared by [build]
     *  (to cull against canvas bounds) and the Compose layer (to place [ProjectedObject.point] on
     *  screen), so the two never drift apart. */
    fun pixelsPerPlaneUnit(fieldOfViewDegrees: Double, referenceSizePixels: Float): Float {
        val halfAngleRadians = Angle.ofDegrees(fieldOfViewDegrees / 2.0).radians
        val halfWidthPlaneUnits = 2.0 * tan(halfAngleRadians / 2.0)
        return (referenceSizePixels / 2.0 / halfWidthPlaneUnits).toFloat()
    }

    /** The projection a [viewport] implies -- centered on its alt-az direction, zenith-up. Shared
     *  by [build] and the Compose layer (for the horizon line and cardinal points), so both agree
     *  on exactly where "center" and "up" are. */
    fun projectionFor(viewport: SkyMapViewport): StereographicProjection {
        val center = HorizontalCoordinates(viewport.centerAzimuth, viewport.centerAltitude).toEnu()
        return StereographicProjection(center)
    }

    fun build(
        directions: List<Pair<SkyObject, Vector3>>,
        viewport: SkyMapViewport,
        canvasWidth: Float,
        canvasHeight: Float,
        maxObjects: Int = DEFAULT_MAX_DRAWN_OBJECTS,
    ): List<ProjectedObject> {
        if (canvasWidth <= 0f || canvasHeight <= 0f) return emptyList()

        val center = HorizontalCoordinates(viewport.centerAzimuth, viewport.centerAltitude).toEnu()
        val projection = projectionFor(viewport)
        val pixelsPerUnit = pixelsPerPlaneUnit(viewport.fieldOfViewDegrees, min(canvasWidth, canvasHeight))
        val halfWidthUnits = (canvasWidth / 2.0) / pixelsPerUnit * VIEWPORT_BOUNDS_MARGIN
        val halfHeightUnits = (canvasHeight / 2.0) / pixelsPerUnit * VIEWPORT_BOUNDS_MARGIN
        val magnitudeLimit = magnitudeLimitFor(viewport.fieldOfViewDegrees)

        // A cone cull before projecting -- cheap (one dot product) and skips the trig in
        // StereographicProjection.project for everything far outside the current view. Derived
        // from the canvas's actual corner distance (exactly, via the stereographic radius/angle
        // relation) rather than a flat degree margin -- a fixed margin would need re-tuning every
        // time VIEWPORT_BOUNDS_MARGIN or the canvas's aspect ratio changed, and silently under-cull
        // (dropping objects the exact rectangular check below would otherwise have kept) if it
        // ever fell behind either.
        val cornerPlaneRadius = sqrt(halfWidthUnits * halfWidthUnits + halfHeightUnits * halfHeightUnits)
        val cullHalfAngleRadians = (2.0 * atan(cornerPlaneRadius / 2.0)).coerceAtMost(PI - 0.01)
        val cullCosine = cos(cullHalfAngleRadians)

        return directions.asSequence()
            .filter { (obj, _) -> effectiveMagnitude(obj) <= magnitudeLimit }
            .filter { (_, direction) -> (direction dot center) >= cullCosine }
            .mapNotNull { (obj, direction) ->
                val point = projection.project(direction) ?: return@mapNotNull null
                if (point.x < -halfWidthUnits || point.x > halfWidthUnits ||
                    point.y < -halfHeightUnits || point.y > halfHeightUnits
                ) {
                    null
                } else {
                    ProjectedObject(obj, point, direction)
                }
            }
            .sortedBy { effectiveMagnitude(it.skyObject) }
            .take(maxObjects)
            .toList()
    }

    /** The closest projected object to [tapPoint], within [maxDistance] (both in the same plane
     *  units as [ProjectedObject.point] -- the caller converts a raw pixel tap using
     *  [pixelsPerPlaneUnit]), or null if nothing is that close. */
    fun nearest(scene: List<ProjectedObject>, tapPoint: PlanePoint, maxDistance: Double): ProjectedObject? {
        var best: ProjectedObject? = null
        var bestDistance = maxDistance
        for (candidate in scene) {
            val dx = candidate.point.x - tapPoint.x
            val dy = candidate.point.y - tapPoint.y
            val distance = sqrt(dx * dx + dy * dy)
            if (distance <= bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        return best
    }

    /** Wide views would be cluttered by every named mag-7 star; narrow views should show
     *  everything the catalog has. Linear in field of view between those two extremes, reaching
     *  the 16.0 ceiling by [narrowFov] -- deliberately a moderate 20°, not some much smaller
     *  angle: dso.bin's median magnitude is ~14, so a curve that only opened up at an extreme
     *  close-in zoom left galaxies/nebulae sparse at any FOV a user would actually browse at.
     *  20° still reaches [SkyMapViewport.DEFAULT]'s 90° FOV at magnitude ~10 (584 of 13372 DSOs),
     *  not the full ceiling immediately, so wide views stay reasonably decluttered. The ceiling
     *  itself is 16, not [com.astrocompass.catalog.CatalogRepository]'s star cutoff of 7 --
     *  stars.bin is pre-filtered to mag <= 7 at build time so raising this doesn't add star
     *  clutter, but dso.bin is not. */
    private fun magnitudeLimitFor(fieldOfViewDegrees: Double): Float {
        val wideFov = 180.0
        val narrowFov = 20.0
        val t = ((wideFov - fieldOfViewDegrees) / (wideFov - narrowFov)).coerceIn(0.0, 1.0)
        return (2.5 + t * (16.0 - 2.5)).toFloat()
    }

    private fun effectiveMagnitude(obj: SkyObject): Float = when {
        obj is SolarSystemObject -> SOLAR_SYSTEM_BODY_SENTINEL
        obj.magnitude.isNaN() -> NO_MAGNITUDE_SENTINEL
        else -> obj.magnitude
    }
}
