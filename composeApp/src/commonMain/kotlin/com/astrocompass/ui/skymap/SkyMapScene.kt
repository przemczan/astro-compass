package com.astrocompass.ui.skymap

import com.astrocompass.astro.Angle
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.coords.HorizontalCoordinates
import com.astrocompass.astro.projection.PlanePoint
import com.astrocompass.astro.projection.StereographicProjection
import com.astrocompass.catalog.DeepSkyObject
import com.astrocompass.catalog.SkyObject
import com.astrocompass.catalog.SolarSystemObject
import com.astrocompass.catalog.StarObject
import com.astrocompass.catalog.objectImage
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/** Bounds how many objects [SkyMapScene.build] ever returns. Nothing scales the draw count down
 *  automatically, and a dense field (the galactic plane, the Virgo cluster) could stay slow
 *  regardless of the magnitude-vs-FOV curve below -- the same hazard `PLATE_SOLVE_TIMEOUT_MILLIS`
 *  guards against on the plate-solve path (see `AppContainer`). Kept well above what a typical
 *  view actually holds (measured against the real bundled catalog across several sky directions,
 *  including the real galactic center: stars+DSOs peak around 4700 at field of view ~40 degrees,
 *  DSOs alone driving most of that -- see [magnitudeLimitFor]'s doc comment, which this cap does
 *  not attempt to retune) so [magnitudeLimitFor]/[starMagnitudeLimitFor] -- not this cap -- decide
 *  which objects show; a cap that binds routinely would drop objects by a discrete top-N cutoff
 *  that [FADE_MAGNITUDE_RANGE] can't smooth the way it smooths the magnitude-curve threshold. */
private const val DEFAULT_MAX_DRAWN_OBJECTS = 6000

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

/** Widens [build]'s hard magnitude cutoff by this much (in magnitudes) so an object fades out over
 *  a band instead of vanishing outright the instant field of view crosses its threshold -- see
 *  [ProjectedObject.alpha]. */
private const val FADE_MAGNITUDE_RANGE = 1.0f

/** Worst-case field of view (degrees) any bundled object photo can have -- mirrors
 *  tools/fetch-object-images.mjs's MAX_FOV_DEGREES, kept in lockstep by convention rather than a
 *  shared constant (same relationship as the catalog binary format's field layout). A photo can be
 *  hundreds of pixels across, so its *center* point crossing the culled viewport edge doesn't mean
 *  the photo itself has left the screen -- [build] widens the cull bounds for a [DeepSkyObject]
 *  with a bundled photo by that photo's own real half-extent (see the per-object check below)
 *  rather than dropping it the instant its center does, and this constant only bounds how far the
 *  *cheap* cone pre-filter has to look before that precise per-object check runs. */
private const val MAX_PHOTO_FOV_DEGREES = 5.0

/** A photo's bounding box grows by up to this factor over its own half-extent when rotated
 *  (worst case at 45 degrees) -- see [build]'s cull-margin uses of it. */
private val SQRT_2 = sqrt(2.0)

/** One catalog object placed on the map: its projected plane position plus the ENU direction it
 *  came from (kept for markers/hit-testing that need the direction, not just the pixel spot). */
