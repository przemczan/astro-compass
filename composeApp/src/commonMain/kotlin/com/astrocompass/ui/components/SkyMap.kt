package com.astrocompass.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.astrocompass.astro.Angle
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.coords.HorizontalCoordinates
import com.astrocompass.astro.projection.PlanePoint
import com.astrocompass.astro.projection.StereographicProjection
import com.astrocompass.catalog.DeepSkyObject
import com.astrocompass.catalog.SkyObject
import com.astrocompass.catalog.SkyObjectType
import com.astrocompass.catalog.SolarSystemObject
import com.astrocompass.catalog.StarObject
import com.astrocompass.catalog.objectImage
import com.astrocompass.ui.skymap.MilkyWayCellDirection
import com.astrocompass.ui.skymap.SkyMapDirectionCache
import com.astrocompass.ui.skymap.SkyMapScene
import com.astrocompass.ui.skymap.SkyMapViewport
import com.astrocompass.ui.theme.TelescopeBlue
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.imageResource

/** A direction worth marking on the map beyond the catalog itself -- a Guidance target, the
 *  telescope's current pointing, or an already-confirmed alignment sync point. */
data class SkyMapMarker(
    val direction: Vector3,
    val color: Color,
    val label: String? = null,
    /** True centers [label] above the marker instead of anchoring it to the right (see
     *  [drawObjectLabel]) -- for a marker naming *what it is* (Phone/Telescope) rather than a
     *  catalog object, which reads better sitting squarely over its own crosshair. */
    val labelAbove: Boolean = false,
) {
    companion object {
        /** The connected mount's own reported direction -- drawn exactly like any other marker, in
         *  [TelescopeBlue] so it reads as the telescope's position rather than the app's own
         *  pointing, on every map that shows one. */
        fun telescope(direction: Vector3): SkyMapMarker = SkyMapMarker(direction, TelescopeBlue, label = "Telescope", labelAbove = true)
    }
}

/** The straight (great-circle) path from [start] to [end], drawn as a trail of arrowheads that
 *  shrink and fade toward [end] -- see [drawGuidancePath]. Guidance-screen-only: nothing else
 *  passes this to [SkyMap]. */
data class SkyMapGuidancePath(
    val start: Vector3,
    val end: Vector3,
    val color: Color,
)

/** A resolved, ready-to-draw bundled photo for one object at the current zoom --
 *  [rotationDegrees] is the clockwise screen rotation that points the (assumed north-up) photo's
 *  own "up" at true equatorial north, see [SkyMap]'s `objectPhotos` for the derivation. */
private data class ObjectPhoto(
    val image: ImageBitmap,
    val targetLongestEdgePx: Float,
    val rotationDegrees: Float,
)

/** An object is labeled once it's this many magnitudes brighter than the current zoom's own
 *  reveal threshold ([SkyMapScene.starMagnitudeLimitFor]) -- a fixed per-object rule, deliberately
 *  *not* "the N brightest objects currently on screen". A rank-based top-N was tried first and
 *  had a real bug: which objects rank in the top N depends on the whole on-screen population, so
 *  a star sitting still in the middle of the screen could still gain or lose its label as
 *  unrelated stars entered or left the view elsewhere while panning. Gating on each object's own
 *  magnitude against the current zoom instead means a star's labeled state depends only on itself
 *  and the current field of view -- stable under panning, and naturally denser as you zoom in,
 *  same as the reveal curve it's offset from. */
private const val LABEL_MAGNITUDE_MARGIN = 3f
/** Safety cap on how many labels [SkyMap] draws in one frame -- [LABEL_MAGNITUDE_MARGIN] alone
 *  already keeps this low in ordinary star fields, so this only matters in pathologically dense
 *  ones (a bright open cluster zoomed in close). Sorted by magnitude first so, on the rare frame
 *  this actually binds, it's still the brightest objects that survive, not an arbitrary subset. */
private const val MAX_LABELS = 40
private val TOUCH_TARGET_RADIUS_DP = 22.dp

/** How far a label sits to the right of the dot it names. */
private const val LABEL_ANCHOR_GAP_PX = 8f

/** Everything below the horizon draws at this fraction of its normal opacity -- dimmed by 75%. It
 *  is behind the ground and can't be observed, but it still belongs on the chart: dimming rather
 *  than hiding it keeps a constellation half-risen readable as one shape, and shows at a glance how
 *  long a target still has to wait. */
private const val BELOW_HORIZON_ALPHA = 0.25f

/** Where a drawn label ended up, so a tap on the name selects its object -- a label is a far bigger
 *  and steadier target than the few pixels of the dot beside it.
 *
 *  Collected during the draw pass into a plain list rather than snapshot state: it is rewritten
 *  every frame and read only by the tap handler, so making it observable would feed each frame
 *  straight back into recomposition. Being drawn is also exactly the condition for being tappable,
 *  which is why this is built where the drawing happens instead of being derived separately. */
private class LabelHitBox(val skyObject: SkyObject, val bounds: Rect)
/** A star's rendered core radius grows linearly with how far its magnitude sits above the
 *  *current zoom's* limiting magnitude ([SkyMapScene.starMagnitudeLimitFor]) -- not with its
 *  absolute magnitude. This is deliberate, matching how Stellarium renders point sources: a star
 *  right at the current visibility threshold always draws at [MIN_STAR_RADIUS_PX] regardless of
 *  what that threshold happens to be, and grows the same way whether it gets zoomed in on (the
 *  threshold itself reaches fainter as field of view narrows, so a fixed star's distance above it
 *  grows) or is simply an intrinsically brighter star at the same zoom -- one linear formula
 *  handles both, so every star scales with zoom identically rather than each having its own curve. */
private const val MIN_STAR_RADIUS_PX = 0.6f
private const val MAX_STAR_RADIUS_PX = 20f
private const val STAR_RADIUS_PER_MAGNITUDE_PX = 2.0f
private const val HALO_RADIUS_MULTIPLIER = 5f
private const val MAX_STAR_HALO_RADIUS_PX = 80f
/** A star's halo only starts appearing once its own core radius (already zoom-scaled, see above)
 *  clears this many pixels -- an *apparent size* threshold, not a magnitude one, so it's the same
 *  rule at every zoom: a star that's still a bare point stays a bare point, and one that's grown
 *  enough to look like more than a point starts glowing, regardless of why it grew that large. */
private const val HALO_MIN_CORE_RADIUS_PX = 2f
/** The halo's own peak opacity ramps from 0 right at [HALO_MIN_CORE_RADIUS_PX] up to
 *  [HALO_PEAK_ALPHA] by [MAX_STAR_RADIUS_PX] -- a star that's just barely earned a halo gets a
 *  faint one, not the same intensity as Sirius, so halo strength (not just reach) tracks how far
 *  above the visibility threshold a star sits, same as its size does. */
private const val HALO_PEAK_ALPHA = 0.45f
/** Passed to [drawStar] for planets/Sun/Moon -- brighter than any real star magnitude, so they
 *  always draw at [MAX_STAR_RADIUS_PX]/[MAX_STAR_HALO_RADIUS_PX] regardless of the current zoom's
 *  limiting magnitude. */
private const val PLANET_DRAW_MAGNITUDE = -10f
/** Solar system bodies draw at half the size stars can reach -- see [drawStar]'s `sizeMultiplier`.
 *  Stars are unaffected; this only scales the always-maxed-out planet/Sun/Moon case. */
private const val SOLAR_SYSTEM_SIZE_MULTIPLIER = 0.5f
private val DSO_GLYPH_RADIUS_DP = 6.dp
/** Below this on-screen size an object's bundled photo would just be a smudge -- the dot
 *  glyph reads better and is cheaper to draw, so the photo only takes over once real apparent
 *  size at the current zoom earns it. First-pass tuning value, not derived from anything. */
