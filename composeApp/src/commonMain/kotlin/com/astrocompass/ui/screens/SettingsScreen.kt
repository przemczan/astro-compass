@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.astrocompass.astro.Angle
import com.astrocompass.guiding.CameraMounting
import com.astrocompass.guiding.TelescopeAxis
import com.astrocompass.location.ObserverLocation
import com.astrocompass.sensors.OrientationSensor
import com.astrocompass.sensors.SensorSource
import com.astrocompass.settings.AppPreferences
import com.astrocompass.ui.theme.AppTheme
import com.astrocompass.update.AppRelease
import com.astrocompass.update.AppUpdater
import com.astrocompass.update.InstallOutcome
import com.astrocompass.update.isNewerVersion
import kotlinx.coroutines.launch

private const val BUY_ME_A_COFFEE_URL = "https://buymeacoffee.com/przemczan"

@Composable
fun SettingsScreen(
    preferences: AppPreferences,
    orientationSensor: OrientationSensor,
    appUpdater: AppUpdater,
    resolvedLocation: ObserverLocation?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            DonationSection()

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionTitle("Location")
            LocationSection(preferences, resolvedLocation)

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionTitle("Telescope axis")
            TelescopeAxisSection(preferences)

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionTitle("On-target tolerance")
            ToleranceSection(preferences)

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionTitle("Appearance")
            ThemeSection(preferences)
            HorizonDimmingSection(preferences)

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionTitle("Object images (Beta)")
            ObjectImagesSection(preferences)

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionTitle("Data sources & licenses")
            Text(
                "Stars: HYG Database (Astronomy Nexus / David Nash), CC BY-SA 4.0.\n" +
                    "Deep sky: OpenNGC (Mattia Verga), CC BY-SA 4.0.\n" +
                    "Constellation lines & Milky Way: d3-celestial (Olaf Frohn), BSD-3-Clause.\n" +
                    "Deep-sky photos: Digitized Sky Survey (STScI / AAO-UKST) via CDS hips2fits.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionTitle("Advanced")
            AdvancedSection(preferences, orientationSensor)

            // Sideloaded (no Play Store) is exactly why this exists, and exactly why it's Android-
            // only -- iOS has nothing to install a downloaded .ipa into. See AppUpdater.isSupported.
            if (appUpdater.isSupported) {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                SectionTitle("Updates")
                UpdatesSection(appUpdater)
            }
        }
    }
}

