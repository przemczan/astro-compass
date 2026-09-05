@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.astrocompass.telescope.SlewRatePreset

/**
 * What the Guidance toolbar's single "Telescope" button opens, whether or not a mount is connected
 * -- [isConnected] picks which content it shows, so there is one door in regardless of state.
 *
 * Disconnected: just a way back to [com.astrocompass.ui.screens.TelescopeScreen] ([onConnect]),
 * the same screen the app menu's own "Telescope" entry opens.
 *
 * Connected: GOTO/Abort/Home for the screen's current target, a way to end the session
 * ([onDisconnect]), plus the two mount settings worth reaching without leaving the sky map. All of
 * it applies to the connected mount immediately -- there is no Apply/Save step, and no local copy
 * of any value.
 *
 * [slewError] and [trackingError] both surface a refusal inline rather than through a snackbar --
 * a [ModalBottomSheet] sits over the screen's own `SnackbarHost`, so a snackbar posted while this
 * is open would be invisible. [trackingEnabled] is null while the mount is still being asked, and
 * stays null if it never answered -- the toggle shows a spinner rather than guessing a state,
 * since a wrong guess here reads as "tracking is on" on a mount that is quietly drifting.
 */
@Composable
fun TelescopeSheet(
    isConnected: Boolean,
    onConnect: () -> Unit,
    onGoto: () -> Unit,
    onAbortSlew: () -> Unit,
    slewError: String?,
    onMoveHome: () -> Unit,
    onDisconnect: () -> Unit,
    slewRatePreset: SlewRatePreset,
    onSlewRatePresetChange: (SlewRatePreset) -> Unit,
    trackingEnabled: Boolean?,
    onTrackingEnabledChange: (Boolean) -> Unit,
    trackingError: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            if (isConnected) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Button(onClick = onGoto) { Text("Goto") }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = onAbortSlew) { Text("Abort") }
                }
                if (slewError != null) {
                    Text(
                        slewError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
                    OutlinedButton(onClick = onMoveHome) { Text("Home") }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Disconnect") }
                }

                HorizontalDivider(Modifier.padding(vertical = 16.dp))

                SlewRateDropdown(
                    selected = slewRatePreset,
                    onSelect = onSlewRatePresetChange,
                )

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                TrackingRow(enabled = trackingEnabled, onEnabledChange = onTrackingEnabledChange)
                if (trackingError != null) {
                    Text(
                        trackingError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                Text(
                    "Telescope not connected",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Button(onClick = onConnect) { Text("Connect") }
                }
            }
        }
    }
}

@Composable
private fun SlewRateDropdown(selected: SlewRatePreset, onSelect: (SlewRatePreset) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "GOTO speed",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selected.label,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (preset in SlewRatePreset.entries) {
                    DropdownMenuItem(
                        text = { Text(preset.label) },
                        onClick = {
                            onSelect(preset)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackingRow(enabled: Boolean?, onEnabledChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .then(
                if (enabled == null) Modifier
                else Modifier.toggleable(value = enabled, role = Role.Switch, onValueChange = onEnabledChange)
            )
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Tracking", style = MaterialTheme.typography.bodyLarge)
        if (enabled == null) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}
