package com.astrocompass.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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

/** A toolbar action rendered as a stacked icon + label -- [androidx.compose.material3.BottomAppBar]'s
 *  usual content is bare [androidx.compose.material3.IconButton]s, which carry no visible label. The
 *  icon's own content description is left null since the adjacent [Text] already names the action.
 *  [containerColor], when set, draws a filled pill behind the icon (matching Material's selected-nav-
 *  item look) so an action that still needs doing -- not just labeled, but visually flagged -- stands
 *  out from the toolbar's other, already-available actions. */
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
        val iconModifier = if (containerColor != null) {
            Modifier.clip(CircleShape).background(containerColor).padding(6.dp)
        } else {
            Modifier
        }
        Icon(icon, contentDescription = null, tint = resolvedContentColor, modifier = iconModifier)
        Text(label, style = MaterialTheme.typography.labelSmall, color = resolvedContentColor)
    }
}