private const val MIN_PHOTO_DISPLAY_PX = 40f
/** A schematic DSO glyph (the plain dot/oval/square/diamond fallback, not a bundled photo) fades
 *  in as the object's own real apparent size -- majorAxisArcmin converted to pixels at the current
 *  zoom, same conversion [SkyMap]'s objectPhotos map uses -- clears this many pixels, and is fully
 *  opaque by [MIN_DSO_APPARENT_RADIUS_PX] + [DSO_SIZE_FADE_RANGE_PX]. Most catalog DSOs are a small
 *  fraction of a screen pixel across at any zoom a wide field of view shows, so gating by real
 *  apparent size -- not just magnitude -- declutters a wide view down to the objects actually big
 *  enough to matter, opening up gradually as you zoom in, the same way [drawStar] fades a star in
 *  near its own magnitude threshold. Objects with no known size ([DeepSkyObject.majorAxisArcmin]
 *  is `NaN` for about 10% of dso.bin) skip this gate entirely and keep the old magnitude-only
 *  behavior -- there's no size data to gate on. Measured against the real bundled catalog: this
 *  cuts the DSO count roughly 60-70% at field of view 40-60 degrees (the worst-cluttered range)
 *  while barely touching field of view 10 degrees and narrower, where survivors are already big
 *  enough that most render above [MIN_DSO_APPARENT_RADIUS_PX] anyway. */
private const val MIN_DSO_APPARENT_RADIUS_PX = 0.4f
private const val DSO_SIZE_FADE_RANGE_PX = 0.4f
private val MARKER_RADIUS_DP = 20.dp
private val GUIDANCE_ARROW_LENGTH_DP = 42.dp
private val GUIDANCE_ARROW_WIDTH_DP = 21.dp
/** The on-screen gap after each arrowhead, as a multiple of *that arrow's own* length -- 0.5x
 *  means the gap is half as long as the arrow it follows. Walked in screen pixels rather than sky
 *  degrees (see [drawGuidancePath]) so the trail's on-screen scale stays constant as the user
 *  zooms; expressed relative to each arrow's own (already-shrunk) length rather than a single
 *  fixed pixel gap so the gaps taper down in step with the arrows as they shrink toward the target,
 *  instead of looking increasingly sparse relative to them. */
private const val GUIDANCE_PATH_GAP_TO_ARROW_LENGTH_RATIO = 0.5f
/** Safety cap on the arc-length walk in [drawGuidancePath] -- with no artificial minimum spacing,
 *  a pathological case (e.g. a huge separation at a very tight zoom) could otherwise walk many
 *  more steps than are useful to draw. */
private const val GUIDANCE_PATH_MAX_ARROWS = 40
/** How far ahead (in the same [0, 1] `t` used to walk the path) each arrowhead looks to find its
 *  own heading -- small enough to approximate the local tangent, not the path's overall direction. */
private const val GUIDANCE_PATH_TANGENT_STEP = 0.01
/** The smallest (closest-to-target) arrowhead is this fraction of the largest (closest-to-start) --
 *  i.e. the starting arrow is 3x the target-end arrow, per the guidance-path spec. */
private const val GUIDANCE_PATH_END_SIZE_FRACTION = 1f / 3f
/** The starting (closest-to-start) arrowhead's opacity, as a fraction of the path color's own full
 *  opacity -- fading further to [GUIDANCE_PATH_END_ALPHA_FRACTION] by the target. */
private const val GUIDANCE_PATH_START_ALPHA_FRACTION = 0.75f
/** The closest-to-target arrowhead fades to this fraction of full opacity. */
private const val GUIDANCE_PATH_END_ALPHA_FRACTION = 0.5f
/** The first arrowhead is drawn this many times wider than [GUIDANCE_ARROW_WIDTH_DP] (before the
 *  usual [GUIDANCE_PATH_END_SIZE_FRACTION] shrink is applied) -- a distinct visual cue that this is
 *  the *start* of the trail, separate from the shrink-toward-target sizing every arrow already
 *  gets. Tapers back down to the normal 1x width by [GUIDANCE_PATH_WIDTH_TAPER_T], not all the way
 *  to the target, so only the lead of the trail reads as flared. */
private const val GUIDANCE_PATH_START_WIDTH_MULTIPLIER = 3f
/** `t` (the same [0, 1] fraction along the path used everywhere else) at which the width flare
 *  from [GUIDANCE_PATH_START_WIDTH_MULTIPLIER] has fully tapered back to 1x -- the path's midpoint. */
private const val GUIDANCE_PATH_WIDTH_TAPER_T = 0.5f
private val HORIZON_SAMPLE_COUNT = 96
private const val HORIZON_STROKE_WIDTH = 4f
private const val GRATICULE_STROKE_WIDTH = 1.5f
private val GRATICULE_DASH_EFFECT = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
/** Altitude rings drawn at these steps -- 0° (the horizon) and 90° (the zenith, a single point)
 *  are excluded since [drawHorizon] already draws the former and the latter has no circle. */
private val GRATICULE_ALTITUDE_STEPS_DEGREES = listOf(30.0, 60.0)
private val GRATICULE_AZIMUTH_STEP_DEGREES = 30.0
private val GRATICULE_RADIAL_SAMPLE_COUNT = 18

/** Multiplies [SkyMapSnapshot.milkyWayGridStepDegrees] to get each Milky Way cell's soft-edged
 *  blob radius -- > 0.5 so a cell's blob overlaps its immediate neighbors (spaced exactly one grid
 *  step apart) rather than just touching them, and comfortably covers the larger (sqrt(2)x)
 *  diagonal-neighbor spacing too, so the whole grid reads as one continuous cloud with no visible
 *  seams between cells. Expressed as a multiple of the *angular* grid step, not a fixed pixel size,
 *  so this overlap ratio -- and therefore how smooth the cloud looks -- stays the same at every
 *  zoom level: a cell's screen radius and its screen distance to its neighbors both scale by the
 *  same pixelsPerUnit factor, so their ratio never changes. First-pass tuning value, not derived
 *  from anything. */
private const val MILKY_WAY_CELL_RADIUS_GRID_STEP_MULTIPLIER = 1.3f

/** Peak alpha (at each blob's own center, fading to 0 at its edge, same technique as [drawStar]'s
 *  halo) for a Milky Way cell, indexed by [MilkyWayCellDirection.level] - 1 (levels run 1..5, 1 =
 *  the widest/faintest contour, 5 = the smallest/brightest one around the galactic core).
 *  Individually very subtle -- a cloud silhouette, not a bold overlay -- since overlapping
 *  neighbor cells (see [MILKY_WAY_CELL_RADIUS_GRID_STEP_MULTIPLIER]) compound through ordinary
 *  alpha-over blending into a visibly denser cloud without any single blob standing out on its
 *  own. First-pass tuning values, not derived from anything. */
private val MILKY_WAY_LEVEL_ALPHA = floatArrayOf(0.055f, 0.08f, 0.11f, 0.16f, 0.24f)

/**
 * A pannable/zoomable alt-az sky chart: catalog objects as dots/glyphs, an optional set of
 * [markers] for directions that aren't catalog objects, and tap-to-select. Screen-up is always
 * the local zenith (see [SkyMapViewport]), so the horizon renders level.
 *
 * Gesture state ([viewport]) and the projected object list are recomputed from scratch on every
 * call -- cheap at this catalog size (a few hundred objects survive culling; see
 * [SkyMapScene.build]'s draw cap) -- so this composable holds no astronomy state of its own,
 * only the transient touch-tracking needed to keep gestures smooth.
 */
