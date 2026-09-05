package com.astrocompass.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/**
 * A live camera preview of [cameraId] (and, if non-null, specifically its [physicalCameraId] lens
 * -- see [com.astrocompass.platesolve.CameraDescriptor]'s doc comment), cropped to at most its own
 * center 50%x50% so the alignment wizard's crosshair step only ever
 * shows the region relevant to lining up a telescope's field of view -- the crop zooms in further,
 * automatically, as [panFraction] approaches a frame edge, so the visible window never samples
 * past the real image. [panFraction] shifts which part of the raw frame that crop shows, as a
 * fraction of the frame's own width/height in the same upright coordinate convention as
 * [com.astrocompass.platesolve.CapturedFrame] (0,0 = no pan); dragging inside the preview reports
 * deltas through [onDrag], already converted out of the crop's current zoom so the caller only ever
 * deals in frame fractions.
 *
 * This is the one piece of platform UI that can't be a plain injected interface like
 * [com.astrocompass.platesolve.CameraCapture] -- a live preview is inherently a platform `View`,
 * not a service -- so it uses the same `expect`/`actual` escape hatch as
 * [com.astrocompass.astro.time.currentEpochMillis]. The iOS `actual` is a placeholder: there is no
 * AVFoundation camera binding yet.
 */
@Composable
expect fun CameraPreviewSurface(
    cameraId: String?,
    physicalCameraId: String?,
    panFraction: Offset,
    onDrag: (Offset) -> Unit,
    modifier: Modifier = Modifier,
)