data class ProjectedObject(
    val skyObject: SkyObject,
    val point: PlanePoint,
    val direction: Vector3,
    /** 1 for an object comfortably brighter than the current magnitude limit, ramping linearly to
     *  0 as its magnitude crosses [FADE_MAGNITUDE_RANGE] beyond that limit. [build] never returns
     *  an object past that point at all. Lets objects fade in/out smoothly as field of view changes
     *  continuously during a pinch gesture, rather than popping at a hard threshold. */
    val alpha: Float = 1f,
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
        val starMagnitudeLimit = starMagnitudeLimitFor(viewport.fieldOfViewDegrees)
        fun limitFor(obj: SkyObject): Float = if (obj is StarObject) starMagnitudeLimit else magnitudeLimit

        // A cone cull before projecting -- cheap (one dot product) and skips the trig in
        // StereographicProjection.project for everything far outside the current view. Derived
        // from the canvas's actual corner distance (exactly, via the stereographic radius/angle
        // relation) rather than a flat degree margin -- a fixed margin would need re-tuning every
        // time VIEWPORT_BOUNDS_MARGIN or the canvas's aspect ratio changed, and silently under-cull
        // (dropping objects the exact rectangular check below would otherwise have kept) if it
        // ever fell behind either. Widened by MAX_PHOTO_FOV_DEGREES's worst case (times sqrt(2) for
        // a photo rotated up to 45 degrees) so this cheap pre-filter can never reject something the
        // precise per-object check below would still have kept.
        val maxPhotoHalfExtentPlaneUnits = Angle.ofDegrees(MAX_PHOTO_FOV_DEGREES / 2.0).radians * SQRT_2
        val cornerPlaneRadius = sqrt(
            (halfWidthUnits + maxPhotoHalfExtentPlaneUnits) * (halfWidthUnits + maxPhotoHalfExtentPlaneUnits) +
                (halfHeightUnits + maxPhotoHalfExtentPlaneUnits) * (halfHeightUnits + maxPhotoHalfExtentPlaneUnits),
        )
        val cullHalfAngleRadians = (2.0 * atan(cornerPlaneRadius / 2.0)).coerceAtMost(PI - 0.01)
        val cullCosine = cos(cullHalfAngleRadians)

        return directions.asSequence()
            .filter { (obj, _) -> effectiveMagnitude(obj) <= limitFor(obj) + FADE_MAGNITUDE_RANGE }
            .filter { (_, direction) -> (direction dot center) >= cullCosine }
            .mapNotNull { (obj, direction) ->
                val point = projection.project(direction) ?: return@mapNotNull null
                // A DSO's bundled photo can be hundreds of pixels across, so its *center* leaving
                // the viewport doesn't mean the photo has -- widen the bounds check by the photo's
                // own real half-extent (again times sqrt(2) for worst-case rotation) rather than
                // dropping it the instant the center does. SkyMap's Canvas clips the true edge
                // (see VIEWPORT_BOUNDS_MARGIN's doc comment), so over-including here just means a
                // few extra draw calls clipped away, not visible overdraw.
                val photoHalfExtentPlaneUnits = (obj as? DeepSkyObject)
                    ?.let { objectImage(it.id) }
                    ?.let { Angle.ofDegrees(it.fovDegrees.toDouble() / 2.0).radians * SQRT_2 }
                    ?: 0.0
                val effectiveHalfWidth = halfWidthUnits + photoHalfExtentPlaneUnits
                val effectiveHalfHeight = halfHeightUnits + photoHalfExtentPlaneUnits
                if (point.x < -effectiveHalfWidth || point.x > effectiveHalfWidth ||
                    point.y < -effectiveHalfHeight || point.y > effectiveHalfHeight
                ) {
                    null
                } else {
                    val limit = limitFor(obj)
                    val alpha = ((limit + FADE_MAGNITUDE_RANGE - effectiveMagnitude(obj)) / FADE_MAGNITUDE_RANGE)
                        .coerceIn(0f, 1f)
                    ProjectedObject(obj, point, direction, alpha)
                }
            }
            .sortedBy { effectiveMagnitude(it.skyObject) }
            .take(maxObjects)
            .toList()
    }

    /** The best tap target for [tapPoint] among [scene], within [maxDistance] of some candidate's
     *  own center if nothing better applies (both in the same plane units as
     *  [ProjectedObject.point] -- the caller converts a raw pixel tap using
     *  [pixelsPerPlaneUnit]), or null if nothing qualifies.
     *
     *  A tap landing inside a candidate's own rendered disc (per [radiusPlaneUnits], in the same
     *  plane units) always beats one that doesn't, regardless of which center is numerically
     *  closer -- without this, a bright star's own large glow can visually cover a much fainter,
     *  tinier neighbor a fraction of a degree away (a real case: Markab/HIP 114031 in Pegasus,
     *  ~0.3 degrees apart), and a tap squarely on the bright star's disc would still resolve to
     *  the neighbor because its exact center happens to sit a few pixels closer to the tap than
     *  the bright star's own center does. Among several discs that all contain the tap, the
     *  smallest radius wins (the more specific target), matching how a small icon sitting on a
     *  large background shape stays individually tappable. Only once no candidate's disc contains
     *  the tap does this fall back to nearest-center-within-[maxDistance], as before. */
    fun nearest(
        scene: List<ProjectedObject>,
        tapPoint: PlanePoint,
        maxDistance: Double,
        radiusPlaneUnits: (ProjectedObject) -> Double = { 0.0 },
    ): ProjectedObject? {
        var best: ProjectedObject? = null
        var bestIsContained = false
        var bestRadius = Double.POSITIVE_INFINITY
        var bestDistance = Double.POSITIVE_INFINITY
        for (candidate in scene) {
            val dx = candidate.point.x - tapPoint.x
            val dy = candidate.point.y - tapPoint.y
            val distance = sqrt(dx * dx + dy * dy)
            val radius = radiusPlaneUnits(candidate)
            val isContained = distance <= radius
            val betterThanBest = when {
                isContained && !bestIsContained -> true
                isContained -> radius < bestRadius
                bestIsContained -> false
                else -> distance <= maxDistance && distance < bestDistance
            }
            if (betterThanBest) {
                best = candidate
                bestIsContained = isContained
                bestRadius = radius
                bestDistance = distance
            }
        }
        return best
    }

    /** Governs every [SkyObject] except [StarObject] (see [starMagnitudeLimitFor] for that curve) --
     *  in practice this means DSOs, since [SolarSystemObject] always passes via its sentinel. Log-
     *  linear in field of view, the same shape as [starMagnitudeLimitFor] and for the same reason:
     *  each tap of the zoom-in button is itself multiplicative ([MAP_ZOOM_STEP_FACTOR]), so "N taps
     *  in" is a roughly constant step in ln(fieldOfViewDegrees) regardless of the starting zoom,
     *  where a curve linear in the angle itself would reveal most of its range in the first couple
     *  of taps. Calibrated so a fully-zoomed-out view (180°) shows no DSOs at all -- [DSO_LOG_SLOPE]
     *  pushes the raw value at 180° far below [DSO_MAGNITUDE_FLOOR], which itself sits comfortably
     *  under the brightest real DSO (the Pleiades, magnitude ~1.6) once [FADE_MAGNITUDE_RANGE] is
     *  added back -- the first of those only starts fading in about 5 taps in (field of view ~59°),
     *  and the full catalog depth (dso.bin's median magnitude is ~14) isn't reached until
     *  [DSO_CEILING_FOV] at ~15 taps in (field of view ~6.3°) -- stretched out deliberately, so the
     *  deepest, densest part of the catalog only shows up once the user is genuinely zoomed in
     *  close, not partway through an ordinary browsing zoom. */
    private const val DSO_MAGNITUDE_FLOOR = -1f
    private const val DSO_MAGNITUDE_CEILING = 16f
    private const val DSO_CEILING_FOV = 6.33
    private const val DSO_LOG_SLOPE = 6.72f
    private fun magnitudeLimitFor(fieldOfViewDegrees: Double): Float {
        val raw = DSO_MAGNITUDE_CEILING - DSO_LOG_SLOPE * ln(fieldOfViewDegrees / DSO_CEILING_FOV).toFloat()
        return raw.coerceIn(DSO_MAGNITUDE_FLOOR, DSO_MAGNITUDE_CEILING)
    }

    /** [StarObject]s get their own reveal curve rather than sharing [magnitudeLimitFor]: stars.bin
     *  is pre-filtered to mag <= 8.5 at build time (`STAR_MAG_LIMIT` in tools/build-catalogs.mjs,
     *  chosen as roughly as deep as HYG's own completeness goes -- see that constant's comment) and,
     *  unlike [magnitudeLimitFor]'s DSOs, carries every such star sky-wide (not just named/Bayer/
     *  Flamsteed ones -- see that file), so a curve tuned for dso.bin's sparser distribution badly
     *  overshoots here: measured against the real bundled catalog, a curve linear in field of view
     *  put well over 4000 stars in a single default-zoom view (a real phone canvas's visible solid
     *  angle at a given [SkyMapViewport.fieldOfViewDegrees] is much larger than the FOV number alone
     *  suggests, given [VIEWPORT_BOUNDS_MARGIN] and a typical tall aspect ratio), heavy enough to
     *  threaten frame time and defeat the point of a reveal curve.
     *
     *  Star count in a fixed-shape viewport scales roughly with area (~[fieldOfViewDegrees]^2) times
     *  a magnitude-dependent density, so holding perceived density roughly constant across zoom
     *  needs the cutoff to grow with **ln**([fieldOfViewDegrees]), not the FOV itself. [STAR_LOG_SLOPE]
     *  and [STAR_CEILING_FOV] are fit against measurements of the real bundled catalog (checked
     *  across several sky directions, including the real galactic center, not just one) rather than
     *  picked by eye. [STAR_CEILING_FOV] deliberately trades some of that headroom for reaching
     *  fainter stars sooner (i.e. at a wider field of view, needing less zoom) -- the peak combined
     *  stars+DSOs count in view this produces (~4700 at field of view ~40 degrees) is what
     *  [DEFAULT_MAX_DRAWN_OBJECTS] is sized against. */
    private const val STAR_CATALOG_MAGNITUDE_CEILING = 8.5f
    private const val STAR_MAGNITUDE_FLOOR = 3.0f
    /** Field of view at/below which the curve saturates at [STAR_CATALOG_MAGNITUDE_CEILING] --
     *  raised from an earlier, stricter fit specifically so more of the catalog's depth shows up at
     *  a less-zoomed-in field of view, at the cost of a denser view along the way (see
     *  [DEFAULT_MAX_DRAWN_OBJECTS]'s doc comment for the resulting peak count). */
    private const val STAR_CEILING_FOV = 10.0
    private const val STAR_LOG_SLOPE = 1.7f

    /** Public so the Compose draw layer can compute each star's rendered size relative to the same
     *  current-zoom limiting magnitude that decides which stars [build] includes at all -- see
     *  [com.astrocompass.ui.components.SkyMap]'s `drawStar`. */
    fun starMagnitudeLimitFor(fieldOfViewDegrees: Double): Float {
        val raw = STAR_CATALOG_MAGNITUDE_CEILING - STAR_LOG_SLOPE * ln(fieldOfViewDegrees / STAR_CEILING_FOV).toFloat()
        return raw.coerceIn(STAR_MAGNITUDE_FLOOR, STAR_CATALOG_MAGNITUDE_CEILING)
    }

    private fun effectiveMagnitude(obj: SkyObject): Float = when {
        obj is SolarSystemObject -> SOLAR_SYSTEM_BODY_SENTINEL
        obj.magnitude.isNaN() -> NO_MAGNITUDE_SENTINEL
        else -> obj.magnitude
    }
}
