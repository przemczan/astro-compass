@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.coords.HorizontalCoordinates
import com.astrocompass.astro.time.currentEpochMillis
import com.astrocompass.catalog.CatalogRepository
import com.astrocompass.catalog.MapObjectFilter
import com.astrocompass.catalog.SkyObject
import com.astrocompass.guiding.AlignmentStatus
import com.astrocompass.guiding.SkyPointingSource
import com.astrocompass.guiding.currentHorizontal
import com.astrocompass.location.ObserverLocation
import com.astrocompass.telescope.MountSyncStepResult
import com.astrocompass.telescope.SlewOutcome
import com.astrocompass.telescope.SlewRatePreset
import com.astrocompass.telescope.TelescopeConnectionState
import com.astrocompass.telescope.TelescopeReport
import com.astrocompass.ui.BackHandler
import com.astrocompass.ui.components.MAP_ZOOM_STEP_FACTOR
import com.astrocompass.ui.components.AppBottomBar
import com.astrocompass.ui.components.AppMenuActions
import com.astrocompass.ui.components.MapFilterSheet
import com.astrocompass.ui.components.MapFollowMode
import com.astrocompass.ui.components.MapFollowZoomControls
import com.astrocompass.ui.components.SkyMap
import com.astrocompass.ui.components.SkyMapMarker
import com.astrocompass.ui.components.TelescopeSheet
import com.astrocompass.ui.components.ToolbarActionButton
import com.astrocompass.ui.components.mapOverlayScrim
import com.astrocompass.ui.components.rememberSkyMapSnapshot
import com.astrocompass.ui.skymap.SkyMapViewport
import com.astrocompass.ui.theme.WarningAmber
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** How long a second back press has to follow the first to actually exit, rather than just
 *  re-showing the "press back again" message -- long enough for a deliberate double press,
 *  short enough that it doesn't feel like the first press did nothing. */
private const val DOUBLE_BACK_TO_EXIT_WINDOW_MILLIS = 2_000L

/** The main/home screen: a full-sky browse map plus whatever's currently marked. Search lives on
 *  its own screen ([SearchScreen]) reached from the bottom toolbar -- this screen shows the
 *  same catalog [SkyMap]'s own zoom-driven reveal curves would show anywhere else (see
 *  [rememberSkyMapSnapshot]), never a query- or category-narrowed subset.
 *
 *  This is also the app's back-navigation root: [BackHandler] here only fires when nothing else
 *  (Settings/Alignment/Search/Guidance) is showing, since `App.kt`'s own `BackHandler` claims the
 *  back press for inter-screen navigation whenever one of those is up. A single press here shows a
 *  "press again to exit" [SnackbarHost] rather than exiting immediately, so a back press meant to
 *  dismiss something else (a keyboard, a sheet) can't accidentally quit the app instead. */
