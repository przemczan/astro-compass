@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.astrocompass.telescope.MountSyncStep
import com.astrocompass.telescope.MountSyncStepOutcome
import com.astrocompass.telescope.MountSyncStepResult
import com.astrocompass.telescope.SlewRatePreset
import com.astrocompass.telescope.TelescopeConnectionState
import com.astrocompass.telescope.TelescopeReport
import com.astrocompass.telescope.TelescopeTransportKind
import kotlinx.coroutines.launch

/**
 * What the Telescope toolbar button opens on both Map and Guidance, whether or not a mount is
 * connected -- [connectionState] picks which content it shows, so there is one door in regardless
 * of state.
 *
 * Disconnected/Connecting/Failed: the connection form itself (Wi-Fi/TCP host+port,
 * Bluetooth Classic where [showBluetoothSection] allows it), right here rather than behind a
 * "Connect" button that opens another screen -- this sheet is the only door in, since the app menu
 * carries no Telescope entry of its own.
 *
 * Connected: the mount's reported position, GOTO/Abort for whatever the caller's [onGoto] targets,
 * Home, a way to end the session ([onDisconnect]), plus the two mount settings worth reaching
 * without leaving the sky map. All of it applies to the connected mount immediately -- there is no
 * Apply/Save step, and no local copy of any value. [gotoEnabled] exists because the Map screen (no
 * fixed guidance target) may have nothing selected to GOTO at all, unlike Guidance which always has
 * one.
 *
 * [slewError] and [trackingError] both surface a refusal inline rather than through a snackbar --
 * a [ModalBottomSheet] sits over the screen's own `SnackbarHost`, so a snackbar posted while this
 * is open would be invisible. [trackingEnabled] is null while the mount is still being asked, and
 * stays null if it never answered -- the toggle shows a spinner rather than guessing a state,
 * since a wrong guess here reads as "tracking is on" on a mount that is quietly drifting.
 */
@Composable
fun TelescopeSheet(
    connectionState: TelescopeConnectionState,
    reportedPosition: TelescopeReport?,
    mountSyncResults: List<MountSyncStepResult>,
    initialTcpHost: String,
    initialTcpPort: Int,
    onConnectTcp: suspend (host: String, port: Int) -> Unit,
    showBluetoothSection: Boolean,
    bondedBluetoothDevices: () -> List<Pair<String, String>>,
    onPairNewDevice: () -> Unit,
    initialBluetoothAddress: String?,
    onConnectBluetooth: suspend (address: String, name: String) -> Unit,
    onGoto: () -> Unit,
    gotoEnabled: Boolean,
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
    val isConnected = connectionState is TelescopeConnectionState.Connected
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            if (isConnected) {
                reportedPosition?.let {
                    Text(
                        "RA ${it.equatorialJNow.rightAscension.formatHms()}   Dec ${it.equatorialJNow.declination.formatDms()}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                if (mountSyncResults.isNotEmpty()) {
                    MountSyncChecklist(mountSyncResults, Modifier.padding(bottom = 12.dp))
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Button(onClick = onGoto, enabled = gotoEnabled) { Text("Goto") }
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
                ConnectionSetupSection(
                    connectionState = connectionState,
                    mountSyncResults = mountSyncResults,
                    initialTcpHost = initialTcpHost,
                    initialTcpPort = initialTcpPort,
                    onConnectTcp = onConnectTcp,
                    showBluetoothSection = showBluetoothSection,
                    bondedBluetoothDevices = bondedBluetoothDevices,
                    onPairNewDevice = onPairNewDevice,
                    initialBluetoothAddress = initialBluetoothAddress,
                    onConnectBluetooth = onConnectBluetooth,
                )
            }
        }
    }
}

/** The connection form shown whenever [TelescopeSheet] isn't connected -- the sheet is the one
 *  door in, so this covers everything from a cold start to a failed attempt. TCP works on both
 *  platforms; Bluetooth Classic SPP is Android-only (no non-MFi API on iOS -- see
 *  `StubBluetoothTelescopeTransport`), so [showBluetoothSection] hides that whole section rather
 *  than showing an always-empty device
 *  list. */