@Composable
fun SkyMap(
    directions: List<Pair<SkyObject, Vector3>>,
    viewport: SkyMapViewport,
    onViewportChange: (SkyMapViewport) -> Unit,
    modifier: Modifier = Modifier,
    onManualInteraction: () -> Unit = {},
    highlightedIds: Set<String> = emptySet(),
    markers: List<SkyMapMarker> = emptyList(),
    guidancePath: SkyMapGuidancePath? = null,
    constellationLines: List<List<Vector3>> = emptyList(),
    /** The Milky Way density grid, at the current sky rotation -- see [MilkyWayCellDirection] and
     *  [drawMilkyWay]. [milkyWayGridStepDegrees] is [com.astrocompass.catalog.MilkyWayCatalog]'s
     *  own fixed grid spacing, shared by every cell rather than carried per-cell. */
    milkyWayCells: List<MilkyWayCellDirection> = emptyList(),
    milkyWayGridStepDegrees: Float = 0f,
    /** Settings -> Appearance dial (0 = hidden, 1 = the map's own tuned brightness, 2 = double
     *  that) -- multiplies [MILKY_WAY_LEVEL_ALPHA] directly rather than gating a boolean, since
     *  "subtle cloud" is a matter of taste this app can't get right for everyone by itself. */
    milkyWayBrightness: Float = 1f,
    /** ENU direction of "true equatorial north" for each object that has a bundled photo -- see
     *  [SkyMapDirectionCache.northOffsetDirections]'s doc comment for why this needs observer
     *  location/time despite each object's own orientation being fixed. Objects missing from this
     *  map draw screen-upright rather than unrotated-wrong. */
    northOffsetDirections: Map<String, Vector3> = emptyMap(),
    /** Beta feature flag (Settings -> "Object images") -- false skips resolving/drawing photos
     *  entirely, falling back to the plain dot/glyph for every object. */
    showObjectPhotos: Boolean = true,
    /** Settings -> Appearance toggle -- false draws everything below the horizon at full opacity
     *  instead of [BELOW_HORIZON_ALPHA]. */
    dimBelowHorizon: Boolean = true,
    onSelect: ((SkyObject) -> Unit)? = null,
) {
    val belowHorizonAlpha = if (dimBelowHorizon) BELOW_HORIZON_ALPHA else 1f
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val scene = remember(directions, viewport, canvasSize) {
        SkyMapScene.build(directions, viewport, canvasSize.width.toFloat(), canvasSize.height.toFloat())
    }
    val pixelsPerUnit = remember(viewport, canvasSize) {
        SkyMapScene.pixelsPerPlaneUnit(viewport.fieldOfViewDegrees, min(canvasSize.width, canvasSize.height).toFloat())
    }
    val projection = remember(viewport) { SkyMapScene.projectionFor(viewport) }

    // Real photos take over from the dot glyph once the photo's own apparent size at the current
    // zoom clears MIN_PHOTO_DISPLAY_PX. Sized by the photo's own fovDegrees (the real angular field
    // it depicts), not the object's majorAxisArcmin -- a bundled photo includes real surrounding
    // starfield padded beyond the object's own size (see BundledObjectImage's doc comment), so
    // scaling it as if its content were exactly object-sized would compress that real starfield
    // to the wrong on-screen scale relative to the independently-projected catalog stars around
    // it. angularSize * pixelsPerUnit approximates on-screen size well for something this small
    // (the stereographic projection is ~scale-preserving over that small an angle) without needing
    // per-object trig beyond what's already computed above. showObjectPhotos short-circuits the
    // whole thing to an empty map rather than filtering the result, so a disabled toggle costs
    // nothing beyond the flag check itself.
    val objectPhotos: Map<String, ObjectPhoto> = if (!showObjectPhotos) emptyMap() else buildMap {
        for (projected in scene) {
            val obj = projected.skyObject as? DeepSkyObject ?: continue
            val bundledImage = objectImage(obj.id) ?: continue
            val fovRadians = Angle.ofDegrees(bundledImage.fovDegrees.toDouble()).radians
            val targetLongestEdgePx = (fovRadians * pixelsPerUnit).toFloat()
            if (targetLongestEdgePx < MIN_PHOTO_DISPLAY_PX) continue

            // Rotates the (published-north-up) photo so its own "up" points at true equatorial
            // north on *this* screen -- see SkyMapDirectionCache.northOffsetDirections' doc
            // comment for why that needs observer location/time despite the object's own
            // orientation being fixed. Plane-space deltas double as the screen-space bearing
            // (from up, clockwise) directly: toScreen's x/y scaling is uniform, so it doesn't
            // change the angle between two nearby points, only pixelsPerUnit does, and that
            // cancels out of atan2. Falls back to upright (0°) if north-offset data wasn't
            // supplied by the caller (SkyMap's default) or projects off the visible sky.
            // Confirmed on-device to rotate (not just draw upright); exact sign/direction against
            // an independent reference is still unverified.
            val rotationDegrees = northOffsetDirections[obj.id]
                ?.let(projection::project)
                ?.let { northPoint ->
                    val dx = northPoint.x - projected.point.x
                    val dy = northPoint.y - projected.point.y
                    Angle.ofRadians(atan2(dx, dy)).degrees.toFloat()
                } ?: 0f

            key(obj.id) {
                put(obj.id, ObjectPhoto(imageResource(bundledImage.drawable), targetLongestEdgePx, rotationDegrees))
            }
        }
    }

    val currentViewport = rememberUpdatedState(viewport)
    val currentOnViewportChange = rememberUpdatedState(onViewportChange)
    val currentOnManualInteraction = rememberUpdatedState(onManualInteraction)
    val currentScene = rememberUpdatedState(scene)
    val currentPixelsPerUnit = rememberUpdatedState(pixelsPerUnit)
    val currentOnSelect = rememberUpdatedState(onSelect)
    val labelHitBoxes = remember { mutableListOf<LabelHitBox>() }

    val backgroundColor = MaterialTheme.colorScheme.surface
    val constellationLineColor = MaterialTheme.colorScheme.outlineVariant
    val graticuleColor = MaterialTheme.colorScheme.outline
    val horizonColor = MaterialTheme.colorScheme.outline
    val cardinalColor = MaterialTheme.colorScheme.onSurfaceVariant
    val starColor = MaterialTheme.colorScheme.onSurface
    val dsoColor = MaterialTheme.colorScheme.tertiary
    val planetColor = MaterialTheme.colorScheme.secondary
    val highlightColor = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val cardinalStyle = MaterialTheme.typography.titleMedium.copy(color = cardinalColor, fontWeight = FontWeight.Bold)

    Canvas(
        modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (pan != Offset.Zero || zoom != 1f) currentOnManualInteraction.value()
                    val updated = currentViewport.value
                        .pannedBy(pan.x, pan.y, min(size.width, size.height).toFloat())
                        .zoomedBy(zoom)
                    currentOnViewportChange.value(updated)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val select = currentOnSelect.value ?: return@detectTapGestures
                    val ppu = currentPixelsPerUnit.value
                    if (ppu <= 0f) return@detectTapGestures
                    // A label is checked before its own dot: it is the larger target of the two, and
                    // tapping a name to pick what it names is what a reader expects.
                    labelHitBoxes.firstOrNull { it.bounds.contains(offset) }?.let {
                        select(it.skyObject)
                        return@detectTapGestures
                    }
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val tapPoint = PlanePoint(
                        x = ((offset.x - center.x) / ppu).toDouble(),
                        y = (-(offset.y - center.y) / ppu).toDouble(),
                    )
                    val touchRadiusUnits = TOUCH_TARGET_RADIUS_DP.toPx() / ppu
                    // Restricted to objects big enough to actually be seen at the current zoom (see
                    // isSelectable) -- otherwise a barely-visible faint star sitting near a genuinely
                    // prominent one could win "nearest" purely by geometric luck, making the prominent
                    // one hard to tap until you zoom in past the faint one's own reveal point anyway.
                    val starMagnitudeLimit = SkyMapScene.starMagnitudeLimitFor(currentViewport.value.fieldOfViewDegrees)
                    val selectableScene = currentScene.value.filter { isSelectable(it.skyObject, starMagnitudeLimit, ppu) }
                    SkyMapScene.nearest(
                        selectableScene,
                        tapPoint,
                        touchRadiusUnits.toDouble(),
                        radiusPlaneUnits = { renderedRadiusPx(it.skyObject, starMagnitudeLimit, ppu).toDouble() / ppu },
                    )?.let { select(it.skyObject) }
                }
            },
    ) {
        drawRect(backgroundColor)
        if (pixelsPerUnit <= 0f) return@Canvas

        val currentStarMagnitudeLimit = SkyMapScene.starMagnitudeLimitFor(viewport.fieldOfViewDegrees)
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        fun toScreen(point: PlanePoint): Offset = Offset(
            x = center.x + (point.x * pixelsPerUnit).toFloat(),
            y = center.y - (point.y * pixelsPerUnit).toFloat(),
        )

        // Backdrop strokes (constellation lines, the horizon) are drawn straight from ENU
        // directions, not through SkyMapScene.build's per-object culling -- without their own
        // bounds check, a segment connecting a point in view to one clear across the sky would
        // still get a finite stereographic projection and be drawn stretching across the canvas,
        // while properly-culled stars/DSOs stay confined to the actual field of view. Uses the
        // same margin SkyMapScene.build applies to objects (see VIEWPORT_BOUNDS_MARGIN's doc) so
        // neither system reaches further toward the edge than the other.
        val maxPlaneX = (this.size.width / 2.0 / pixelsPerUnit) * SkyMapScene.VIEWPORT_BOUNDS_MARGIN
        val maxPlaneY = (this.size.height / 2.0 / pixelsPerUnit) * SkyMapScene.VIEWPORT_BOUNDS_MARGIN

        // Unlike maxPlaneX/maxPlaneY above, NOT margined -- scene includes objects out to
        // VIEWPORT_BOUNDS_MARGIN beyond the canvas so dots don't pop in/out at the edge while
        // panning, but the label pass below must never label something that isn't actually drawn
        // on screen.
        val visibleHalfWidth = this.size.width / 2.0 / pixelsPerUnit
        val visibleHalfHeight = this.size.height / 2.0 / pixelsPerUnit
        fun isOnScreen(point: PlanePoint) =
            kotlin.math.abs(point.x) <= visibleHalfWidth && kotlin.math.abs(point.y) <= visibleHalfHeight

        drawMilkyWay(milkyWayCells, milkyWayGridStepDegrees, milkyWayBrightness, projection, ::toScreen, starColor, pixelsPerUnit, maxPlaneX, maxPlaneY, belowHorizonAlpha)
        drawGraticule(projection, ::toScreen, graticuleColor, maxPlaneX, maxPlaneY)
        drawConstellationLines(constellationLines, projection, ::toScreen, constellationLineColor, maxPlaneX, maxPlaneY, belowHorizonAlpha)
        drawHorizon(projection, ::toScreen, horizonColor, maxPlaneX, maxPlaneY)
        drawCardinalPoints(projection, ::toScreen, textMeasurer, cardinalStyle)

        // Tracks which DeepSkyObjects actually drew something (photo, or a schematic glyph that
        // cleared MIN_DSO_APPARENT_RADIUS_PX) -- the label pass below must agree, or a bright-but-
        // tiny DSO that the size gate hid would still get a name floating over empty sky.
        val visibleDsoIds = mutableSetOf<String>()
        for (projected in scene) {
            val screenPoint = toScreen(projected.point)
            val isHighlighted = highlightedIds.contains(projected.skyObject.id)
            val photo = objectPhotos[projected.skyObject.id]
            val alpha = projected.alpha * projected.direction.horizonAlpha(belowHorizonAlpha)
            when (val obj = projected.skyObject) {
                is StarObject -> drawStar(screenPoint, obj.magnitude, starColor, currentStarMagnitudeLimit, alpha, isHighlighted, highlightColor)
                is DeepSkyObject -> if (photo != null) {
                    drawObjectPhoto(screenPoint, photo.image, photo.targetLongestEdgePx, photo.rotationDegrees, alpha)
                    visibleDsoIds += obj.id
                } else {
                    val apparentRadiusPx = obj.apparentRadiusPx(pixelsPerUnit)
                    val drew = drawDeepSkyObject(screenPoint, obj.type, dsoColor, isHighlighted, highlightColor, alpha, apparentRadiusPx)
                    if (drew) visibleDsoIds += obj.id
                }
                is SolarSystemObject -> drawStar(
                    screenPoint, PLANET_DRAW_MAGNITUDE, planetColor, currentStarMagnitudeLimit, alpha,
                    isHighlighted, highlightColor, sizeMultiplier = SOLAR_SYSTEM_SIZE_MULTIPLIER,
                )
            }
        }

        // Planets/Sun/Moon are always labeled (there are only a handful, and unlike a star they
        // have no real magnitude to rank by -- see SkyMapScene's SOLAR_SYSTEM_BODY_SENTINEL).
        val labelMagnitudeLimit = currentStarMagnitudeLimit - LABEL_MAGNITUDE_MARGIN
        val labeled = scene
            .filter { !it.skyObject.magnitude.isNaN() }
            .filter { it.skyObject !is DeepSkyObject || it.skyObject.id in visibleDsoIds }
            .filter { isOnScreen(it.point) }
            .filter { it.skyObject.magnitude <= labelMagnitudeLimit }
            .sortedBy { it.skyObject.magnitude }
            .take(MAX_LABELS) +
            scene.filter { (highlightedIds.contains(it.skyObject.id) || it.skyObject is SolarSystemObject) && isOnScreen(it.point) }
        labelHitBoxes.clear()
        for (projected in labeled.distinct()) {
            val bounds = drawObjectLabel(
                toScreen(projected.point), projected.skyObject.displayName, textMeasurer, labelStyle,
                alpha = projected.direction.horizonAlpha(belowHorizonAlpha),
            )
            labelHitBoxes += LabelHitBox(projected.skyObject, bounds)
        }

        // Markers (and the guidance path between them) draw last, on top of every catalog object --
        // including a DeepSkyObject's bundled photo, which can be hundreds of pixels across and
        // would otherwise paint straight over a target/telescope/current-pointing marker drawn
        // earlier in the frame.
        guidancePath?.let { drawGuidancePath(it, projection, ::toScreen, pixelsPerUnit) }
        for (marker in markers) {
            val point = projection.project(marker.direction) ?: continue
            val screenPoint = toScreen(point)
            drawMarker(screenPoint, marker.color)
            // Unlike catalog objects, a marker's label isn't limited to the brightest few --
            // it's how a selected/target object stays identifiable even if it wouldn't
            // otherwise earn a label (a faint star, or any deep-sky object outside the
            // always-labeled solar system bodies).
            marker.label?.let { label ->
                if (marker.labelAbove) {
                    drawLabelCenteredAbove(screenPoint, label, textMeasurer, labelStyle, MARKER_RADIUS_DP.toPx() * 1.6f)
                } else {
                    drawObjectLabel(screenPoint, label, textMeasurer, labelStyle)
                }
            }
        }
    }
}