@Composable
fun MapScreen(
    catalogRepository: CatalogRepository,
    location: ObserverLocation?,
    pointingSource: SkyPointingSource,
    /** Marked in blue while a connected mount is reporting -- see
     *  [com.astrocompass.AppContainer.telescopeSkyDirection]. */
    telescopeDirection: Vector3?,
    menu: AppMenuActions,
    selectedTarget: SkyObject?,
    onSelectTarget: (SkyObject) -> Unit,
    onGoto: () -> Unit,
    viewport: SkyMapViewport,
    onViewportChange: (SkyMapViewport) -> Unit,
    showObjectPhotos: Boolean,
    dimBelowHorizon: Boolean,
    milkyWayBrightness: Float,
    mapObjectFilter: MapObjectFilter,
    onMapObjectFilterChange: (MapObjectFilter) -> Unit,
    onOpenSearch: () -> Unit,
    onExitApp: () -> Unit,
    // Reached from the Telescope button's bottom sheet -- see TelescopeSheet's doc comment. Unlike
    // Guidance, there's no fixed target here: onGotoTelescope takes whichever SkyObject the caller
    // hands it (selectedTarget, when there is one), rather than closing over one fixed target the
    // way Guidance's own onGoto does.
    onGotoTelescope: suspend (SkyObject) -> SlewOutcome,
    onAbortSlewTelescope: suspend () -> Unit,
    onMoveHomeTelescope: suspend () -> Unit,
    onDisconnectTelescope: suspend () -> Unit,
    telescopeConnectionState: StateFlow<TelescopeConnectionState>,
    telescopeReportedPosition: StateFlow<TelescopeReport?>,
    telescopeMountSyncResults: StateFlow<List<MountSyncStepResult>>,
    initialTelescopeTcpHost: String,
    initialTelescopeTcpPort: Int,
    onConnectTelescopeTcp: suspend (host: String, port: Int) -> Unit,
    showTelescopeBluetoothSection: Boolean,
    bondedTelescopeBluetoothDevices: () -> List<Pair<String, String>>,
    onPairNewTelescopeBluetoothDevice: () -> Unit,
    initialTelescopeBluetoothAddress: String?,
    onConnectTelescopeBluetooth: suspend (address: String, name: String) -> Unit,
    slewRatePreset: SlewRatePreset,
    onSlewRatePresetChange: suspend (SlewRatePreset) -> Unit,
    onReadTelescopeTracking: suspend () -> Boolean?,
    onSetTelescopeTracking: suspend (Boolean) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var showFilterSheet by remember { mutableStateOf(false) }
    var showTelescopeSheet by remember { mutableStateOf(false) }
    // Shared by both places the mount can be sent home from on this screen (today, just the
    // Telescope sheet's own button, but keeping the same pattern GuidanceScreen uses) -- see that
    // screen's own showHomeConfirmation for the full reasoning.
    var showHomeConfirmation by remember { mutableStateOf(false) }
    var trackingEnabled by remember { mutableStateOf<Boolean?>(null) }
    var trackingError by remember { mutableStateOf<String?>(null) }
    var slewError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showTelescopeSheet) {
        if (!showTelescopeSheet) return@LaunchedEffect
        trackingEnabled = null
        trackingError = null
        slewError = null
        trackingEnabled = onReadTelescopeTracking()
    }

    var lastBackPressEpochMillis by remember { mutableStateOf(0L) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    BackHandler(enabled = true) {
        val now = currentEpochMillis()
        if (now - lastBackPressEpochMillis <= DOUBLE_BACK_TO_EXIT_WINDOW_MILLIS) {
            onExitApp()
        } else {
            lastBackPressEpochMillis = now
            coroutineScope.launch { snackbarHostState.showSnackbar("Press back again to exit") }
        }
    }

    // Targets whichever SkyObject is selected *at the moment of the tap*, not a fixed one -- unlike
    // Guidance's performGoto, which always slews to that screen's one fixed target.
    val performTelescopeGoto: () -> Unit = {
        val target = selectedTarget
        if (target != null) {
            slewError = null
            coroutineScope.launch {
                when (val outcome = onGotoTelescope(target)) {
                    is SlewOutcome.Rejected -> slewError = outcome.reason
                    SlewOutcome.NoConnection -> slewError = "Telescope not connected"
                    SlewOutcome.Started -> {}
                }
            }
        }
    }
    val performTelescopeAbort: () -> Unit = { coroutineScope.launch { onAbortSlewTelescope() } }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Find an object") }) },
        bottomBar = {
            AppBottomBar(menu) {
                ToolbarActionButton(icon = Icons.Default.Search, label = "Search", onClick = onOpenSearch)
                ToolbarActionButton(
                    icon = Icons.Default.SettingsInputAntenna,
                    label = "Telescope",
                    onClick = { showTelescopeSheet = true },
                    containerColor = if (menu.isTelescopeConnected) MaterialTheme.colorScheme.primaryContainer else null,
                    contentColor = if (menu.isTelescopeConnected) MaterialTheme.colorScheme.onPrimaryContainer else LocalContentColor.current,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (location == null) {
            LocationRequiredPrompt(menu.onOpenSettings, Modifier.padding(padding))
            return@Scaffold
        }

        val isLoaded by catalogRepository.isLoaded.collectAsState()
        if (!isLoaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val snapshot = rememberSkyMapSnapshot(
            catalogRepository, location,
            filterKey = mapObjectFilter,
            catalogFilter = mapObjectFilter::matches,
        )
        val now = snapshot.nowEpochMillis

        // NONE by default -- unlike Guidance, this is a browse map, so following anything isn't
        // the reason someone opened it. Same recenter-on-tick pattern as GuidanceScreen's follow
        // effect otherwise, just gated behind an opt-in toggle -- and, unlike Guidance, offering
        // TELESCOPE as a real destination (see MapFollowZoomControls' hasTelescope param below),
        // since this screen's whole point is browsing the sky rather than a single fixed target.
        val currentPointing by pointingSource.currentSkyDirection.collectAsState()
        var followMode by remember { mutableStateOf(MapFollowMode.NONE) }
        LaunchedEffect(currentPointing, telescopeDirection, followMode) {
            val direction = when (followMode) {
                MapFollowMode.PHONE -> currentPointing
                MapFollowMode.TELESCOPE -> telescopeDirection
                MapFollowMode.NONE -> null
            }
            if (direction != null) {
                val horizontal = HorizontalCoordinates.fromEnu(direction)
                onViewportChange(viewport.copy(centerAzimuth = horizontal.azimuth, centerAltitude = horizontal.altitude))
            }
        }

        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                SkyMap(
                    directions = snapshot.directions,
                    viewport = viewport,
                    onViewportChange = onViewportChange,
                    onManualInteraction = { followMode = MapFollowMode.NONE },
                    constellationLines = snapshot.constellationLines,
                    milkyWayCells = snapshot.milkyWayCells,
                    milkyWayGridStepDegrees = snapshot.milkyWayGridStepDegrees,
                    northOffsetDirections = snapshot.northOffsetDirections,
                    showObjectPhotos = showObjectPhotos,
                    dimBelowHorizon = dimBelowHorizon,
                    milkyWayBrightness = milkyWayBrightness,
                    markers = listOfNotNull(
                        selectedTarget?.let { target ->
                            SkyMapMarker(
                                direction = target.currentHorizontal(location, now).toEnu(),
                                color = MaterialTheme.colorScheme.primary,
                                label = target.displayName,
                            )
                        },
                        currentPointing?.let { direction ->
                            SkyMapMarker(
                                direction = direction,
                                color = MaterialTheme.colorScheme.secondary,
                                label = "Phone",
                                labelAbove = true,
                            )
                        },
                        telescopeDirection?.let(SkyMapMarker::telescope),
                    ),
                    onSelect = { obj -> onSelectTarget(obj) },
                    modifier = Modifier.fillMaxSize(),
                )
                MapFollowZoomControls(
                    followMode = followMode,
                    hasTelescope = menu.isTelescopeConnected,
                    onFollowModeChange = { followMode = it },
                    onZoomIn = { onViewportChange(viewport.zoomedBy(MAP_ZOOM_STEP_FACTOR)) },
                    onZoomOut = { onViewportChange(viewport.zoomedBy(1f / MAP_ZOOM_STEP_FACTOR)) },
                    onOpenFilter = { showFilterSheet = true },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
                )
                // End padding (both banners) clears the control cluster sharing this corner of the map.
                when (menu.alignmentStatus) {
                    AlignmentStatus.NOT_CALIBRATED -> NotCalibratedBanner(
                        onCalibrate = menu.onOpenAlignment,
                        modifier = Modifier.align(Alignment.TopStart).padding(top = 8.dp, start = 8.dp, end = 64.dp),
                    )
                    AlignmentStatus.AWAITING_FIRST_PLATE_SOLVE -> AwaitingPlateSolveBanner(
                        modifier = Modifier.align(Alignment.TopStart).padding(top = 8.dp, start = 8.dp, end = 64.dp),
                    )
                    AlignmentStatus.CALIBRATED -> {}
                }
                if (selectedTarget != null) {
                    Row(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Button(onClick = onGoto) { Text("Goto ${selectedTarget.displayName}") }
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        MapFilterSheet(
            filter = mapObjectFilter,
            onFilterChange = onMapObjectFilterChange,
            onDismiss = { showFilterSheet = false },
        )
    }

    if (showHomeConfirmation) {
        AlertDialog(
            onDismissRequest = { showHomeConfirmation = false },
            title = { Text("Send telescope home?") },
            text = { Text("The mount will slew away from its current position, off whatever it's pointed at now.") },
            confirmButton = {
                TextButton(onClick = { showHomeConfirmation = false; coroutineScope.launch { onMoveHomeTelescope() } }) { Text("Send home") }
            },
            dismissButton = {
                TextButton(onClick = { showHomeConfirmation = false }) { Text("Cancel") }
            },
        )
    }

    if (showTelescopeSheet) {
        val connectionState by telescopeConnectionState.collectAsState()
        val reportedPosition by telescopeReportedPosition.collectAsState()
        val mountSyncResults by telescopeMountSyncResults.collectAsState()
        TelescopeSheet(
            connectionState = connectionState,
            reportedPosition = reportedPosition,
            mountSyncResults = mountSyncResults,
            initialTcpHost = initialTelescopeTcpHost,
            initialTcpPort = initialTelescopeTcpPort,
            onConnectTcp = onConnectTelescopeTcp,
            showBluetoothSection = showTelescopeBluetoothSection,
            bondedBluetoothDevices = bondedTelescopeBluetoothDevices,
            onPairNewDevice = onPairNewTelescopeBluetoothDevice,
            initialBluetoothAddress = initialTelescopeBluetoothAddress,
            onConnectBluetooth = onConnectTelescopeBluetooth,
            // Nothing may be selected on this screen at all, unlike Guidance's fixed target.
            gotoEnabled = selectedTarget != null,
            onGoto = performTelescopeGoto,
            onAbortSlew = performTelescopeAbort,
            slewError = slewError,
            onMoveHome = { showHomeConfirmation = true },
            onDisconnect = { coroutineScope.launch { onDisconnectTelescope() } },
            slewRatePreset = slewRatePreset,
            onSlewRatePresetChange = { preset -> coroutineScope.launch { onSlewRatePresetChange(preset) } },
            trackingEnabled = trackingEnabled,
            // Applied optimistically only once the mount has accepted: a refused enable (OnStep
            // will not start tracking while parked) must leave the toggle showing the mount's real
            // state, not the state the user asked for.
            onTrackingEnabledChange = { desired ->
                coroutineScope.launch {
                    if (onSetTelescopeTracking(desired)) {
                        trackingEnabled = desired
                        trackingError = null
                    } else {
                        trackingError = if (desired) "Mount refused to start tracking — is it parked?"
                        else "Mount refused to stop tracking."
                    }
                }
            },
            trackingError = trackingError,
            onDismiss = { showTelescopeSheet = false },
        )
    }
}

/** Shown for [AlignmentStatus.NOT_CALIBRATED] -- no real calibration exists yet. Deliberately a
 *  nudge and not a wall: pointing still works off the compass fallback (see
 *  [com.astrocompass.alignment.CompassAlignment]), it is just rough -- which the menu's own badge
 *  alone was too easy to miss. See [AwaitingPlateSolveBanner] for the sibling state where a
 *  calibration *does* exist but hasn't produced a live fix yet. */
@Composable
private fun NotCalibratedBanner(onCalibrate: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.mapOverlayScrim().clickable(onClick = onCalibrate).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Explore,
            contentDescription = null,
            tint = WarningAmber,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column {
            Text("Not calibrated", color = WarningAmber, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Pointing is rough — tap to calibrate.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Shown for [AlignmentStatus.AWAITING_FIRST_PLATE_SOLVE] -- a camera setup is fully calibrated,
 *  but its background solver (see [com.astrocompass.guiding.AutoPlateSolveRefiner]) hasn't landed
 *  a fix yet this run, so pointing is still running off the compass fallback underneath it.
 *  Deliberately not clickable, unlike [NotCalibratedBanner]: there is nothing for the user to do
 *  here, it resolves itself once the telescope holds still long enough for one solve (which only
 *  happens once Guidance is open for some target -- the background solver doesn't run on this
 *  screen). */
@Composable
private fun AwaitingPlateSolveBanner(modifier: Modifier = Modifier) {
    Row(
        modifier.mapOverlayScrim().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Explore,
            contentDescription = null,
            tint = WarningAmber,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column {
            Text("Camera calibrated", color = WarningAmber, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Pointing is rough until the first plate solve, in Guidance.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LocationRequiredPrompt(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.padding(bottom = 16.dp))
        Text("Location needed", style = MaterialTheme.typography.titleLarge)
        Text(
            "Every altitude and azimuth in this app depends on knowing where you are. Set your location to continue.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Button(onClick = onOpenSettings) { Text("Set location") }
        }
    }
}
