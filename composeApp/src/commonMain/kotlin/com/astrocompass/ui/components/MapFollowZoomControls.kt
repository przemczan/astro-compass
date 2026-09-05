package com.astrocompass.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.astrocompass.ui.theme.TelescopeBlue

/** [SkyMapViewport.zoomedBy][com.astrocompass.ui.skymap.SkyMapViewport.zoomedBy]'s `factor` for one
 *  tap of [MapFollowZoomControls]'s zoom buttons -- shared so the two screens that use them zoom by
 *  the same amount per tap. */
const val MAP_ZOOM_STEP_FACTOR = 1.25f

/** What the map's follow button recenters on -- [Companion.next] is the tap-to-cycle order,
 *  computed here (not by each caller) since it's the one piece of behavior every caller must agree
 *  on for the button to mean the same thing wherever it appears. */
enum class MapFollowMode {
    NONE,
    PHONE,
    TELESCOPE,
    ;

    /** The next mode a tap lands on. [hasTelescope] skips [TELESCOPE] entirely when there's no
     *  connected mount to follow -- a caller that never offers telescope-following at all (see
     *  [MapFollowZoomControls]'s own doc comment) can just always pass `false` here to keep a plain
     *  two-state cycle regardless of any real connection. */
    fun next(hasTelescope: Boolean): MapFollowMode = when (this) {
        PHONE -> if (hasTelescope) TELESCOPE else NONE
        TELESCOPE -> NONE
        NONE -> PHONE
    }
}

/**
 * The control cluster overlaid on every sky map, so the map controls read as the same control
 * wherever they appear. Tapping the follow button cycles [MapFollowMode] forward (see
 * [MapFollowMode.next]); turning it off can also happen by panning or pinching the map itself (see
 * each caller's `onManualInteraction`), so the icon/tint here only ever reflects the current mode,
 * never a separate "is this button pressed" state of its own.
 *
 * [hasTelescope] is a per-caller *offer*, not just today's raw connection state: Guidance is
 * phone-only by design (see `GuidanceScreen`'s own doc comment on why) and always passes `false`
 * here regardless of whether a mount happens to be connected, while the Map screen passes its own
 * live connection state so the cycle only ever offers a mode it can actually satisfy.
 *
 * The zoom buttons call [onZoomIn]/[onZoomOut] directly rather than going through the map's own
 * gesture path, so -- unlike pinch-zoom -- they don't disengage following: they're the way to
 * zoom while the map keeps recentering on whatever it's following every tick.
 *
 * [onOpenFilter] belongs here rather than in the bottom toolbar because what the map draws is a
 * property of the map, not of the screen around it -- and every screen showing one needs it.
 */
@Composable
fun MapFollowZoomControls(
    followMode: MapFollowMode,
    hasTelescope: Boolean,
    onFollowModeChange: (MapFollowMode) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onOpenFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.mapOverlayScrim().padding(4.dp),
    ) {
        val (icon, description, tint) = when (followMode) {
            MapFollowMode.PHONE -> Triple<ImageVector, String, Color>(Icons.Default.GpsFixed, "Following phone pointing", MaterialTheme.colorScheme.primary)
            // Reuses the same icon as the app's other telescope affordances (the Telescope toolbar
            // button, the sheet it opens) and TelescopeBlue, the color a connected mount's own map
            // marker already draws in -- both are the app's established "this means telescope"
            // signals, so this state reads as telescope-related at a glance rather than needing a
            // new symbol invented just for this button.
            MapFollowMode.TELESCOPE -> Triple(Icons.Default.SettingsInputAntenna, "Following telescope pointing", TelescopeBlue)
            MapFollowMode.NONE -> Triple(Icons.Default.GpsNotFixed, "Not following", MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { onFollowModeChange(followMode.next(hasTelescope)) }) {
            Icon(icon, contentDescription = description, tint = tint)
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