private fun DrawScope.drawHorizon(
    projection: StereographicProjection,
    toScreen: (PlanePoint) -> Offset,
    color: Color,
    maxPlaneX: Double,
    maxPlaneY: Double,
) {
    val directions = (0..HORIZON_SAMPLE_COUNT).map { i ->
        HorizontalCoordinates(Angle.ofDegrees(360.0 * i / HORIZON_SAMPLE_COUNT), Angle.ZERO).toEnu()
    }
    drawDirectionPolyline(directions, projection, toScreen, color, strokeWidth = HORIZON_STROKE_WIDTH, maxPlaneX, maxPlaneY)
}

/** Alt-az graticule: rings of constant altitude ([GRATICULE_ALTITUDE_STEPS_DEGREES], the "latitude"
 *  lines) plus radials of constant azimuth every [GRATICULE_AZIMUTH_STEP_DEGREES] from the horizon
 *  to the zenith (the "longitude" lines) -- drawn the same way as [drawHorizon] and
 *  [drawConstellationLines], straight from ENU directions rather than through [SkyMapScene]. */
private fun DrawScope.drawGraticule(
    projection: StereographicProjection,
    toScreen: (PlanePoint) -> Offset,
    color: Color,
    maxPlaneX: Double,
    maxPlaneY: Double,
) {
    for (altitudeDegrees in GRATICULE_ALTITUDE_STEPS_DEGREES) {
        val ring = (0..HORIZON_SAMPLE_COUNT).map { i ->
            HorizontalCoordinates(Angle.ofDegrees(360.0 * i / HORIZON_SAMPLE_COUNT), Angle.ofDegrees(altitudeDegrees)).toEnu()
        }
        drawDirectionPolyline(ring, projection, toScreen, color, strokeWidth = GRATICULE_STROKE_WIDTH, maxPlaneX, maxPlaneY, GRATICULE_DASH_EFFECT)
    }

    var azimuthDegrees = 0.0
    while (azimuthDegrees < 360.0) {
        val radial = (0..GRATICULE_RADIAL_SAMPLE_COUNT).map { i ->
            HorizontalCoordinates(Angle.ofDegrees(azimuthDegrees), Angle.ofDegrees(90.0 * i / GRATICULE_RADIAL_SAMPLE_COUNT)).toEnu()
        }
        drawDirectionPolyline(radial, projection, toScreen, color, strokeWidth = GRATICULE_STROKE_WIDTH, maxPlaneX, maxPlaneY, GRATICULE_DASH_EFFECT)
        azimuthDegrees += GRATICULE_AZIMUTH_STEP_DEGREES
    }
}

/** The Milky Way's diffuse band, as one soft-edged radial-gradient blob per surviving density-grid
 *  cell -- the same halo technique [drawStar] uses for a bright star's glow, here sized so
 *  overlapping neighbor cells (see [MILKY_WAY_CELL_RADIUS_GRID_STEP_MULTIPLIER]) blend into one
 *  continuous cloud instead of a field of visible dots. Drawn straight from ENU directions before
 *  any other backdrop layer, same as [drawHorizon]/[drawGraticule]/[drawConstellationLines] --
 *  everything drawn after it (the graticule, constellation lines, stars) paints over it, which is
 *  the point: it's meant to read as a faint tint behind the chart, not a layer on top of it. */
