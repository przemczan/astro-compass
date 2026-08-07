package com.astroguider.guiding

import com.astroguider.astro.Angle
import com.astroguider.astro.Vector3
import com.astroguider.astro.coords.HorizontalCoordinates
import kotlin.math.atan2
import kotlin.math.cos

/**
 * Pure: two sky-frame (ENU) unit vectors in, a [Guidance] out. Deliberately expressed entirely
 * in alt-az terms rather than projected onto the phone's screen plane -- the screen's in-plane
 * axes depend on [TelescopeAxis], and for some choices (e.g. an edge axis) the "forward"
 * direction coincides with one of them, which breaks a screen-projected arrow geometrically.
 * Working purely in alt-az sidesteps that, is mounting- and display-rotation-independent, and
 * keeps the arrow visually consistent with the altitude/cross-track bars it sits next to (the
 * arrow is exactly their vector sum).
 */
object GuidanceCalculator {

    fun compute(
        currentPointing: Vector3,
        target: Vector3,
        onTargetToleranceDegrees: Double,
    ): Guidance {
        val separation = currentPointing.angleTo(target).degrees

        val current = HorizontalCoordinates.fromEnu(currentPointing)
        val targetHorizontal = HorizontalCoordinates.fromEnu(target)

        val altitudeDelta = targetHorizontal.altitude.degrees - current.altitude.degrees
        // Azimuth converges near the zenith (30 deg of Delta-az at 80 deg altitude is only ~5 deg
        // of sky), so this is shown as cross-track (Delta-az * cos(altitude)), never the raw
        // azimuth difference -- see the plan's "Guidance math and the arrow" section.
        val rawAzimuthDelta = (targetHorizontal.azimuth - current.azimuth).normalizedSigned().degrees
        val crossTrackDelta = rawAzimuthDelta * cos(current.altitude.radians)

        val arrowAngle = Angle.ofRadians(atan2(crossTrackDelta, altitudeDelta)).normalized().degrees

        return Guidance(
            separationDegrees = separation,
            altitudeDeltaDegrees = altitudeDelta,
            crossTrackDeltaDegrees = crossTrackDelta,
            arrowAngleDegrees = arrowAngle,
            isOnTarget = separation <= onTargetToleranceDegrees,
        )
    }
}
