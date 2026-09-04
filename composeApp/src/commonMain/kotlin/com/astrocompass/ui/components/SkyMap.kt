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
) {
    companion object {
        /** The connected mount's own reported direction -- drawn exactly like any other marker, in
         *  [TelescopeBlue] so it reads as the telescope's position rather than the app's own
         *  pointing, on every map that shows one. */
        fun telescope(direction: Vector3): SkyMapMarker = SkyMapMarker(direction, TelescopeBlue)
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
/** The closest-to-target arrowhead fades to this fraction of full opacity. */
private const val GUIDANCE_PATH_END_ALPHA_FRACTION = 0.5f
private val HORIZON_SAMPLE_COUNT = 96
private const val HORIZON_STROKE_WIDTH = 4f
private const val GRATICULE_STROKE_WIDTH = 1.5f
private val GRATICULE_DASH_EFFECT = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
/** Altitude rings drawn at these steps -- 0° (the horizon) and 90° (the zenith, a single point)
 *  are excluded since [drawHorizon] already draws the former and the latter has no circle. */
private val GRATICULE_ALTITUDE_STEPS_DEGREES = listOf(30.0, 60.0)
private val GRATICULE_AZIMUTH_STEP_DEGREES = 30.0
private val GRATICULE_RADIAL_SAMPLE_COUNT = 18

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
    /** ENU direction of "true equatorial north" for each object that has a bundled photo -- see
     *  [SkyMapDirectionCache.northOffsetDirections]'s doc comment for why this needs observer
     *  location/time despite each object's own orientation being fixed. Objects missing from this
     *  map draw screen-upright rather than unrotated-wrong. */
    northOffsetDirections: Map<String, Vector3> = emptyMap(),
    /** Beta feature flag (Settings -> "Object images") -- false skips resolving/drawing photos
     *  entirely, falling back to the plain dot/glyph for every object. */
    showObjectPhotos: Boolean = true,
    onSelect: ((SkyObject) -> Unit)? = null,
) {
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

        drawGraticule(projection, ::toScreen, graticuleColor, maxPlaneX, maxPlaneY)
        drawConstellationLines(constellationLines, projection, ::toScreen, constellationLineColor, maxPlaneX, maxPlaneY)
        drawHorizon(projection, ::toScreen, horizonColor, maxPlaneX, maxPlaneY)
        drawCardinalPoints(projection, ::toScreen, textMeasurer, cardinalStyle)

        for (marker in markers) {
            val point = projection.project(marker.direction) ?: continue
            val screenPoint = toScreen(point)
            drawMarker(screenPoint, marker.color)
            // Unlike catalog objects, a marker's label isn't limited to the brightest few --
            // it's how a selected/target object stays identifiable even if it wouldn't
            // otherwise earn a label (a faint star, or any deep-sky object outside the
            // always-labeled solar system bodies).
            marker.label?.let { label -> drawObjectLabel(screenPoint, label, textMeasurer, labelStyle) }
        }

        guidancePath?.let { drawGuidancePath(it, projection, ::toScreen, pixelsPerUnit) }

        // Tracks which DeepSkyObjects actually drew something (photo, or a schematic glyph that
        // cleared MIN_DSO_APPARENT_RADIUS_PX) -- the label pass below must agree, or a bright-but-
        // tiny DSO that the size gate hid would still get a name floating over empty sky.
        val visibleDsoIds = mutableSetOf<String>()
        for (projected in scene) {
            val screenPoint = toScreen(projected.point)
            val isHighlighted = highlightedIds.contains(projected.skyObject.id)
            val photo = objectPhotos[projected.skyObject.id]
            when (val obj = projected.skyObject) {
                is StarObject -> drawStar(screenPoint, obj.magnitude, starColor, currentStarMagnitudeLimit, projected.alpha, isHighlighted, highlightColor)
                is DeepSkyObject -> if (photo != null) {
                    drawObjectPhoto(screenPoint, photo.image, photo.targetLongestEdgePx, photo.rotationDegrees, projected.alpha)
                    visibleDsoIds += obj.id
                } else {
                    val apparentRadiusPx = obj.apparentRadiusPx(pixelsPerUnit)
                    val drew = drawDeepSkyObject(screenPoint, obj.type, dsoColor, isHighlighted, highlightColor, projected.alpha, apparentRadiusPx)
                    if (drew) visibleDsoIds += obj.id
                }
                is SolarSystemObject -> drawStar(
                    screenPoint, PLANET_DRAW_MAGNITUDE, planetColor, currentStarMagnitudeLimit, projected.alpha,
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
        for (projected in labeled.distinct()) {
            drawObjectLabel(toScreen(projected.point), projected.skyObject.displayName, textMeasurer, labelStyle)
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

private fun DrawScope.drawConstellationLines(
    polylines: List<List<Vector3>>,
    projection: StereographicProjection,
    toScreen: (PlanePoint) -> Offset,
    color: Color,
    maxPlaneX: Double,
    maxPlaneY: Double,
) {
    for (polyline in polylines) {
        drawDirectionPolyline(polyline, projection, toScreen, color, strokeWidth = 1f, maxPlaneX, maxPlaneY)
    }
}

/** A path through [directions], broken (not connected across) wherever [StereographicProjection]
 *  can't place a vertex, or the vertex projects outside [maxPlaneX]/[maxPlaneY] (see the call
 *  site's comment on why that second check matters) -- shared by [drawHorizon] and
 *  [drawConstellationLines], both of which are "backdrop" strokes drawn directly from ENU
 *  directions rather than from [SkyMapScene]'s per-object projected list. */
private fun DrawScope.drawDirectionPolyline(
    directions: List<Vector3>,
    projection: StereographicProjection,
    toScreen: (PlanePoint) -> Offset,
    color: Color,
    strokeWidth: Float,
    maxPlaneX: Double,
    maxPlaneY: Double,
    pathEffect: PathEffect? = null,
) {
    val path = Path()
    var started = false
    for (direction in directions) {
        val point = projection.project(direction)
        val inBounds = point != null && kotlin.math.abs(point.x) <= maxPlaneX && kotlin.math.abs(point.y) <= maxPlaneY
        if (!inBounds) {
            started = false
            continue
        }
        val screen = toScreen(point)
        if (!started) {
            path.moveTo(screen.x, screen.y)
            started = true
        } else {
            path.lineTo(screen.x, screen.y)
        }
    }
    drawPath(path, color, style = Stroke(width = strokeWidth, pathEffect = pathEffect))
}

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

/** Draws [path] as a trail of arrowheads walking its great circle from start to end -- shrinking
 *  to [GUIDANCE_PATH_END_SIZE_FRACTION] of their starting size and fading to
 *  [GUIDANCE_PATH_END_ALPHA_FRACTION] opacity as they approach the target, so the trail reads as
 *  flowing toward it rather than a uniform dashed line. Each arrow's heading comes from the
 *  path's own local tangent ([GUIDANCE_PATH_TANGENT_STEP] further along `t`, projected the same
 *  as the arrow itself) rather than the straight line to the target, so it stays correct even
 *  though the great circle isn't a straight line under the stereographic projection.
 *
 *  Arrows are placed by walking a fixed *pixel* arc length (converted from `t` via [pixelsPerUnit]
 *  and the small-angle approximation `radians ~= pixels / pixelsPerUnit`, same as used elsewhere
 *  for angular sizing, e.g. [SkyMap]'s photo scaling) -- never a fixed angular spacing -- so both
 *  the arrows' base size and the gap between them stay constant on screen regardless of the map's
 *  zoom or how far away the target is; only the number that fit changes (see
 *  [GUIDANCE_PATH_GAP_TO_ARROW_LENGTH_RATIO]'s doc for why the gap itself still shrinks per-arrow). */
private fun DrawScope.drawGuidancePath(
    path: SkyMapGuidancePath,
    projection: StereographicProjection,
    toScreen: (PlanePoint) -> Offset,
    pixelsPerUnit: Float,
) {
    val start = path.start.normalized()
    val end = path.end.normalized()
    val totalPx = start.angleTo(end).radians * pixelsPerUnit
    val fullArrowLengthPx = GUIDANCE_ARROW_LENGTH_DP.toPx()
    if (totalPx < fullArrowLengthPx) return // too short for even one arrow to fit cleanly

    var offsetPx = fullArrowLengthPx * (1f + GUIDANCE_PATH_GAP_TO_ARROW_LENGTH_RATIO)
    var arrowsDrawn = 0
    while (offsetPx < totalPx && arrowsDrawn < GUIDANCE_PATH_MAX_ARROWS) {
        val t = (offsetPx / totalPx).toDouble()
        val sizeFraction = 1f - (1f - GUIDANCE_PATH_END_SIZE_FRACTION) * t.toFloat()
        val lengthPx = fullArrowLengthPx * sizeFraction

        val aheadT = (t + GUIDANCE_PATH_TANGENT_STEP).coerceAtMost(1.0)
        val screenPoint = projection.project(start.slerp(end, t))?.let(toScreen)
        val screenAhead = projection.project(start.slerp(end, aheadT))?.let(toScreen)
        if (screenPoint != null && screenAhead != null) {
            val forwardLength = hypot(screenAhead.x - screenPoint.x, screenAhead.y - screenPoint.y)
            if (forwardLength >= 1e-3f) {
                val forward = Offset((screenAhead.x - screenPoint.x) / forwardLength, (screenAhead.y - screenPoint.y) / forwardLength)
                val perpendicular = Offset(-forward.y, forward.x)
                val alphaFraction = 1f - (1f - GUIDANCE_PATH_END_ALPHA_FRACTION) * t.toFloat()
                drawGuidanceArrow(screenPoint, forward, perpendicular, sizeFraction, alphaFraction, path.color)
            }
        }

        offsetPx += lengthPx * (1f + GUIDANCE_PATH_GAP_TO_ARROW_LENGTH_RATIO)
        arrowsDrawn++
    }
}

private fun DrawScope.drawGuidanceArrow(
    center: Offset,
    forward: Offset,
    perpendicular: Offset,
    sizeFraction: Float,
    alphaFraction: Float,
    color: Color,
) {
    val length = GUIDANCE_ARROW_LENGTH_DP.toPx() * sizeFraction
    val width = GUIDANCE_ARROW_WIDTH_DP.toPx() * sizeFraction
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
) {
    val measured = textMeasurer.measure(text, style)
    drawText(measured, topLeft = Offset(anchor.x + 8f, anchor.y - measured.size.height / 2f))
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
