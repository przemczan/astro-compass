package com.astrocompass.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** The phone/telescope mode picker is hidden behind this gate rather than deleted, so
 *  [com.astrocompass.guiding.GuidingMode.TELESCOPE] stays wired but unreachable and one flip brings
 *  the whole path back -- mount connection itself ([SHOW_TELESCOPE_ENTRIES]) no longer needs the
 *  same gate, since connecting to a mount and setting up its alignment is useful on its own even
 *  while guiding stays phone-driven. */
private const val SHOW_MODE_MENU_ITEM = false
private const val SHOW_TELESCOPE_ENTRIES = true

/** The app-wide destinations every bottom bar's menu offers, passed down from `App.kt` so no screen
 *  has to carry them as individual parameters. [isStarAligned] is a claim about the *phone's* star
 *  fit specifically -- not [com.astrocompass.guiding.PointingService.isAligned], which is also true
 *  under the compass fallback. */
data class AppMenuActions(
    val isStarAligned: Boolean,
    val onOpenAlignment: () -> Unit,
    val onOpenNightWizard: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenTelescope: () -> Unit,
    val isTelescopeConnected: Boolean,
)

/**
 * Every screen's bottom bar: the hamburger menu and a divider, both constant, then whatever context
 * actions the screen itself supplies. Actions that belong to the *app* rather than the screen live
 * in the menu, so a toolbar only ever shows what applies where the user currently is.
 *
 * Trailing actions (see [ToolbarCancelButton]) right-align themselves, so [content] can simply list
 * its buttons in order without laying the row out.
 */
@Composable
fun AppBottomBar(menu: AppMenuActions, content: @Composable RowScope.() -> Unit) {
    BottomAppBar {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppMenuButton(menu)
            ToolbarDivider()
            content()
        }
    }
}

/** A trailing toolbar action, pushed to the end of the bar behind its own divider -- the way out of
 *  whatever the screen is in the middle of, and always the rightmost button wherever it appears. */
@Composable
fun RowScope.ToolbarCancelButton(onClick: () -> Unit, label: String = "Cancel") {
    Spacer(Modifier.weight(1f))
    ToolbarDivider()
    ToolbarActionButton(icon = Icons.Default.Close, label = label, onClick = onClick)
}

/** The divider separating toolbar groups -- one definition so every bar's separators match. */
@Composable
fun ToolbarDivider() {
    VerticalDivider(Modifier.height(32.dp).padding(horizontal = 4.dp))
}

/** Opens over the toolbar (Material anchors a [DropdownMenu] wherever it fits, which above a bottom
 *  bar means upward). Badged when the phone still needs calibrating: burying that state in a closed
 *  menu would otherwise lose the at-a-glance cue the toolbar used to carry. */
@Composable
private fun AppMenuButton(menu: AppMenuActions) {
    var expanded by remember { mutableStateOf(false) }
    val needsCalibration = !menu.isStarAligned
    Box {
        ToolbarActionButton(
            icon = Icons.Default.Menu,
            label = "Menu",
            onClick = { expanded = true },
            containerColor = if (needsCalibration) MaterialTheme.colorScheme.primaryContainer else null,
            contentColor = if (needsCalibration) MaterialTheme.colorScheme.onPrimaryContainer else LocalContentColor.current,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppMenuItem(
                icon = Icons.Default.Explore,
                label = "Calibrate",
                supportingText = if (menu.isStarAligned) "Calibrated" else "Not calibrated",
                onClick = { expanded = false; menu.onOpenAlignment() },
            )
            AppMenuItem(
                icon = Icons.Default.AutoAwesome,
                label = "Night wizard",
                onClick = { expanded = false; menu.onOpenNightWizard() },
            )
            AppMenuItem(
                icon = Icons.Default.Settings,
                label = "Settings",
                onClick = { expanded = false; menu.onOpenSettings() },
            )
            if (SHOW_TELESCOPE_ENTRIES) {
                AppMenuItem(
                    icon = Icons.Default.SettingsInputAntenna,
                    label = "Telescope",
                    supportingText = if (menu.isTelescopeConnected) "Connected" else null,
                    onClick = { expanded = false; menu.onOpenTelescope() },
                )
            }
            if (SHOW_MODE_MENU_ITEM) {
                AppMenuItem(icon = Icons.Default.SwapHoriz, label = "Mode", onClick = { expanded = false })
            }
        }
    }
}

@Composable
private fun AppMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    supportingText: String? = null,
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label)
                if (supportingText != null) {
                    Text(
                        supportingText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}
