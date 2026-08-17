@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.astrocompass.telescope.SlewRatePreset

/** The Guidance toolbar's "Options" button (Telescope mode only) opens this: the two mount
 *  settings worth reaching without leaving the sky map. Both apply to the connected mount
 *  immediately -- there is no Apply/Save step, and no local copy of either value.
 *
 *  [trackingEnabled] is null while the mount is still being asked, and stays null if it never
 *  answered -- the toggle shows a spinner rather than guessing a state, since a wrong guess here
 *  reads as "tracking is on" on a mount that is quietly drifting. [trackingError] carries a
 *  refusal (OnStep won't start tracking while parked) inline instead of through a snackbar the
 *  sheet itself would cover. */
@Composable
fun TelescopeOptionsSheet(
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
            Text(
                "GOTO speed",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            for (preset in SlewRatePreset.entries) {
                SlewRateRow(
                    preset = preset,
                    selected = preset == slewRatePreset,
                    onSelect = { onSlewRatePresetChange(preset) },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            TrackingRow(enabled = trackingEnabled, onEnabledChange = onTrackingEnabledChange)
            if (trackingError != null) {
                Text(
                    trackingError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SlewRateRow(preset: SlewRatePreset, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(preset.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
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
