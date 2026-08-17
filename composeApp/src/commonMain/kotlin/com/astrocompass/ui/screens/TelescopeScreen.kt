@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astrocompass.telescope.MountSyncStep
import com.astrocompass.telescope.MountSyncStepOutcome
import com.astrocompass.telescope.MountSyncStepResult
import com.astrocompass.telescope.TelescopeConnectionState
import com.astrocompass.telescope.TelescopeReport
import com.astrocompass.telescope.TelescopeTransportKind
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** TCP works on both platforms; Bluetooth Classic SPP is Android-only (no non-MFi API on iOS --
 *  see `StubBluetoothTelescopeTransport`), so [showBluetoothSection] hides that whole section
 *  rather than showing an always-empty device list. GOTO/Slew controls are a later milestone. */
@Composable
fun TelescopeScreen(
    connectionState: StateFlow<TelescopeConnectionState>,
    reportedPosition: StateFlow<TelescopeReport?>,
    mountSyncResults: StateFlow<List<MountSyncStepResult>>,
    initialTcpHost: String,
    initialTcpPort: Int,
    onConnectTcp: suspend (host: String, port: Int) -> Unit,
    showBluetoothSection: Boolean,
    bondedBluetoothDevices: List<Pair<String, String>>,
    initialBluetoothAddress: String?,
    onConnectBluetooth: suspend (address: String, name: String) -> Unit,
    onDisconnect: suspend () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by connectionState.collectAsState()
    val report by reportedPosition.collectAsState()
    val syncResults by mountSyncResults.collectAsState()
    val scope = rememberCoroutineScope()

    var hostText by remember { mutableStateOf(initialTcpHost) }
    var portText by remember { mutableStateOf(initialTcpPort.toString()) }

    // There is only ever one active connection -- these disambiguate *which* transport it's on,
    // so e.g. the TCP section doesn't show "Disconnect" for a connection that's actually
    // Bluetooth, and the other section's Connect button disables instead of implying it could
    // start a second, independent connection.
    val activeKind = when (val currentState = state) {
        is TelescopeConnectionState.Connecting -> currentState.endpoint.kind
        is TelescopeConnectionState.Connected -> currentState.endpoint.kind
        else -> null
    }
    val isBusy = state is TelescopeConnectionState.Connecting
    val tcpConnected = activeKind == TelescopeTransportKind.TCP && state is TelescopeConnectionState.Connected
    val tcpBlockedByOtherTransport = activeKind == TelescopeTransportKind.BLUETOOTH_CLASSIC
    val bluetoothConnected = activeKind == TelescopeTransportKind.BLUETOOTH_CLASSIC && state is TelescopeConnectionState.Connected
    val bluetoothBlockedByOtherTransport = activeKind == TelescopeTransportKind.TCP

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Telescope") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            SectionTitle("Connection")
            ConnectionStatusRow(state)
            report?.let {
                Text(
                    "RA ${it.equatorialJNow.rightAscension.formatHms()}   Dec ${it.equatorialJNow.declination.formatDms()}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (syncResults.isNotEmpty()) {
                MountSyncChecklist(syncResults, Modifier.padding(top = 8.dp))
            }

            SectionTitle("Wi-Fi / TCP", modifier = Modifier.padding(top = 24.dp))
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
                if (tcpConnected) {
                    OutlinedButton(onClick = { scope.launch { onDisconnect() } }, enabled = !isBusy) { Text("Disconnect") }
                } else {
                    Button(
                        onClick = {
                            val port = portText.toIntOrNull() ?: return@Button
                            scope.launch { onConnectTcp(hostText, port) }
                        },
                        enabled = !isBusy && !tcpBlockedByOtherTransport &&
                            hostText.isNotBlank() && portText.toIntOrNull() != null,
                    ) { Text("Connect") }
                }
            }

            if (showBluetoothSection) {
                HorizontalDivider(Modifier.padding(vertical = 24.dp))
                BluetoothSection(
                    bondedDevices = bondedBluetoothDevices,
                    initialAddress = initialBluetoothAddress,
                    isBusy = isBusy,
                    isConnected = bluetoothConnected,
                    isBlockedByOtherTransport = bluetoothBlockedByOtherTransport,
                    onConnect = { address, name -> scope.launch { onConnectBluetooth(address, name) } },
                    onDisconnect = { scope.launch { onDisconnect() } },
                )
            }
        }
    }
}

@Composable
private fun BluetoothSection(
    bondedDevices: List<Pair<String, String>>,
    initialAddress: String?,
    isBusy: Boolean,
    isConnected: Boolean,
    isBlockedByOtherTransport: Boolean,
    onConnect: (address: String, name: String) -> Unit,
    onDisconnect: () -> Unit,
) {
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
        return
    }

    var expanded by remember { mutableStateOf(false) }
    var selected by remember(bondedDevices) {
        mutableStateOf(bondedDevices.firstOrNull { (address, _) -> address == initialAddress } ?: bondedDevices.first())
    }

    Row {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = !isBusy && !isConnected && !isBlockedByOtherTransport,
        ) { Text(selected.second) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            bondedDevices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device.second) },
                    onClick = { selected = device; expanded = false },
                )
            }
        }
    }

    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center) {
        if (isConnected) {
            OutlinedButton(onClick = onDisconnect, enabled = !isBusy) { Text("Disconnect") }
        } else {
            Button(
                onClick = { onConnect(selected.first, selected.second) },
                enabled = !isBusy && !isBlockedByOtherTransport,
            ) { Text("Connect") }
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
            Text("Disconnected", style = MaterialTheme.typography.bodyMedium)

        is TelescopeConnectionState.Connecting ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp))
                Text(
                    "Connecting to ${state.endpoint.displayName}...",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

        is TelescopeConnectionState.Connected ->
            Text("Connected to ${state.endpoint.displayName}", style = MaterialTheme.typography.bodyMedium)

        is TelescopeConnectionState.Failed ->
            Text(
                "Couldn't connect: ${state.reason}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
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
