package com.astrocompass.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Icon (24.dp, Material's default) plus [BADGE_PADDING] on every side, whether or not a badge
 *  is actually drawn -- reserving the badged variant's full footprint for every icon is what
 *  keeps every button's label starting at the same height, see [ToolbarActionButton]. */
private val ICON_SLOT_SIZE = 36.dp
private val BADGE_PADDING = 6.dp

/** A toolbar action rendered as a stacked icon + label -- [androidx.compose.material3.BottomAppBar]'s
 *  usual content is bare [androidx.compose.material3.IconButton]s, which carry no visible label. The
 *  icon's own content description is left null since the adjacent [Text] already names the action.
 *  [containerColor], when set, draws a filled pill behind the icon (matching Material's selected-nav-
 *  item look) so an action that still needs doing -- not just labeled, but visually flagged -- stands
 *  out from the toolbar's other, already-available actions.
 *
 *  The icon always sits in a fixed-size [ICON_SLOT_SIZE] box, badged or not: an unbadged icon is
 *  smaller than a badged one (no circle background/padding), so without this every unbadged
 *  button's label would sit higher than a badged button's -- a per-button height difference, not
 *  a per-row one, so it can't be fixed by aligning the row itself. */
@Composable
fun ToolbarActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color? = null,
    contentColor: Color = LocalContentColor.current,
) {
    val resolvedContentColor = if (enabled) contentColor else contentColor.copy(alpha = 0.38f)
    Column(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(ICON_SLOT_SIZE), contentAlignment = Alignment.Center) {
            val iconModifier = if (containerColor != null) {
                Modifier.clip(CircleShape).background(containerColor).padding(BADGE_PADDING)
            } else {
                Modifier
            }
            Icon(icon, contentDescription = null, tint = resolvedContentColor, modifier = iconModifier)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = resolvedContentColor)
    }
}