@Composable
private fun ConnectionSetupSection(
    connectionState: TelescopeConnectionState,
    mountSyncResults: List<MountSyncStepResult>,
    initialTcpHost: String,
    initialTcpPort: Int,
    onConnectTcp: suspend (host: String, port: Int) -> Unit,
    showBluetoothSection: Boolean,
    bondedBluetoothDevices: () -> List<Pair<String, String>>,
    onPairNewDevice: () -> Unit,
    initialBluetoothAddress: String?,
    onConnectBluetooth: suspend (address: String, name: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var hostText by remember { mutableStateOf(initialTcpHost) }
    var portText by remember { mutableStateOf(initialTcpPort.toString()) }

    // Re-read on every resume, not just on first composition -- pairing happens in Android's own
    // Bluetooth settings (see onPairNewDevice), which backgrounds this sheet's Activity without
    // destroying it, so a plain `remember` would keep showing the stale pre-pairing list on return.
    var bondedDevices by remember { mutableStateOf(bondedBluetoothDevices()) }
    LifecycleResumeEffect(Unit) {
        bondedDevices = bondedBluetoothDevices()
        onPauseOrDispose { }
    }

    // There is only ever one active connection -- these disambiguate *which* transport it's on, so
    // e.g. the TCP section doesn't show a busy state for a connection attempt that's actually
    // Bluetooth, and the other section's Connect button disables instead of implying it could start
    // a second, independent connection.
    val attemptedKind = when (connectionState) {
        is TelescopeConnectionState.Connecting -> connectionState.endpoint.kind
        is TelescopeConnectionState.Failed -> connectionState.endpoint.kind
        else -> null
    }
    val isBusy = connectionState is TelescopeConnectionState.Connecting
    val tcpBlockedByOtherTransport = attemptedKind == TelescopeTransportKind.BLUETOOTH_CLASSIC
    val bluetoothBlockedByOtherTransport = attemptedKind == TelescopeTransportKind.TCP

    ConnectionStatusRow(connectionState)
    if (mountSyncResults.isNotEmpty()) {
        MountSyncChecklist(mountSyncResults, Modifier.padding(top = 8.dp))
    }

    SectionTitle("Wi-Fi / TCP", modifier = Modifier.padding(top = 20.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = hostText,
            onValueChange = { hostText = it },
            label = { Text("Host") },
            singleLine = true,
            enabled = !isBusy && !tcpBlockedByOtherTransport,
            modifier = Modifier.weight(2f),
        )
        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it },
            label = { Text("Port") },
            singleLine = true,
            enabled = !isBusy && !tcpBlockedByOtherTransport,
            modifier = Modifier.weight(1f),
        )
    }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center) {
        Button(
            onClick = {
                val port = portText.toIntOrNull() ?: return@Button
                scope.launch { onConnectTcp(hostText, port) }
            },
            enabled = !isBusy && !tcpBlockedByOtherTransport &&
                hostText.isNotBlank() && portText.toIntOrNull() != null,
        ) { Text("Connect") }
    }

    if (showBluetoothSection) {
        HorizontalDivider(Modifier.padding(vertical = 24.dp))
        SectionTitle("Bluetooth")
        if (bondedDevices.isEmpty()) {
            // Deliberately not "pair it in Android Settings" -- an empty list here also covers a
            // denied BLUETOOTH_CONNECT permission or Bluetooth being switched off, and telling
            // someone to go pair a device that may already be paired is worse than being vague.
            Text(
                "No paired devices available. Check that Bluetooth is on, this app has Bluetooth " +
                    "permission, and your telescope's adapter is paired in Android Settings.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center) {
                OutlinedButton(
                    onClick = onPairNewDevice,
                    enabled = !isBusy,
                ) { Text("Pair new device") }
            }
        } else {
            var expanded by remember { mutableStateOf(false) }
            var selected by remember(bondedDevices) {
                mutableStateOf(bondedDevices.firstOrNull { (address, _) -> address == initialBluetoothAddress } ?: bondedDevices.first())
            }
            val selectionEnabled = !isBusy && !bluetoothBlockedByOtherTransport

            // A plain OutlinedButton rather than ExposedDropdownMenuBox/OutlinedTextField -- the
            // latter is a full-height (56dp) Material text field and looks mismatched next to the
            // 40dp "Pair new device" button. The trailing dropdown-arrow icon is what marks this
            // one as a selector instead of an action, at the same height as its neighbor.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { expanded = true },
                        enabled = selectionEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(selected.second, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        bondedDevices.forEach { device ->
                            DropdownMenuItem(
                                text = { Text(device.second) },
                                onClick = { selected = device; expanded = false },
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = onPairNewDevice,
                    enabled = !isBusy,
                ) { Text("Pair new device") }
            }

            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center) {
                Button(
                    onClick = { scope.launch { onConnectBluetooth(selected.first, selected.second) } },
                    enabled = !isBusy && !bluetoothBlockedByOtherTransport,
                ) { Text("Connect") }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(bottom = 8.dp))
}

@Composable
private fun ConnectionStatusRow(state: TelescopeConnectionState) {
    when (state) {
        TelescopeConnectionState.Disconnected ->
            Text("Not connected", style = MaterialTheme.typography.titleMedium)

        is TelescopeConnectionState.Connecting ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp))
                Text(
                    "Connecting to ${state.endpoint.displayName}...",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

        is TelescopeConnectionState.Connected ->
            Text("Connected to ${state.endpoint.displayName}", style = MaterialTheme.typography.titleMedium)

        is TelescopeConnectionState.Failed ->
            Text(
                "Couldn't connect: ${state.reason}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
            )
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

/** [com.astrocompass.telescope.TelescopeConnection.connect] runs the mount-sync sequence itself,
 *  with no separate button here -- this is purely a status readout. Nothing in the app gates on it,
 *  see [com.astrocompass.telescope.TelescopeConnection.mountSyncResults]. */
@Composable
private fun MountSyncChecklist(results: List<MountSyncStepResult>, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            results.forEach { result ->
                Text(
                    "${result.step.label()} ${result.outcome.symbol()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = result.outcome.color(),
                )
            }
        }
        results.forEach { result ->
            val reason = (result.outcome as? MountSyncStepOutcome.Failed)?.reason ?: return@forEach
            Text(
                "${result.step.label()}: $reason",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun MountSyncStep.label(): String = when (this) {
    MountSyncStep.LINK -> "Link"
    MountSyncStep.TIME -> "Time"
    MountSyncStep.SITE -> "Site"
    MountSyncStep.UNPARK -> "Unpark"
    MountSyncStep.TRACKING -> "Tracking"
}

private fun MountSyncStepOutcome.symbol(): String = when (this) {
    is MountSyncStepOutcome.Success -> "✓"
    is MountSyncStepOutcome.Failed -> "⚠"
    is MountSyncStepOutcome.Skipped -> "–"
}

@Composable
private fun MountSyncStepOutcome.color() = when (this) {
    is MountSyncStepOutcome.Success -> MaterialTheme.colorScheme.onSurfaceVariant
    is MountSyncStepOutcome.Failed -> MaterialTheme.colorScheme.error
    is MountSyncStepOutcome.Skipped -> MaterialTheme.colorScheme.onSurfaceVariant
}
