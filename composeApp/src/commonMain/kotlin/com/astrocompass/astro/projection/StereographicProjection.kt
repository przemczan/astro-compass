package com.astrocompass.astro.projection

import com.astrocompass.astro.Vector3
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on the projection's tangent plane. Dimensionless (not pixels) -- the sky map's UI
 *  layer scales this to screen coordinates given the viewport's current field of view. */
data class PlanePoint(val x: Double, val y: Double)

/**
 * Stereographic (conformal) projection of the unit sphere onto a plane tangent at [center],
 * chosen over the plate solver's gnomonic projection because it stays well-behaved out to a
 * full-hemisphere field of view and preserves angles -- constellation shapes stay recognizable
 * near the edge of the sky map, not just near the center.
 *
 * [up] establishes which in-plane direction is "up" on screen (normally the ENU zenith). If it
 * turns out to be parallel to [center] -- looking straight at the zenith, the one direction with
 * no well-defined "up" -- an arbitrary fallback reference is used instead of throwing, since that
 * is a reachable viewport state (altitude clamps to +90), not a caller error.
 */
class StereographicProjection(center: Vector3, up: Vector3 = Vector3.UNIT_Z) {
    private val forward = center.normalized()
    private val right: Vector3
    private val planeUp: Vector3

    init {
        val reference = if ((forward cross up).length > 1e-6) up else Vector3.UNIT_X
        right = (forward cross reference).normalized()
        planeUp = right cross forward
    }

    /** Projects a direction onto the tangent plane, or null if it's on (or beyond) the far
     *  hemisphere from [center], where the projection is undefined/would blow up. */
    fun project(direction: Vector3): PlanePoint? {
        val v = direction.normalized()
        val zLocal = v dot forward
        if (zLocal <= -0.999) return null
        val scale = 2.0 / (1.0 + zLocal)
        return PlanePoint(x = (v dot right) * scale, y = (v dot planeUp) * scale)
    }

    /** Inverse of [project]: the direction a plane point corresponds to. */
    fun unproject(point: PlanePoint): Vector3 {
        val r = sqrt(point.x * point.x + point.y * point.y)
        if (r < 1e-12) return forward
        val theta = 2.0 * atan(r / 2.0)
        val sinTheta = sin(theta)
        val xLocal = point.x / r * sinTheta
        val yLocal = point.y / r * sinTheta
        val zLocal = cos(theta)
        return (right * xLocal + planeUp * yLocal + forward * zLocal).normalized()
    }
}
