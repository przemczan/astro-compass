package com.astrocompass.ui.skymap

import com.astrocompass.astro.Angle
import kotlin.math.cos

private const val MIN_FIELD_OF_VIEW_DEGREES = 0.5
private const val MAX_FIELD_OF_VIEW_DEGREES = 180.0

/** Azimuth panning is scaled by 1/cos(altitude) since meridians converge toward the zenith; this
 *  floor caps that scaling instead of letting it blow up as altitude approaches 90°. */
private const val MIN_COS_ALTITUDE_FOR_PANNING = 0.05

/**
 * Pan/zoom state for the sky map: a center direction in alt-az terms plus a field of view, kept
 * in this frame (rather than a free rotation) so screen-up is always the local zenith and the
 * horizon is always a straight, level line.
 */
data class SkyMapViewport(
    val centerAzimuth: Angle,
    val centerAltitude: Angle,
    val fieldOfViewDegrees: Double,
) {
    /**
     * Pans by a screen-space drag of ([dxPixels], [dyPixels]) -- Compose's pointer convention,
     * +x right / +y down -- over a canvas whose reference side is [referenceSizePixels] (the side
     * [fieldOfViewDegrees] is measured across). Content sticks to the finger, like dragging a
     * physical map: the point under the finger at the start of the drag is under it at the end,
     * so dragging right pans the center to lower azimuth and dragging down pans it to higher
     * altitude. Altitude clamps at the poles rather than flipping over the zenith/nadir.
     */
    fun pannedBy(dxPixels: Float, dyPixels: Float, referenceSizePixels: Float): SkyMapViewport {
        if (referenceSizePixels <= 0f) return this
        val degreesPerPixel = fieldOfViewDegrees / referenceSizePixels
        val cosAltitude = cos(centerAltitude.radians).coerceAtLeast(MIN_COS_ALTITUDE_FOR_PANNING)
        val newAzimuth = centerAzimuth.degrees - dxPixels * degreesPerPixel / cosAltitude
        val newAltitude = (centerAltitude.degrees + dyPixels * degreesPerPixel).coerceIn(-90.0, 90.0)
        return copy(
            centerAzimuth = Angle.ofDegrees(newAzimuth).normalized(),
            centerAltitude = Angle.ofDegrees(newAltitude),
        )
    }

    /** [factor] > 1 zooms in (narrower field of view), matching Compose's pinch-gesture `zoom`
     *  callback convention where fingers moving apart report a factor > 1. */
    fun zoomedBy(factor: Float): SkyMapViewport {
        if (factor <= 0f) return this
        val newFieldOfView = (fieldOfViewDegrees / factor).coerceIn(MIN_FIELD_OF_VIEW_DEGREES, MAX_FIELD_OF_VIEW_DEGREES)
        return copy(fieldOfViewDegrees = newFieldOfView)
    }

    companion object {
        val DEFAULT = SkyMapViewport(
            centerAzimuth = Angle.ofDegrees(180.0),
            centerAltitude = Angle.ofDegrees(45.0),
            fieldOfViewDegrees = 90.0,
        )
    }
}