private fun DrawScope.drawMilkyWay(
    cells: List<MilkyWayCellDirection>,
    gridStepDegrees: Float,
    brightness: Float,
    projection: StereographicProjection,
    toScreen: (PlanePoint) -> Offset,
    color: Color,
    pixelsPerUnit: Float,
    maxPlaneX: Double,
    maxPlaneY: Double,
    belowHorizonAlpha: Float,
) {
    if (gridStepDegrees <= 0f || brightness <= 0f) return
    val radiusPlaneUnits = Angle.ofDegrees((gridStepDegrees * MILKY_WAY_CELL_RADIUS_GRID_STEP_MULTIPLIER).toDouble()).radians
    val radiusPx = (radiusPlaneUnits * pixelsPerUnit).toFloat()
    if (radiusPx <= 0f) return

    for (cell in cells) {
        val point = projection.project(cell.direction) ?: continue
        if (point.x < -maxPlaneX || point.x > maxPlaneX || point.y < -maxPlaneY || point.y > maxPlaneY) continue
        val peakAlpha = MILKY_WAY_LEVEL_ALPHA[(cell.level - 1).coerceIn(0, MILKY_WAY_LEVEL_ALPHA.lastIndex)]
        val alpha = (peakAlpha * brightness * cell.direction.horizonAlpha(belowHorizonAlpha)).coerceIn(0f, 1f)
        val screenPoint = toScreen(point)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = color.alpha * alpha), color.copy(alpha = 0f)),
                center = screenPoint,
                radius = radiusPx,
            ),
            radius = radiusPx,
            center = screenPoint,
        )
    }
}

private fun DrawScope.drawConstellationLines(
    polylines: List<List<Vector3>>,
    projection: StereographicProjection,
    toScreen: (PlanePoint) -> Offset,
    color: Color,
    maxPlaneX: Double,
    maxPlaneY: Double,
    belowHorizonAlpha: Float,
) {
    for (polyline in polylines) {
        drawDirectionPolyline(polyline, projection, toScreen, color, strokeWidth = 1f, maxPlaneX, maxPlaneY, belowHorizonAlpha = belowHorizonAlpha)
    }
}

/** A path through [directions], broken (not connected across) wherever [StereographicProjection]
 *  can't place a vertex, or the vertex projects outside [maxPlaneX]/[maxPlaneY] (see the call
 *  site's comment on why that second check matters) -- shared by [drawHorizon] and
 *  [drawConstellationLines], both of which are "backdrop" strokes drawn directly from ENU
 *  directions rather than from [SkyMapScene]'s per-object projected list. */
/** Split into two paths by [BELOW_HORIZON_ALPHA], per *segment* rather than per polyline, so a
 *  constellation half-risen dims exactly at the horizon instead of all at once. A segment takes the
 *  side its own midpoint falls on, which is why the sum of the two endpoints' up-components is what
 *  is tested. The horizon line itself sits at exactly zero and so draws undimmed. */
private fun DrawScope.drawDirectionPolyline(
    directions: List<Vector3>,
    projection: StereographicProjection,
    toScreen: (PlanePoint) -> Offset,
    color: Color,
    strokeWidth: Float,
    maxPlaneX: Double,
    maxPlaneY: Double,
    pathEffect: PathEffect? = null,
    belowHorizonAlpha: Float = BELOW_HORIZON_ALPHA,
) {
    val abovePath = Path()
    val belowPath = Path()
    var previous: Pair<Vector3, Offset>? = null
    for (direction in directions) {
        val point = projection.project(direction)
        val inBounds = point != null && kotlin.math.abs(point.x) <= maxPlaneX && kotlin.math.abs(point.y) <= maxPlaneY
        if (!inBounds) {
            previous = null
            continue
        }
        val screen = toScreen(point)
        previous?.let { (previousDirection, previousScreen) ->
            val path = if (previousDirection.z + direction.z < 0.0) belowPath else abovePath
            path.moveTo(previousScreen.x, previousScreen.y)
            path.lineTo(screen.x, screen.y)
        }
        previous = direction to screen
    }
    val stroke = Stroke(width = strokeWidth, pathEffect = pathEffect)
    drawPath(abovePath, color, style = stroke)
    drawPath(belowPath, color.copy(alpha = color.alpha * belowHorizonAlpha), style = stroke)
}

/** [belowHorizonAlpha] for a direction under the horizon, 1 above it. ENU's z *is* the
 *  up-component, so this is the altitude's sign without the trig to recover the angle. */
private fun Vector3.horizonAlpha(belowHorizonAlpha: Float): Float = if (z < 0.0) belowHorizonAlpha else 1f

private fun DrawScope.drawCardinalPoints(
    projection: StereographicProjection,
    toScreen: (PlanePoint) -> Offset,
    textMeasurer: TextMeasurer,
    style: TextStyle,
) {
    for ((label, azimuthDegrees) in listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)) {
        val direction = HorizontalCoordinates(Angle.ofDegrees(azimuthDegrees), Angle.ZERO).toEnu()
        val point = projection.project(direction) ?: continue
        val screen = toScreen(point)
        val measured = textMeasurer.measure(label, style)
        drawText(measured, topLeft = Offset(screen.x - measured.size.width / 2f, screen.y - measured.size.height / 2f))
    }
}

/** Draws a star (or, via [PLANET_DRAW_MAGNITUDE], a planet/Sun/Moon) as a small solid core plus,
 *  once it's grown large enough (see [HALO_MIN_CORE_RADIUS_PX]), a soft radial-gradient halo --
 *  like Stellarium's point-source rendering. [currentMagnitudeLimit] (see
 *  [SkyMapScene.starMagnitudeLimitFor]) is the current zoom's limiting magnitude, computed once per
 *  draw and shared by every star so all of them scale with zoom the same way -- see
 *  [MIN_STAR_RADIUS_PX]'s doc comment. [alpha] (see [ProjectedObject.alpha]) fades the whole star
 *  in smoothly as it crosses that limit rather than popping in solid. */
/** The same magnitude-above-limit-to-radius formula [drawStar] draws with -- shared with tap
 *  hit-testing (see [isSelectable]) so "how big does this star actually look" is computed exactly
 *  once, not re-derived a second time and risk drifting out of sync with what's on screen. */
private fun starCoreRadius(magnitude: Float, currentMagnitudeLimit: Float): Float {
    if (magnitude.isNaN()) return MAX_STAR_RADIUS_PX
    val magnitudeAboveLimit = (currentMagnitudeLimit - magnitude).coerceAtLeast(0f)
    return (MIN_STAR_RADIUS_PX + STAR_RADIUS_PER_MAGNITUDE_PX * magnitudeAboveLimit).coerceAtMost(MAX_STAR_RADIUS_PX)
}

private fun DrawScope.drawStar(
    center: Offset,
    magnitude: Float,
    color: Color,
    currentMagnitudeLimit: Float,
    alpha: Float,
    isHighlighted: Boolean,
    highlightColor: Color,
    sizeMultiplier: Float = 1f,
) {
    val baseCoreRadius = starCoreRadius(magnitude, currentMagnitudeLimit)
    // Applied after every other computation (including the MAX_STAR_RADIUS_PX clamp above), not
    // folded into the formula itself -- a planet/Sun/Moon always sits at that clamp regardless (see
    // PLANET_DRAW_MAGNITUDE), so scaling anything upstream of it would have no effect at all.
    val coreRadius = baseCoreRadius * sizeMultiplier
    val haloRadius = (baseCoreRadius * HALO_RADIUS_MULTIPLIER).coerceAtMost(MAX_STAR_HALO_RADIUS_PX) * sizeMultiplier
    val drawColor = color.copy(alpha = color.alpha * alpha)

    if (baseCoreRadius >= HALO_MIN_CORE_RADIUS_PX) {
        // Keyed off baseCoreRadius (true brightness), not the sizeMultiplier-scaled coreRadius --
        // a planet's halo should stay at full intensity even though its whole size is scaled down,
        // not read as dimmer just because it's smaller on screen.
        val haloAlphaT = ((baseCoreRadius - HALO_MIN_CORE_RADIUS_PX) / (MAX_STAR_RADIUS_PX - HALO_MIN_CORE_RADIUS_PX)).coerceIn(0f, 1f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(drawColor.copy(alpha = drawColor.alpha * HALO_PEAK_ALPHA * haloAlphaT), drawColor.copy(alpha = 0f)),
                center = center,
                radius = haloRadius,
            ),
            radius = haloRadius,
            center = center,
        )
    }
    drawCircle(drawColor, radius = coreRadius, center = center)
    if (isHighlighted) {
        // Drawn last, sized off whichever of the core/halo reaches further -- a halo painted after
        // the ring (the original draw order) would wash out a bright star's ring under its own glow.
        drawCircle(
            highlightColor.copy(alpha = highlightColor.alpha * alpha),
            radius = max(coreRadius, haloRadius) + 6f,
            center = center,
            style = Stroke(width = 2f),
        )
    }
}

