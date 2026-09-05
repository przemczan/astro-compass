package com.astrocompass.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** [SkyMapViewport.zoomedBy][com.astrocompass.ui.skymap.SkyMapViewport.zoomedBy]'s `factor` for one
 *  tap of [MapFollowZoomControls]'s zoom buttons -- shared so the two screens that use them zoom by
 *  the same amount per tap. */
const val MAP_ZOOM_STEP_FACTOR = 1.25f

/**
 * The control cluster overlaid on every sky map, so the map controls read as the same control
 * wherever they appear. [onEnableFollow] only ever turns following on; turning it off happens by
 * panning or pinching the map itself (see each caller's `onManualInteraction`), same as the
 * icon/tint here only ever reflects that state rather than toggling it.
 *
 * The zoom buttons call [onZoomIn]/[onZoomOut] directly rather than going through the map's own
 * gesture path, so -- unlike pinch-zoom -- they don't disengage following: they're the way to
 * zoom while the map keeps recentering on the telescope's pointing every tick.
 *
 * [onOpenFilter] belongs here rather than in the bottom toolbar because what the map draws is a
 * property of the map, not of the screen around it -- and every screen showing one needs it.
 */
@Composable
fun MapFollowZoomControls(
    isFollowing: Boolean,
    onEnableFollow: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onOpenFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.mapOverlayScrim().padding(4.dp),
    ) {
        IconButton(onClick = onEnableFollow) {
            Icon(
                if (isFollowing) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                contentDescription = "Follow telescope pointing",
                tint = if (isFollowing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onZoomIn) {
            Icon(Icons.Default.Add, contentDescription = "Zoom in")
        }
        IconButton(onClick = onZoomOut) {
            Icon(Icons.Default.Remove, contentDescription = "Zoom out")
        }
        IconButton(onClick = onOpenFilter) {
            Icon(Icons.Default.Visibility, contentDescription = "Filter visible objects")
        }
    }
}