@Composable
private fun DonationSection() {
    val uriHandler = LocalUriHandler.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Button(onClick = { uriHandler.openUri(BUY_ME_A_COFFEE_URL) }) {
            Icon(Icons.Default.LocalCafe, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Buy me a coffee")
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun LocationSection(preferences: AppPreferences, resolvedLocation: ObserverLocation?) {
    val manualLocation by preferences.manualLocation.collectAsState()
    // Keyed on manualLocation so an external change (e.g. clearing it elsewhere) resyncs the
    // checkbox, but a local toggle in between persists without being fought by recomposition.
    var useCurrentLocation by remember(manualLocation) { mutableStateOf(manualLocation == null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = useCurrentLocation,
            onCheckedChange = { checked ->
                useCurrentLocation = checked
                if (checked) preferences.setManualLocation(null)
            },
        )
        Text("Use current location (GPS)", style = MaterialTheme.typography.bodyMedium)
    }

    if (useCurrentLocation) {
        Text(
            resolvedLocation?.let {
                "Current: ${formatDegrees(it.latitude.degrees)}°, ${formatDegrees(it.longitude.degrees)}°"
            } ?: "No location available yet",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    } else {
        val prefill = manualLocation ?: resolvedLocation
        var latText by remember { mutableStateOf(prefill?.latitude?.degrees?.toString() ?: "") }
        var lonText by remember { mutableStateOf(prefill?.longitude?.degrees?.toString() ?: "") }

        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(latText, { latText = it }, label = { Text("Latitude") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(lonText, { lonText = it }, label = { Text("Longitude") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
            Button(onClick = {
                val lat = latText.toDoubleOrNull()?.coerceIn(-90.0, 90.0)
                val lon = lonText.toDoubleOrNull()?.coerceIn(-180.0, 180.0)
                if (lat != null && lon != null) {
                    preferences.setManualLocation(ObserverLocation(Angle.ofDegrees(lat), Angle.ofDegrees(lon)))
                }
            }) { Text("Save location") }
        }
    }
}

@Composable
private fun TelescopeAxisSection(preferences: AppPreferences) {
    val current by preferences.telescopeAxis.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    Row {
        OutlinedButton(onClick = { expanded = true }) { Text(current.label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TelescopeAxis.entries.forEach { axis ->
                DropdownMenuItem(
                    text = { Text(axis.label) },
                    onClick = { preferences.setTelescopeAxis(axis); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun ToleranceSection(preferences: AppPreferences) {
    val current by preferences.onTargetToleranceDegrees.collectAsState()
    Text("${formatDegrees(current)}°", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = current.toFloat(),
        onValueChange = { preferences.setOnTargetToleranceDegrees(it.toDouble()) },
        valueRange = 0.1f..3.0f,
    )
}

@Composable
private fun ThemeSection(preferences: AppPreferences) {
    val current by preferences.appTheme.collectAsState()
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val options = listOf<AppTheme?>(null, AppTheme.Light, AppTheme.Dark, AppTheme.Night)
        options.forEachIndexed { index, theme ->
            SegmentedButton(
                selected = current == theme,
                onClick = { preferences.setAppTheme(theme) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(theme?.name ?: "System") },
            )
        }
    }
}

@Composable
private fun HorizonDimmingSection(preferences: AppPreferences) {
    val current by preferences.dimBelowHorizon.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Checkbox(checked = current, onCheckedChange = { preferences.setDimBelowHorizon(it) })
        Text("Dim objects below the horizon", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ObjectImagesSection(preferences: AppPreferences) {
    val current by preferences.showObjectImages.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = current, onCheckedChange = { preferences.setShowObjectImages(it) })
        Text("Show real photos for deep-sky objects on the map", style = MaterialTheme.typography.bodyMedium)
    }
    Text(
        "Replaces an object's dot with its bundled photo once zoomed in enough. Sourcing and " +
            "orientation are still rough for some objects -- turn this off if it's more distracting " +
            "than useful.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun AdvancedSection(preferences: AppPreferences, orientationSensor: OrientationSensor) {
    val override by preferences.sensorSourceOverride.collectAsState()
    val active = override ?: orientationSensor.activeSource
    Text("Active sensor: ${active.name}", style = MaterialTheme.typography.bodyMedium)
    Text(
        "Gyroscope: ${orientationSensor.capabilities.hasGyroscope}, " +
            "Magnetometer: ${orientationSensor.capabilities.hasMagnetometer}",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 8.dp),
    )

    var expanded by remember { mutableStateOf(false) }
    Row {
        OutlinedButton(onClick = { expanded = true }) { Text(override?.name ?: "Auto (recommended)") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Auto (recommended)") }, onClick = { preferences.setSensorSourceOverride(null); expanded = false })
            SensorSource.entries.forEach { source ->
                DropdownMenuItem(
                    text = { Text(source.name) },
                    onClick = { preferences.setSensorSourceOverride(source); expanded = false },
                )
            }
        }
    }
    Text(
        "Takes effect the next time the app starts.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )

    Text(
        "Camera mounting",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 16.dp),
    )
    val mounting by preferences.cameraMounting.collectAsState()
    var mountingExpanded by remember { mutableStateOf(false) }
    Row {
        OutlinedButton(onClick = { mountingExpanded = true }) { Text(mounting.label) }
        DropdownMenu(expanded = mountingExpanded, onDismissRequest = { mountingExpanded = false }) {
            CameraMounting.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { preferences.setCameraMounting(option); mountingExpanded = false },
                )
            }
        }
    }
    Text(
        "Only matters once you use Platesolve. If an applied correction looks way off (tens of " +
            "degrees, not a few), try a different option here.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * "Check for updates" fetches the repo's whole release history in one call -- it both answers "is
 * there something newer" and populates the version picker below, rather than being two separate
 * network round trips for what is, from GitHub's side, the same list either way.
 *
 * [installingVersion] tracks whichever release is mid-download/install (there is only ever one at
 * a time -- both Install buttons disable while it's non-null) so the progress bar and the two
 * trigger sites (the "update available" card and the version picker) share one download instead of
 * each carrying its own copy of the [InstallOutcome] handling.
 */
@Composable
private fun UpdatesSection(appUpdater: AppUpdater) {
    var releases by remember { mutableStateOf<List<AppRelease>?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var selectedVersion by remember { mutableStateOf<String?>(null) }
    var installingVersion by remember { mutableStateOf<String?>(null) }
    var installProgress by remember { mutableStateOf(0f) }
    var installError by remember { mutableStateOf<String?>(null) }
    var needsInstallPermission by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val currentVersion = appUpdater.currentVersion
    val latestRelease = releases?.firstOrNull()
    val updateAvailable = latestRelease != null && isNewerVersion(latestRelease.version, currentVersion)

    val performInstall: (AppRelease) -> Unit = { release ->
        installingVersion = release.version
        installProgress = 0f
        installError = null
        needsInstallPermission = false
        scope.launch {
            when (val outcome = appUpdater.downloadAndInstall(release) { progress -> installProgress = progress }) {
                InstallOutcome.Started -> installingVersion = null
                InstallOutcome.PermissionRequired -> {
                    needsInstallPermission = true
                    installingVersion = null
                }
                is InstallOutcome.Failed -> {
                    installError = outcome.reason
                    installingVersion = null
                }
            }
        }
    }

    Text("Current version: $currentVersion", style = MaterialTheme.typography.bodyMedium)

    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
        Button(
            onClick = {
                isChecking = true
                checkError = null
                scope.launch {
                    appUpdater.fetchReleases().fold(
                        onSuccess = { fetched ->
                            releases = fetched
                            selectedVersion = fetched.firstOrNull()?.version
                        },
                        onFailure = { checkError = it.message ?: "Couldn't check for updates" },
                    )
                    isChecking = false
                }
            },
            enabled = !isChecking,
        ) {
            Text(if (isChecking) "Checking…" else "Check for updates")
        }
    }
    if (checkError != null) {
        Text(
            checkError.orEmpty(),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (latestRelease != null) {
        if (updateAvailable) {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("Update available: ${latestRelease.version}", style = MaterialTheme.typography.bodyLarge)
                if (latestRelease.notes.isNotBlank()) {
                    Text(
                        latestRelease.notes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Button(onClick = { performInstall(latestRelease) }, enabled = installingVersion == null) {
                        Text("Install ${latestRelease.version}")
                    }
                }
            }
        } else {
            Text(
                "You're up to date.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }

    if (installingVersion != null) {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            LinearProgressIndicator(progress = { installProgress }, modifier = Modifier.fillMaxWidth())
            Text(
                "Downloading $installingVersion… ${(installProgress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    if (needsInstallPermission) {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text(
                "Allow AstroCompass to install apps, then try again.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                OutlinedButton(onClick = { appUpdater.openInstallPermissionSettings() }) { Text("Open settings") }
            }
        }
    }
    if (installError != null) {
        Text(
            installError.orEmpty(),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    val allReleases = releases
    if (allReleases != null) {
        Text(
            "Install a specific version",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        var pickerExpanded by remember { mutableStateOf(false) }
        Row {
            OutlinedButton(onClick = { pickerExpanded = true }) { Text(selectedVersion ?: "Choose a version") }
            DropdownMenu(expanded = pickerExpanded, onDismissRequest = { pickerExpanded = false }) {
                allReleases.forEach { release ->
                    val isCurrent = release.version.removePrefix("v") == currentVersion.removePrefix("v")
                    DropdownMenuItem(
                        text = { Text(release.version + if (isCurrent) " (current)" else "") },
                        onClick = { selectedVersion = release.version; pickerExpanded = false },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
            Button(
                onClick = { allReleases.firstOrNull { it.version == selectedVersion }?.let(performInstall) },
                enabled = selectedVersion != null && installingVersion == null,
            ) {
                Text("Install selected version")
            }
        }
    }
}

private fun formatDegrees(value: Double): String = (kotlin.math.round(value * 100) / 100).toString()