/** [DeepSkyObject.majorAxisArcmin] converted to an on-screen radius at the current zoom -- same
 *  conversion [SkyMap]'s objectPhotos map uses -- or null if OpenNGC has no size measurement for
 *  this object. */
private fun DeepSkyObject.apparentRadiusPx(pixelsPerUnit: Float): Float? {
    if (majorAxisArcmin.isNaN()) return null
    return (Angle.ofDegrees(majorAxisArcmin / 60.0 / 2.0).radians * pixelsPerUnit).toFloat()
}

/** 1 (no gating) for an object with no known size; otherwise ramps 0 to 1 as [apparentRadiusPx]
 *  crosses [MIN_DSO_APPARENT_RADIUS_PX] over [DSO_SIZE_FADE_RANGE_PX] -- see that constant's doc
 *  comment. */
private fun dsoSizeAlpha(apparentRadiusPx: Float?): Float =
    if (apparentRadiusPx == null) {
        1f
    } else {
        ((apparentRadiusPx - MIN_DSO_APPARENT_RADIUS_PX) / DSO_SIZE_FADE_RANGE_PX).coerceIn(0f, 1f)
    }

/** Whether [obj] currently renders large enough to be a fair tap target -- reusing the exact size
 *  math [drawStar]/[dsoSizeAlpha] use to draw it, not a separate, looser touch radius. Without this,
 *  a barely-visible mag-8 star sitting near a genuinely prominent one competes equally for "nearest
 *  tap" purely by geometric luck, making the prominent object hard to select until the faint one
 *  grows past this same bar by zooming in -- which is backwards from what a tap should feel like.
 *  [HALO_MIN_CORE_RADIUS_PX] doubles as the bar here: it's already this codebase's answer to "how
 *  big before a star reads as more than a bare point," which is the same question a tap target
 *  needs answered. DSOs reuse [dsoSizeAlpha] directly -- an object [SkyMap] didn't actually draw
 *  (alpha 0) obviously shouldn't be tappable either. */
private fun isSelectable(obj: SkyObject, currentStarMagnitudeLimit: Float, pixelsPerUnit: Float): Boolean = when (obj) {
    is StarObject -> starCoreRadius(obj.magnitude, currentStarMagnitudeLimit) >= HALO_MIN_CORE_RADIUS_PX
    is SolarSystemObject -> starCoreRadius(PLANET_DRAW_MAGNITUDE, currentStarMagnitudeLimit) >= HALO_MIN_CORE_RADIUS_PX
    is DeepSkyObject -> dsoSizeAlpha(obj.apparentRadiusPx(pixelsPerUnit)) > 0f
}

/** [obj]'s on-screen radius exactly as [drawStar]/[drawDeepSkyObject] render it -- passed to
 *  [SkyMapScene.nearest] so a tap landing inside a bright object's own visible disc resolves to
 *  it even when a much fainter neighbor's exact center happens to sit a few pixels closer to the
 *  tap. Without this, a prominent star's own glow could visually cover a tiny, faint companion a
 *  fraction of a degree away, and a tap squarely on the prominent star would still resolve to the
 *  companion on raw center-to-center distance alone. */
private fun Density.renderedRadiusPx(obj: SkyObject, currentStarMagnitudeLimit: Float, pixelsPerUnit: Float): Float =
    when (obj) {
        is StarObject -> starCoreRadius(obj.magnitude, currentStarMagnitudeLimit)
        is SolarSystemObject -> starCoreRadius(PLANET_DRAW_MAGNITUDE, currentStarMagnitudeLimit) * SOLAR_SYSTEM_SIZE_MULTIPLIER
        is DeepSkyObject -> {
            val apparentRadiusPx = obj.apparentRadiusPx(pixelsPerUnit)
            if (apparentRadiusPx == null) DSO_GLYPH_RADIUS_DP.toPx() else max(DSO_GLYPH_RADIUS_DP.toPx(), apparentRadiusPx)
        }
    }

/** Draws a DSO's schematic dot/oval/square/diamond glyph, fading in by real apparent size (see
 *  [MIN_DSO_APPARENT_RADIUS_PX]) unless [isHighlighted] -- a selected/searched-for object stays
 *  visible regardless of size, the same way a [SkyMapMarker] is never limited by brightness either.
 *  Returns whether anything was actually drawn, so the caller can keep its label list in sync (see
 *  the `visibleDsoIds` comment at the call site). */
private fun DrawScope.drawDeepSkyObject(
    center: Offset,
    type: SkyObjectType,
    baseColor: Color,
    isHighlighted: Boolean,
    highlightColor: Color,
    alpha: Float,
    apparentRadiusPx: Float?,
): Boolean {
    val sizeAlpha = if (isHighlighted) 1f else dsoSizeAlpha(apparentRadiusPx)
    if (sizeAlpha <= 0f) return false

    val radius = if (apparentRadiusPx == null) DSO_GLYPH_RADIUS_DP.toPx() else max(DSO_GLYPH_RADIUS_DP.toPx(), apparentRadiusPx)
    val color = baseColor.copy(alpha = baseColor.alpha * alpha * sizeAlpha)
    val stroke = Stroke(width = 2f, pathEffect = dashEffectFor(type))
    if (isHighlighted) {
        drawCircle(highlightColor.copy(alpha = highlightColor.alpha * alpha), radius = radius + 6f, center = center, style = Stroke(width = 2f))
    }
    when (glyphFor(type)) {
        DsoGlyph.ELLIPSE -> drawOval(
            color = color,
            topLeft = Offset(center.x - radius, center.y - radius * 0.65f),
            size = Size(radius * 2, radius * 1.3f),
            style = stroke,
        )
        DsoGlyph.CIRCLE -> drawCircle(color, radius = radius, center = center, style = stroke)
        DsoGlyph.SQUARE -> drawRect(
            color = color,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = stroke,
        )
        DsoGlyph.DIAMOND -> {
            val path = Path().apply {
                moveTo(center.x, center.y - radius)
                lineTo(center.x + radius, center.y)
                lineTo(center.x, center.y + radius)
                lineTo(center.x - radius, center.y)
                close()
            }
            drawPath(path, color, style = stroke)
        }
    }
    return true
}

/** Fully opaque out to this fraction of each axis's own half-extent, then fades to transparent by
 *  [PHOTO_FADE_END_FRACTION] -- expressed per-axis (see [drawObjectPhoto]'s fade), not as a
 *  fraction of the frame's diagonal, so it lines up with the object's own edge consistently
 *  regardless of the frame's aspect ratio: fetch-object-images.mjs pads *each* axis by the same
 *  FOV_MARGIN_FACTOR (1.4x) independently, so the object's own edge always sits at very close to
 *  1/1.4 =~ 0.71 of the frame's half-extent along any given axis, elongated or not. Started just
 *  past that so the fade never eats into the object itself, only the padding/context starfield
 *  around it. */
private const val PHOTO_FADE_START_FRACTION = 0.85f

/** Fully transparent by this fraction of each axis's own half-extent -- short of 1f so there's a
 *  comfortable margin of guaranteed-transparent padding before the frame's actual edge, not just a
 *  single fully-transparent pixel row right at the boundary. */
private const val PHOTO_FADE_END_FRACTION = 0.95f

/** The symmetric opaque-middle/transparent-edges fade profile [drawObjectPhoto] applies along
 *  each axis independently, as fractions of the *full* [0, axis length] span (mirroring
 *  [PHOTO_FADE_START_FRACTION]/[PHOTO_FADE_END_FRACTION], which are expressed relative to the
 *  center, onto both ends of that span) -- shared by the horizontal and vertical mask so both
 *  read from the same profile rather than two hand-duplicated stop lists. */
