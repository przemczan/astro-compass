package com.astrocompass.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.astrocompass.guiding.GuidingMode

/**
 * Picks which source drives pointing -- shared by the Guidance and Alignment screens so switching
 * between phone and mount is the same control wherever it appears, and so both stay in step: the
 * mode is one app-wide setting (see [com.astrocompass.AppContainer.guidingMode]), not per-screen.
 *
 * Sits at the far left of both toolbars, deliberately outside whatever gate hides the
 * mode-specific actions beside it -- a mount that never reports a position is precisely when
 * someone needs to get back to [GuidingMode.PHONE].
 */
@Composable
fun GuidingModeButton(
    mode: GuidingMode,
    telescopeConnected: Boolean,
    onModeChange: (GuidingMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolbarActionButton(icon = Icons.Default.SwapHoriz, label = mode.label, onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in GuidingMode.entries) {
                DropdownMenuItem(
                    text = { Text(option.label) },
                    enabled = option != GuidingMode.TELESCOPE || telescopeConnected,
                    trailingIcon = {
                        if (option == mode) Icon(Icons.Default.Check, contentDescription = "Selected")
                    },
                    onClick = {
                        onModeChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
