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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
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
import com.astrocompass.catalog.messierImageDrawable
import com.astrocompass.ui.skymap.SkyMapDirectionCache
import com.astrocompass.ui.skymap.SkyMapScene
import com.astrocompass.ui.skymap.SkyMapViewport
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import org.jetbrains.compose.resources.painterResource

/** A direction worth marking on the map beyond the catalog itself -- a Guidance target, the
 *  telescope's current pointing, or an already-confirmed alignment sync point. */
data class SkyMapMarker(
    val direction: Vector3,
    val color: Color,
    val label: String? = null,
)

/** A resolved, ready-to-draw bundled photo for one Messier object at the current zoom --
 *  [rotationDegrees] is the clockwise screen rotation that points the (assumed north-up) photo's
 *  own "up" at true equatorial north, see [SkyMap]'s `objectPhotos` for the derivation. */
private data class ObjectPhoto(
    val painter: Painter,
    val targetLongestEdgePx: Float,
    val rotationDegrees: Float,
)

private val STAR_LABEL_COUNT = 12
private val TOUCH_TARGET_RADIUS_DP = 22.dp
private val MIN_STAR_RADIUS_PX = 1.5f
private val MAX_STAR_RADIUS_PX = 7f
/** Passed to [drawStar] for planets/Sun/Moon -- brighter than any real star magnitude, so they
 *  always draw at [MAX_STAR_RADIUS_PX] regardless of the (nonexistent) real magnitude clamp. */
private const val PLANET_DRAW_MAGNITUDE = -10f
private val DSO_GLYPH_RADIUS_DP = 6.dp
/** Below this on-screen size a Messier object's bundled photo would just be a smudge -- the dot
 *  glyph reads better and is cheaper to draw, so the photo only takes over once real apparent
 *  size at the current zoom earns it. First-pass tuning value, not derived from anything. */
private const val MIN_PHOTO_DISPLAY_PX = 40f
private val MARKER_RADIUS_DP = 10.dp
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
    constellationLines: List<List<Vector3>> = emptyList(),
    /** ENU direction of "true equatorial north" for each Messier object with a known position
     *  angle -- see [SkyMapDirectionCache.northOffsetDirections]'s doc comment for why this needs
     *  observer location/time despite each object's own orientation being fixed. Objects missing
     *  from this map (no position angle on file) draw screen-upright rather than unrotated-wrong. */
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

    // Real photos take over from the dot glyph once an object's actual apparent size at the
    // current zoom clears MIN_PHOTO_DISPLAY_PX. angularSize * pixelsPerUnit approximates on-screen
    // size well for something object-sized (the stereographic projection is ~scale-preserving over
    // that small an angle) without needing per-object trig beyond what's already computed above.
    // showObjectPhotos short-circuits the whole thing to an empty map rather than filtering the
    // result, so a disabled toggle costs nothing beyond the flag check itself.
    val objectPhotos: Map<String, ObjectPhoto> = if (!showObjectPhotos) emptyMap() else buildMap {
        for (projected in scene) {
            val obj = projected.skyObject as? DeepSkyObject ?: continue
            if (obj.messier <= 0 || obj.majorAxisArcmin.isNaN()) continue
            val majorAxisRadians = Angle.ofDegrees(obj.majorAxisArcmin / 60.0).radians
            val targetLongestEdgePx = (majorAxisRadians * pixelsPerUnit).toFloat()
            if (targetLongestEdgePx < MIN_PHOTO_DISPLAY_PX) continue
            val drawable = messierImageDrawable(obj.messier) ?: continue

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
                put(obj.id, ObjectPhoto(painterResource(drawable), targetLongestEdgePx, rotationDegrees))
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
                    SkyMapScene.nearest(currentScene.value, tapPoint, touchRadiusUnits.toDouble())
                        ?.let { select(it.skyObject) }
                }
            },
    ) {
        drawRect(backgroundColor)
        if (pixelsPerUnit <= 0f) return@Canvas

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

        for (projected in scene) {
            val screenPoint = toScreen(projected.point)
            val isHighlighted = highlightedIds.contains(projected.skyObject.id)
            val photo = objectPhotos[projected.skyObject.id]
            when (val obj = projected.skyObject) {
                is StarObject -> drawStar(screenPoint, obj.magnitude, starColor, isHighlighted, highlightColor)
                is DeepSkyObject -> if (photo != null) {
                    drawObjectPhoto(screenPoint, photo.painter, photo.targetLongestEdgePx, photo.rotationDegrees)
                } else {
                    drawDeepSkyObject(screenPoint, obj.type, dsoColor, isHighlighted, highlightColor)
                }
                is SolarSystemObject -> drawStar(screenPoint, PLANET_DRAW_MAGNITUDE, planetColor, isHighlighted, highlightColor)
            }
        }

        // Planets/Sun/Moon are always labeled (there are only a handful, and unlike a star they
        // have no real magnitude to rank by -- see SkyMapScene's SOLAR_SYSTEM_BODY_SENTINEL).
        val labeled = scene
            .filter { !it.skyObject.magnitude.isNaN() }
            .sortedBy { it.skyObject.magnitude }
            .take(STAR_LABEL_COUNT) +
            scene.filter { highlightedIds.contains(it.skyObject.id) || it.skyObject is SolarSystemObject }
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

private fun DrawScope.drawStar(
    center: Offset,
    magnitude: Float,
    color: Color,
    isHighlighted: Boolean,
    highlightColor: Color,
) {
    val radius = if (magnitude.isNaN()) {
        (MIN_STAR_RADIUS_PX + MAX_STAR_RADIUS_PX) / 2f
    } else {
        (MAX_STAR_RADIUS_PX - magnitude * 0.9f).coerceIn(MIN_STAR_RADIUS_PX, MAX_STAR_RADIUS_PX)
    }
    if (isHighlighted) {
        drawCircle(highlightColor, radius = radius + 6f, center = center, style = Stroke(width = 2f))
    }
    drawCircle(color, radius = radius, center = center)
}

private fun DrawScope.drawDeepSkyObject(
    center: Offset,
    type: SkyObjectType,
    color: Color,
    isHighlighted: Boolean,
    highlightColor: Color,
) {
    val radius = DSO_GLYPH_RADIUS_DP.toPx()
    val stroke = Stroke(width = 2f, pathEffect = dashEffectFor(type))
    if (isHighlighted) {
        drawCircle(highlightColor, radius = radius + 6f, center = center, style = Stroke(width = 2f))
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
}

/** Draws a bundled object photo centered at [center], its longest edge scaled to
 *  [targetLongestEdgePx] -- the photo's own aspect ratio is kept as-is (not stretched to the
 *  catalog's major/minor axis ratio), since the photo's framing rarely matches that ratio exactly
 *  and stretching would visibly distort it -- then rotated clockwise by [rotationDegrees] about
 *  its own center to align with true north on screen. */
private fun DrawScope.drawObjectPhoto(center: Offset, painter: Painter, targetLongestEdgePx: Float, rotationDegrees: Float) {
    val intrinsic = painter.intrinsicSize
    if (intrinsic.width <= 0f || intrinsic.height <= 0f) return
    val scale = targetLongestEdgePx / max(intrinsic.width, intrinsic.height)
    val drawWidth = intrinsic.width * scale
    val drawHeight = intrinsic.height * scale
    rotate(rotationDegrees, pivot = center) {
        translate(left = center.x - drawWidth / 2f, top = center.y - drawHeight / 2f) {
            with(painter) { draw(Size(drawWidth, drawHeight)) }
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