private val PHOTO_FADE_AXIS_STOPS: Array<Pair<Float, Color>> = run {
    val innerStop = 0.5f * (1f - PHOTO_FADE_START_FRACTION)
    val outerStop = 0.5f * (1f - PHOTO_FADE_END_FRACTION)
    arrayOf(
        outerStop to Color.Transparent,
        innerStop to Color.White,
        (1f - innerStop) to Color.White,
        (1f - outerStop) to Color.Transparent,
    )
}

/** Draws a bundled object photo centered at [center], its longest edge scaled to
 *  [targetLongestEdgePx] -- the photo's own aspect ratio is kept as-is (not stretched to the
 *  catalog's major/minor axis ratio), since the photo's framing rarely matches that ratio exactly
 *  and stretching would visibly distort it -- then rotated clockwise by [rotationDegrees] about
 *  its own center to align with true north on screen.
 *
 *  Composited with [BlendMode.Screen] rather than plain alpha: every bundled photo is matted to
 *  solid black (see `tools/build-object-images.java`), and the map's own background isn't
 *  reliably black -- only [com.astrocompass.ui.theme.AppTheme.Night] is; Light/Dark come straight
 *  from Material's default color schemes -- so a plain-alpha draw would show as a stark black
 *  rectangle in both. Screen ignores near-black source pixels and only brightens toward the
 *  photo's actual stars/nebulosity, so the image blends into the map instead of sitting on top of
 *  it, regardless of theme.
 *
 *  A fade is masked on top (via [BlendMode.DstIn] onto its own offscreen layer, so it only affects
 *  this photo, not whatever's already drawn on the map underneath) rather than left as a hard
 *  rectangle -- the frame's own straight edges/corners are otherwise the most obvious "this is a
 *  pasted-on photo" tell, worse than the black background [BlendMode.Screen] already handles.
 *  Rectangular -- matching the frame's own shape, rather than an inscribed circle/ellipse that
 *  would cut into the frame's corners even where the underlying photo has real, undistorted sky
 *  data -- built from two independent [BlendMode.DstIn] passes, a horizontal fade then a vertical
 *  one; DstIn multiplies alpha, so stacking them fades each corner by *both* axes' falloff at
 *  once (correctly stronger there) while an edge midpoint only fades by the one axis it's actually
 *  close to. */
private fun DrawScope.drawObjectPhoto(center: Offset, image: ImageBitmap, targetLongestEdgePx: Float, rotationDegrees: Float, alpha: Float) {
    if (image.width <= 0 || image.height <= 0) return
    val pixelScale = targetLongestEdgePx / max(image.width, image.height).toFloat()
    val drawWidth = image.width * pixelScale
    val drawHeight = image.height * pixelScale
    rotate(rotationDegrees, pivot = center) {
        translate(left = center.x - drawWidth / 2f, top = center.y - drawHeight / 2f) {
            val bounds = Rect(Offset.Zero, Size(drawWidth, drawHeight))
            drawContext.canvas.saveLayer(bounds, Paint())
            drawImage(
                image = image,
                dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt()),
                alpha = alpha,
                blendMode = BlendMode.Screen,
            )
            drawRect(
                brush = Brush.horizontalGradient(*PHOTO_FADE_AXIS_STOPS, startX = 0f, endX = drawWidth),
                size = bounds.size,
                blendMode = BlendMode.DstIn,
            )
            drawRect(
                brush = Brush.verticalGradient(*PHOTO_FADE_AXIS_STOPS, startY = 0f, endY = drawHeight),
                size = bounds.size,
                blendMode = BlendMode.DstIn,
            )
            drawContext.canvas.restore()
        }
    }
}

private fun DrawScope.drawMarker(center: Offset, color: Color) {
    val radius = MARKER_RADIUS_DP.toPx()
    drawCircle(color, radius = radius, center = center, style = Stroke(width = 3f))
    drawLine(color, Offset(center.x - radius * 1.6f, center.y), Offset(center.x - radius * 0.6f, center.y), strokeWidth = 3f)
    drawLine(color, Offset(center.x + radius * 0.6f, center.y), Offset(center.x + radius * 1.6f, center.y), strokeWidth = 3f)
    drawLine(color, Offset(center.x, center.y - radius * 1.6f), Offset(center.x, center.y - radius * 0.6f), strokeWidth = 3f)
    drawLine(color, Offset(center.x, center.y + radius * 0.6f), Offset(center.x, center.y + radius * 1.6f), strokeWidth = 3f)
}

/** The straight line the trail actually follows: altitude and azimuth (shortest signed direction)
 *  interpolated independently and linearly -- the same alt-az decomposition
 *  [com.astrocompass.guiding.GuidanceCalculator] uses for the arrow and delta bars, not the
 *  greatcircle geodesic between the two directions. Guiding a phone/telescope means dialing in
 *  altitude and azimuth (or cross-track) separately, so a great-circle path -- the *shortest* path
 *  across the sky, but not one that corresponds to any single "move it this way" instruction --
 *  reads as pointing somewhere other than where the ALT/AZ delta bars say to go. This keeps the
 *  path consistent with the rest of the guidance UI instead of technically-shortest but misleading. */
private fun interpolateGuidancePath(start: Vector3, end: Vector3, t: Double): Vector3 {
    val from = HorizontalCoordinates.fromEnu(start)
    val to = HorizontalCoordinates.fromEnu(end)
    val altitude = from.altitude + (to.altitude - from.altitude) * t
    val azimuth = from.azimuth + (to.azimuth - from.azimuth).normalizedSigned() * t
    return HorizontalCoordinates(azimuth, altitude).toEnu()
}

/** How far along [interpolateGuidancePath] (from `0` at [start] toward `1` at [end]) the path
 *  stays on screen before crossing the canvas edge -- `1.0` if [end] itself is on screen. Assumes
 *  [start] is on screen and the path crosses the edge at most once between the two (true for the
 *  guidance trail in practice: the map follows the current-pointing marker, and the walk below
 *  only ever needs *a* reasonable cutoff, not an exact one); falls back to `1.0` -- i.e. no
 *  clipping -- if [start] itself isn't on screen, since there is then no known-on-screen point to
 *  search from. Used so a target that has panned off the visible map still shows the trail
 *  terminating right at the edge it would exit through, rather than skipping straight to a point
 *  that isn't actually drawn, or not drawing the trail at all. */
private fun DrawScope.onScreenPathFraction(
    start: Vector3,
    end: Vector3,
    projection: StereographicProjection,
    toScreen: (PlanePoint) -> Offset,
): Double {
    fun isOnScreen(t: Double): Boolean {
        val screen = projection.project(interpolateGuidancePath(start, end, t))?.let(toScreen) ?: return false
        return screen.x in 0f..size.width && screen.y in 0f..size.height
    }

    if (!isOnScreen(0.0) || isOnScreen(1.0)) return 1.0

    var onScreenT = 0.0
    var offScreenT = 1.0
    repeat(20) {
        val mid = (onScreenT + offScreenT) / 2.0
        if (isOnScreen(mid)) onScreenT = mid else offScreenT = mid
    }
    return onScreenT
}

/** Draws [path] as a trail of arrowheads walking [interpolateGuidancePath] from start to end --
 *  shrinking to [GUIDANCE_PATH_END_SIZE_FRACTION] of their starting size and fading from
 *  [GUIDANCE_PATH_START_ALPHA_FRACTION] to [GUIDANCE_PATH_END_ALPHA_FRACTION] opacity as they
 *  approach the target, so the trail reads as flowing toward it rather than a uniform dashed line.
 *  The lead arrow is also flared [GUIDANCE_PATH_START_WIDTH_MULTIPLIER] wide, tapering to normal
 *  width by the path's midpoint (see [guidanceArrowWidthMultiplier]), marking which end is "now"
 *  without relying on size/opacity alone. Each arrow's heading comes from the path's own local
 *  tangent ([GUIDANCE_PATH_TANGENT_STEP] further along `t`, projected the same as the arrow
 *  itself) rather than the straight line to the target, so it stays correct even though the alt-az
 *  path isn't a straight line under the stereographic projection either.
 *
 *  If the target is off screen ([onScreenPathFraction] < 1), the whole shrink/fade/width
 *  progression -- and the "1" end of `t` throughout this function -- refers to the edge of the
 *  screen, not the real target: the trail always reads as terminating exactly where it leaves the
 *  visible map, the same way it terminates at the target when the target is on screen, and picks
 *  back up following the real target the moment it pans back into view.
 *
 *  Arrows are placed by walking a fixed *pixel* arc length (converted from `t` via [pixelsPerUnit]
 *  and the small-angle approximation `radians ~= pixels / pixelsPerUnit`, same as used elsewhere
 *  for angular sizing, e.g. [SkyMap]'s photo scaling) -- never a fixed angular spacing -- so both
 *  the arrows' base size and the gap between them stay constant on screen regardless of the map's
 *  zoom or how far away the target is; only the number that fit changes (see
 *  [GUIDANCE_PATH_GAP_TO_ARROW_LENGTH_RATIO]'s doc for why the gap itself still shrinks per-arrow).
 *  The pixel budget itself is still the great-circle separation ([Vector3.angleTo]) -- the same
 *  angle the "N° away" readout shows -- since the alt-az path is only ever slightly longer than
 *  that for the separations guiding actually deals with, and this keeps the arrow count reading
 *  in step with that on-screen number (when the target itself is on screen; the off-screen case
 *  above scales this down to the visible portion instead). */
private fun DrawScope.drawGuidancePath(
    path: SkyMapGuidancePath,
    projection: StereographicProjection,
    toScreen: (PlanePoint) -> Offset,
    pixelsPerUnit: Float,
) {
    val start = path.start.normalized()
    val end = path.end.normalized()
    val onScreenFraction = onScreenPathFraction(start, end, projection, toScreen)
    val totalPx = start.angleTo(end).radians * onScreenFraction * pixelsPerUnit
    val fullArrowLengthPx = GUIDANCE_ARROW_LENGTH_DP.toPx()
    if (totalPx < fullArrowLengthPx) return // too short for even one arrow to fit cleanly

    var offsetPx = fullArrowLengthPx * (1f + GUIDANCE_PATH_GAP_TO_ARROW_LENGTH_RATIO)
    var arrowsDrawn = 0
    while (offsetPx < totalPx && arrowsDrawn < GUIDANCE_PATH_MAX_ARROWS) {
        val t = (offsetPx / totalPx).toDouble()
        val sizeFraction = 1f - (1f - GUIDANCE_PATH_END_SIZE_FRACTION) * t.toFloat()
        val lengthPx = fullArrowLengthPx * sizeFraction

        val aheadT = (t + GUIDANCE_PATH_TANGENT_STEP).coerceAtMost(1.0)
        val screenPoint = projection.project(interpolateGuidancePath(start, end, onScreenFraction * t))?.let(toScreen)
        val screenAhead = projection.project(interpolateGuidancePath(start, end, onScreenFraction * aheadT))?.let(toScreen)
        if (screenPoint != null && screenAhead != null) {
            val forwardLength = hypot(screenAhead.x - screenPoint.x, screenAhead.y - screenPoint.y)
            if (forwardLength >= 1e-3f) {
                val forward = Offset((screenAhead.x - screenPoint.x) / forwardLength, (screenAhead.y - screenPoint.y) / forwardLength)
                val perpendicular = Offset(-forward.y, forward.x)
                val alphaFraction = GUIDANCE_PATH_START_ALPHA_FRACTION -
                    (GUIDANCE_PATH_START_ALPHA_FRACTION - GUIDANCE_PATH_END_ALPHA_FRACTION) * t.toFloat()
                val widthMultiplier = guidanceArrowWidthMultiplier(t.toFloat())
                drawGuidanceArrow(screenPoint, forward, perpendicular, sizeFraction, widthMultiplier, alphaFraction, path.color)
            }
        }

        offsetPx += lengthPx * (1f + GUIDANCE_PATH_GAP_TO_ARROW_LENGTH_RATIO)
        arrowsDrawn++
    }
}

/** Linearly tapers [GUIDANCE_PATH_START_WIDTH_MULTIPLIER] down to 1x between `t = 0` and
 *  [GUIDANCE_PATH_WIDTH_TAPER_T], then holds at 1x for the rest of the path. */
private fun guidanceArrowWidthMultiplier(t: Float): Float {
    if (t >= GUIDANCE_PATH_WIDTH_TAPER_T) return 1f
    val progress = t / GUIDANCE_PATH_WIDTH_TAPER_T
    return GUIDANCE_PATH_START_WIDTH_MULTIPLIER - (GUIDANCE_PATH_START_WIDTH_MULTIPLIER - 1f) * progress
}

private fun DrawScope.drawGuidanceArrow(
    center: Offset,
    forward: Offset,
    perpendicular: Offset,
    sizeFraction: Float,
    widthMultiplier: Float,
    alphaFraction: Float,
    color: Color,
) {
    val length = GUIDANCE_ARROW_LENGTH_DP.toPx() * sizeFraction
    val width = GUIDANCE_ARROW_WIDTH_DP.toPx() * sizeFraction * widthMultiplier
    val tip = Offset(center.x + forward.x * length * 0.5f, center.y + forward.y * length * 0.5f)
    val back = Offset(center.x - forward.x * length * 0.5f, center.y - forward.y * length * 0.5f)
    val backLeft = Offset(back.x + perpendicular.x * width * 0.5f, back.y + perpendicular.y * width * 0.5f)
    val backRight = Offset(back.x - perpendicular.x * width * 0.5f, back.y - perpendicular.y * width * 0.5f)
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(backLeft.x, backLeft.y)
        lineTo(backRight.x, backRight.y)
        close()
    }
    drawPath(path, color.copy(alpha = color.alpha * alphaFraction))
}

private fun DrawScope.drawObjectLabel(
    anchor: Offset,
    text: String,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    alpha: Float = 1f,
): Rect {
    val measured = textMeasurer.measure(text, style)
    val topLeft = Offset(anchor.x + LABEL_ANCHOR_GAP_PX, anchor.y - measured.size.height / 2f)
    drawText(measured, topLeft = topLeft, alpha = alpha)
    return Rect(topLeft, Size(measured.size.width.toFloat(), measured.size.height.toFloat()))
}

/** Like [drawObjectLabel], but horizontally centered above [anchor] rather than anchored to its
 *  right -- see [SkyMapMarker.labelAbove]. [gapAbovePx] clears the marker's own drawn extent (its
 *  crosshair ticks, not just its ring) so the label never overlaps it. */
private fun DrawScope.drawLabelCenteredAbove(
    anchor: Offset,
    text: String,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    gapAbovePx: Float,
): Rect {
    val measured = textMeasurer.measure(text, style)
    val topLeft = Offset(anchor.x - measured.size.width / 2f, anchor.y - gapAbovePx - measured.size.height)
    drawText(measured, topLeft = topLeft)
    return Rect(topLeft, Size(measured.size.width.toFloat(), measured.size.height.toFloat()))
}

private enum class DsoGlyph { ELLIPSE, CIRCLE, SQUARE, DIAMOND }

private fun glyphFor(type: SkyObjectType): DsoGlyph = when (type) {
    SkyObjectType.GALAXY, SkyObjectType.GALAXY_PAIR, SkyObjectType.GALAXY_TRIPLET, SkyObjectType.GALAXY_GROUP ->
        DsoGlyph.ELLIPSE
    SkyObjectType.OPEN_CLUSTER, SkyObjectType.GLOBULAR_CLUSTER, SkyObjectType.ASSOCIATION, SkyObjectType.CLUSTER_AND_NEBULA ->
        DsoGlyph.CIRCLE
    SkyObjectType.PLANETARY_NEBULA, SkyObjectType.HII_REGION, SkyObjectType.DARK_NEBULA,
    SkyObjectType.EMISSION_NEBULA, SkyObjectType.NEBULA, SkyObjectType.REFLECTION_NEBULA, SkyObjectType.SUPERNOVA_REMNANT ->
        DsoGlyph.SQUARE
    SkyObjectType.STAR, SkyObjectType.DOUBLE_STAR, SkyObjectType.NOVA, SkyObjectType.OTHER -> DsoGlyph.DIAMOND
}

private fun dashEffectFor(type: SkyObjectType): PathEffect? = when (type) {
    SkyObjectType.DARK_NEBULA, SkyObjectType.NEBULA, SkyObjectType.EMISSION_NEBULA,
    SkyObjectType.REFLECTION_NEBULA, SkyObjectType.HII_REGION, SkyObjectType.SUPERNOVA_REMNANT ->
        PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
    else -> null
}
