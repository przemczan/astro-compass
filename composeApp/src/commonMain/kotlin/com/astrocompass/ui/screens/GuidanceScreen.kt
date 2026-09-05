@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.coords.HorizontalCoordinates
import com.astrocompass.astro.time.currentEpochMillis
import com.astrocompass.catalog.CatalogRepository
import com.astrocompass.catalog.MapObjectFilter
import com.astrocompass.catalog.SkyObject
import com.astrocompass.catalog.searchDisplayLabel
import com.astrocompass.guiding.AbsoluteReferenceState
import com.astrocompass.guiding.AlignmentStatus
import com.astrocompass.guiding.GuidanceCalculator
import com.astrocompass.guiding.SkyPointingSource
import com.astrocompass.guiding.currentHorizontal
import com.astrocompass.location.ObserverLocation
import com.astrocompass.telescope.MountSyncStepResult
import com.astrocompass.telescope.MoveRatePreset
import com.astrocompass.telescope.SlewOutcome
import com.astrocompass.telescope.SlewRatePreset
import com.astrocompass.telescope.TelescopeConnectionState
import com.astrocompass.telescope.TelescopeDirection
import com.astrocompass.telescope.TelescopeReport
import com.astrocompass.ui.components.AppBottomBar
import com.astrocompass.ui.components.AppMenuActions
import com.astrocompass.ui.components.DeltaBar
import com.astrocompass.ui.components.MAP_ZOOM_STEP_FACTOR
import com.astrocompass.ui.components.MapFilterSheet
import com.astrocompass.ui.components.MapFollowMode
import com.astrocompass.ui.components.MapFollowZoomControls
import com.astrocompass.ui.components.ToolbarCancelButton
import com.astrocompass.ui.components.mapOverlayScrim
import com.astrocompass.ui.components.SkyMap
import com.astrocompass.ui.components.SkyMapGuidancePath
import com.astrocompass.ui.components.SkyMapMarker
import com.astrocompass.ui.components.TelescopeControlPad
import com.astrocompass.ui.components.TelescopeSheet
import com.astrocompass.ui.components.ToolbarActionButton
import com.astrocompass.ui.components.rememberSkyMapSnapshot
import com.astrocompass.ui.skymap.SkyMapViewport
import com.astrocompass.ui.theme.GuidancePathAmber
import com.astrocompass.ui.theme.OnTargetGreen
import com.astrocompass.ui.theme.WarningAmber
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val UPDATE_INTERVAL_MS = 50L
private const val CATALOG_REFRESH_INTERVAL_MS = 5_000L

// Smaller than headlineSmall's default 24.sp so the readout takes up less of the map without the
// target marker underneath it staying hidden as long.
private val ANGLE_TEXT_SIZE = 18.sp

@Composable
fun GuidanceScreen(
    target: SkyObject,
    // Retargeting from the map -- see the SkyMap's onSelect wiring below. Only reachable outside
    // the Night Wizard (wizardProgress == null) and while the telescope isn't mid-slew, since a
    // tap here doesn't fit the wizard's fixed Prev/Next list and a moving mount is already
    // committed to the target it was sent to.
    onSelectTarget: (SkyObject) -> Unit,
    pointingSource: SkyPointingSource,
    /** Marked in blue while a connected mount is reporting -- see
     *  [com.astrocompass.AppContainer.telescopeSkyDirection]. Entirely separate from
     *  [pointingSource], which always serves the phone's own pointing: this is only ever shown as
     *  its own marker, alongside it, never in place of it. */
    telescopeDirection: Vector3?,
    absoluteReference: StateFlow<AbsoluteReferenceState?>,
    location: ObserverLocation,
    catalogRepository: CatalogRepository,
    onTargetToleranceDegrees: Double,
    /** Runs the background solve loop for as long as this screen is up -- a no-op unless the setup
     *  was aligned with a camera (see [com.astrocompass.AppContainer.setAutoPlateSolveActive]). */
    onAutoPlateSolveActive: (Boolean) -> Unit,
    menu: AppMenuActions,
    onOpenSearch: () -> Unit,
    onExitGuiding: () -> Unit,
    // Reached from the Telescope button's bottom sheet, not gated on the phone's own pointing
    // state at all -- a connected mount's GOTO needs no phone alignment, only its own connection.
    onGoto: suspend () -> SlewOutcome,
    onAbortSlew: suspend () -> Unit,
    onMoveHome: suspend () -> Unit,
    onDisconnectTelescope: suspend () -> Unit,
    // The Telescope sheet's own connection form -- see TelescopeSheet's doc comment for why this
    // lives in the sheet now rather than a separate screen reached from the app menu.
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
    /** Hand-controller motion for the floating "Manual controls" sheet -- press/release and the
     *  rate it moves at. [onStopAllMotion] is deliberately not suspending; see [TelescopeControlPad]. */
    onPressDirection: suspend (TelescopeDirection) -> Unit,
    onReleaseDirection: suspend (TelescopeDirection) -> Unit,
    onMoveRateChange: suspend (MoveRatePreset) -> Unit,
    onStopAllMotion: () -> Unit,
    slewRatePreset: SlewRatePreset,
    onSlewRatePresetChange: suspend (SlewRatePreset) -> Unit,
    onReadTracking: suspend () -> Boolean?,
    onSetTracking: suspend (Boolean) -> Boolean,
    showObjectPhotos: Boolean,
    dimBelowHorizon: Boolean,
    milkyWayBrightness: Float,
    mapObjectFilter: MapObjectFilter,
    onMapObjectFilterChange: (MapObjectFilter) -> Unit,
    // Night Wizard mode: non-null wizardProgress swaps the header/toolbar to walk through a fixed
    // object list instead of the single-target Search/Telescope/Exit layout. `first` is the
    // 1-based current index, `second` the total count.
    wizardProgress: Pair<Int, Int>? = null,
    onNextObject: () -> Unit = {},
    onPreviousObject: () -> Unit = {},
    onOpenWizardOptions: () -> Unit = {},
    /** One step back up the wizard's stack, to the object list -- what the top bar's arrow does,
     *  matching system Back. Options is a step *forward* from here (the toolbar's own button), and
     *  deliberately not where the arrow leads: a Back that reached Options, whose own Back returned
     *  here, would be a loop with no way out. */
    onBackToObjectList: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val currentPointing by pointingSource.currentSkyDirection.collectAsState()
    val reference by absoluteReference.collectAsState()
    val scope = rememberCoroutineScope()

    var now by remember { mutableStateOf(currentEpochMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(UPDATE_INTERVAL_MS)
            now = currentEpochMillis()
        }
    }

    // A DisposableEffect, not a LaunchedEffect: the loop belongs to the container's own scope and
    // owns an in-flight camera capture, so a recomposition here must not be able to cancel it.
    DisposableEffect(Unit) {
        onAutoPlateSolveActive(true)
        onDispose { onAutoPlateSolveActive(false) }
    }

    // The map's catalog snapshot is rebuilt on its own, much slower cadence than `now` above --
    // recomputing precession/ENU for the whole catalog every UPDATE_INTERVAL_MS (for a
    // sub-pixel-at-any-zoom improvement; the sky moves ~15"/s) would be pure waste.
    val snapshot = rememberSkyMapSnapshot(
        catalogRepository, location,
        refreshIntervalMillis = CATALOG_REFRESH_INTERVAL_MS,
        filterKey = mapObjectFilter,
        catalogFilter = mapObjectFilter::matches,
    )

    // Tracks a commanded GOTO that hasn't yet landed -- blocks picking a new target off the map
    // mid-slew (see onSelectTarget's doc). Cleared by Abort or once the mount's own reported
    // position lands on the target (see telescopeOnTarget below) -- deliberately not the phone's
    // own guidance.isOnTarget, which the phone may never report at all if it's held separately from
    // the telescope rather than mounted on it. Keyed on target's identity so switching targets
    // (including the wizard's Prev/Next) starts each one clean.
    var telescopeSlewing by remember(target) { mutableStateOf(false) }
    val targetDirection = target.currentHorizontal(location, now).toEnu()
    val telescopeOnTarget = telescopeDirection?.let { it.angleTo(targetDirection).degrees <= onTargetToleranceDegrees } ?: false
    LaunchedEffect(telescopeOnTarget) {
        if (telescopeOnTarget) telescopeSlewing = false
    }
    var mapViewport by remember { mutableStateOf(SkyMapViewport.DEFAULT) }
    // Only ever PHONE or NONE: Guidance is phone-only by design (see this file's own doc comment),
    // so MapFollowZoomControls is always told hasTelescope = false below regardless of any real
    // mount connection, keeping this a plain two-state toggle even though the type now also has
    // a TELESCOPE case (which the Map screen's own follow button does use).
    var followMode by remember { mutableStateOf(MapFollowMode.PHONE) }
    // Deriving this directly during composition, rather than centering mapViewport itself via a
    // LaunchedEffect keyed on currentPointing, matters: that LaunchedEffect's state write landed
    // one recomposition *after* the currentPointing update that triggered it, so the current-
    // position marker (and the guidance path, projected under the same viewport) would render one
    // frame off-center before the camera caught up -- visible as a small jump on every sensor
    // sample while panning/tilting the phone. This has no such gap: displayedViewport is exactly
    // in step with currentPointing on every recomposition, sensor-driven or not.
    //
    // The cost of never writing back is that mapViewport goes stale the moment follow turns on --
    // see MapFollowZoomControls' onFollowModeChange below for why turning it back off has to
    // account for that explicitly rather than just falling back to mapViewport as-is.
    val displayedViewport = if (followMode == MapFollowMode.PHONE && currentPointing != null) {
        val horizontal = HorizontalCoordinates.fromEnu(currentPointing!!)
        mapViewport.copy(centerAzimuth = horizontal.azimuth, centerAltitude = horizontal.altitude)
    } else {
        mapViewport
    }

    // Tracking is read from the mount each time the sheet opens rather than polled -- nothing else
    // in the app reacts to it, so a second periodic command alongside the position poll would buy
    // nothing. Null means "not answered yet", which is what the sheet renders as a spinner instead
    // of an assumed state.
    var showFilterSheet by remember { mutableStateOf(false) }
    var showTelescopeSheet by remember { mutableStateOf(false) }
    // Shared by both places the mount can be sent home from -- the floating TelescopeActionRow and
    // the Telescope sheet's own button -- so confirming (or canceling) from either behaves
    // identically, and there's exactly one dialog to keep in sync rather than two.
    var showHomeConfirmation by remember { mutableStateOf(false) }
    var trackingEnabled by remember { mutableStateOf<Boolean?>(null) }
    var trackingError by remember { mutableStateOf<String?>(null) }
    var slewError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showTelescopeSheet) {
        if (!showTelescopeSheet) return@LaunchedEffect
        trackingEnabled = null
        trackingError = null
        slewError = null
        trackingEnabled = onReadTracking()
    }

    // Shared by the Telescope sheet's own Goto/Abort row and the floating action row over the map,
    // so the two doors into the same command behave identically rather than each carrying its own
    // copy of the SlewOutcome handling.
    val performGoto: () -> Unit = {
        telescopeSlewing = true
        slewError = null
        scope.launch {
            when (val outcome = onGoto()) {
                is SlewOutcome.Rejected -> {
                    telescopeSlewing = false
                    slewError = outcome.reason
                }
                SlewOutcome.NoConnection -> {
                    telescopeSlewing = false
                    slewError = "Telescope not connected"
                }
                SlewOutcome.Started -> {}
            }
        }
    }
    val performAbort: () -> Unit = {
        scope.launch { onAbortSlew() }
        telescopeSlewing = false
    }

    // The floating "Manual controls" sheet's own state -- mirrors StarAlignmentStep's Controls
    // overlay (see [TelescopeControlPad]), the code this reuses.
    var showTelescopeControls by remember { mutableStateOf(false) }
    var moveRate by remember { mutableStateOf(MoveRatePreset.DEFAULT) }
    val pressJobs = remember { mutableMapOf<TelescopeDirection, Job>() }
    // Sent when the sheet opens as well as when the rate is picked: the rate lives on the mount,
    // and the pad's own default has never been sent to it, so opening without this would move at
    // whatever the mount was last set to while the label claimed otherwise.
    LaunchedEffect(showTelescopeControls, moveRate) {
        if (showTelescopeControls) onMoveRateChange(moveRate)
    }

    // pointingSource always serves the phone's own sensors now -- a connected mount's own reported
    // position is shown separately (telescopeDirection) and never substitutes for this. So there's
    // exactly one reason the not-ready wall shows: no star/compass reference yet.
    val activeReference = reference
    val phoneNeedsAlignment = activeReference == null

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(target.searchDisplayLabel()) },
                navigationIcon = {
                    if (wizardProgress != null) {
                        IconButton(onClick = onBackToObjectList) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the object list")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (wizardProgress != null) {
                    val (index, total) = wizardProgress
                    Text(
                        "$index / $total",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                AppBottomBar(menu) {
                    if (wizardProgress != null) {
                        val (index, total) = wizardProgress
                        // Search is deliberately absent here: a searched target doesn't fit the
                        // wizard's fixed Prev/Next list, the same reason map-tap retargeting is
                        // gated off below.
                        ToolbarActionButton(
                            icon = Icons.AutoMirrored.Filled.NavigateBefore,
                            label = "Prev",
                            enabled = index > 1,
                            onClick = onPreviousObject,
                            modifier = Modifier.weight(1f),
                        )
                        ToolbarActionButton(
                            icon = Icons.AutoMirrored.Filled.NavigateNext,
                            label = "Next",
                            enabled = index < total,
                            onClick = onNextObject,
                            modifier = Modifier.weight(1f),
                        )
                        ToolbarActionButton(
                            icon = Icons.Default.Tune,
                            label = "Options",
                            onClick = onOpenWizardOptions,
                            modifier = Modifier.weight(1f),
                        )
                        ToolbarCancelButton(onExitGuiding)
                    } else {
                        ToolbarActionButton(icon = Icons.Default.Search, label = "Search", onClick = onOpenSearch)
                        // Independent of the phone's own alignment state -- a connected mount's
                        // GOTO needs no phone reference at all, so this is reachable even from the
                        // "Not calibrated" wall below. Nothing here corrects the phone's own
                        // alignment either way: a camera setup re-anchors itself in the background
                        // (see onAutoPlateSolveActive) and a sensors-only one is re-aligned through
                        // the wizard, in the menu.
                        ToolbarActionButton(
                            icon = Icons.Default.SettingsInputAntenna,
                            label = "Telescope",
                            onClick = { showTelescopeSheet = true },
                            containerColor = if (menu.isTelescopeConnected) MaterialTheme.colorScheme.primaryContainer else null,
                            contentColor = if (menu.isTelescopeConnected) MaterialTheme.colorScheme.onPrimaryContainer else LocalContentColor.current,
                        )
                        ToolbarCancelButton(onExitGuiding)
                    }
                }
            }
        },
    ) { padding ->
        if (currentPointing == null || phoneNeedsAlignment) {
            NotReadyContent(menu.onOpenAlignment, Modifier.padding(padding))
            return@Scaffold
        }

        val guidance = GuidanceCalculator.compute(currentPointing!!, targetDirection, onTargetToleranceDegrees)

        val haptic = LocalHapticFeedback.current
        var wasOnTarget by remember { mutableStateOf(false) }
        LaunchedEffect(guidance.isOnTarget) {
            if (guidance.isOnTarget && !wasOnTarget) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            wasOnTarget = guidance.isOnTarget
        }

        // A single Box -- map plus every overlay on top of it -- rather than a Column with the
        // status text as a sibling above the map: matching Search/Alignment's structure this way
        // (not just their padding) is what keeps MapFollowZoomControls at the same distance from
        // the screen border on every screen that has it, since the map now starts flush at the top
        // of the content area on all of them rather than being pushed down by a sibling here.
        Box(Modifier.padding(padding).fillMaxSize()) {
            SkyMap(
                directions = snapshot.directions,
                viewport = displayedViewport,
                onViewportChange = { mapViewport = it },
                onManualInteraction = { followMode = MapFollowMode.NONE },
                constellationLines = snapshot.constellationLines,
                milkyWayCells = snapshot.milkyWayCells,
                milkyWayGridStepDegrees = snapshot.milkyWayGridStepDegrees,
                northOffsetDirections = snapshot.northOffsetDirections,
                showObjectPhotos = showObjectPhotos,
                dimBelowHorizon = dimBelowHorizon,
                milkyWayBrightness = milkyWayBrightness,
                guidancePath = SkyMapGuidancePath(
                    start = currentPointing!!,
                    end = targetDirection,
                    color = GuidancePathAmber,
                ),
                markers = listOfNotNull(
                    SkyMapMarker(direction = targetDirection, color = MaterialTheme.colorScheme.primary, label = target.displayName),
                    SkyMapMarker(
                        direction = currentPointing!!,
                        color = if (guidance.isOnTarget) OnTargetGreen else MaterialTheme.colorScheme.secondary,
                        label = "Phone",
                        labelAbove = true,
                    ),
                    telescopeDirection?.let(SkyMapMarker::telescope),
                ),
                onSelect = if (wizardProgress == null && !telescopeSlewing) onSelectTarget else null,
                modifier = Modifier.fillMaxSize(),
            )
            MapFollowZoomControls(
                followMode = followMode,
                hasTelescope = false,
                // Turning follow off specifically (not on) first commits displayedViewport --
                // wherever the map is actually showing right now -- into mapViewport. Without this,
                // mapViewport is still sitting wherever it was before follow last turned on (see its
                // own doc comment on why display and storage are split), so switching off would
                // otherwise snap the map back to that stale spot instead of leaving it where it
                // visibly was.
                onFollowModeChange = { newMode ->
                    if (newMode == MapFollowMode.NONE) mapViewport = displayedViewport
                    followMode = newMode
                },
                onZoomIn = { mapViewport = mapViewport.zoomedBy(MAP_ZOOM_STEP_FACTOR) },
                onZoomOut = { mapViewport = mapViewport.zoomedBy(1f / MAP_ZOOM_STEP_FACTOR) },
                onOpenFilter = { showFilterSheet = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
            )

            ReferenceStatusSection(
                alignmentStatus = menu.alignmentStatus,
                // End padding clears MapFollowZoomControls sharing this corner of the map --
                // same reservation MapScreen's own calibration banner uses.
                modifier = Modifier.align(Alignment.TopStart)
                    .padding(top = 8.dp, start = 8.dp, end = 64.dp)
                    .mapOverlayScrim()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )

            // The separation readout sits directly above the delta bars, both pinned to the
            // bottom edge, so the arrow-angle text stays with the numbers it summarizes instead
            // of splitting across opposite ends of the map.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                if (menu.isTelescopeConnected) {
                    TelescopeActionRow(
                        isSlewing = telescopeSlewing,
                        onGoto = performGoto,
                        onAbort = performAbort,
                        onOpenControls = { showTelescopeControls = true },
                        onMoveHome = { showHomeConfirmation = true },
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 8.dp)
                        .mapOverlayScrim()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    Text(
                        "${formatDegrees(guidance.separationDegrees)}° away",
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = ANGLE_TEXT_SIZE),
                    )
                    if (guidance.isOnTarget) {
                        Text("On target", style = MaterialTheme.typography.labelLarge)
                    }
                }

                Column(
                    Modifier.fillMaxWidth()
                        .mapOverlayScrim(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .padding(16.dp),
                ) {
                    DeltaBar("ALT", guidance.altitudeDeltaDegrees, Modifier.padding(bottom = 12.dp))
                    DeltaBar("AZ", guidance.crossTrackDeltaDegrees)
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
                TextButton(onClick = { showHomeConfirmation = false; scope.launch { onMoveHome() } }) { Text("Send home") }
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
            // Always enabled: Guidance always has a fixed target (see this screen's own `target`
            // param), unlike the Map screen's sheet where nothing may be selected yet.
            gotoEnabled = true,
            onGoto = performGoto,
            onAbortSlew = performAbort,
            slewError = slewError,
            onMoveHome = { showHomeConfirmation = true },
            onDisconnect = { scope.launch { onDisconnectTelescope() } },
            slewRatePreset = slewRatePreset,
            onSlewRatePresetChange = { preset -> scope.launch { onSlewRatePresetChange(preset) } },
            trackingEnabled = trackingEnabled,
            // Applied optimistically only once the mount has accepted: a refused enable (OnStep
            // will not start tracking while parked) must leave the toggle showing the mount's real
            // state, not the state the user asked for.
            onTrackingEnabledChange = { desired ->
                scope.launch {
                    if (onSetTracking(desired)) {
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

    if (showTelescopeControls) {
        ModalBottomSheet(onDismissRequest = { showTelescopeControls = false }) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                TelescopeControlPad(
                    moveRate = moveRate,
                    onMoveRateChange = { moveRate = it },
                    onPressDirection = { direction ->
                        pressJobs[direction] = scope.launch { onPressDirection(direction) }
                    },
                    onReleaseDirection = { direction ->
                        scope.launch {
                            pressJobs.remove(direction)?.join()
                            onReleaseDirection(direction)
                        }
                    },
                    onStopAllMotion = onStopAllMotion,
                )
            }
        }
    }
}

/** The floating telescope control cluster, styled like [MapFollowZoomControls] but horizontal --
 *  shown above the separation readout only while a mount is connected. [isSlewing] toggles the
 *  first icon between Goto and Abort (a play/stop pair) rather than showing both permanently,
 *  since the two are never both valid at once; it's the same locally-tracked "is a commanded GOTO
 *  still in flight" state the toolbar's Telescope sheet uses; see `telescopeSlewing`'s own doc for
 *  why that's the mount's own reported position and not the phone's guidance. */
@Composable
private fun TelescopeActionRow(
    isSlewing: Boolean,
    onGoto: () -> Unit,
    onAbort: () -> Unit,
    onOpenControls: () -> Unit,
    onMoveHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.mapOverlayScrim().padding(4.dp)) {
        IconButton(onClick = if (isSlewing) onAbort else onGoto) {
            Icon(
                if (isSlewing) Icons.Default.Stop else Icons.Default.GpsFixed,
                contentDescription = if (isSlewing) "Abort slew" else "Goto",
            )
        }
        IconButton(onClick = onOpenControls) {
            Icon(Icons.Default.Gamepad, contentDescription = "Manual controls")
        }
        IconButton(onClick = onMoveHome) {
            Icon(Icons.Default.Home, contentDescription = "Send telescope home")
        }
    }
}

/** How the phone's own alignment stands. Renders nothing once a real fit is in effect: only the
 *  compass fallback warrants a permanent overlay saying the whole solution is provisional -- and
 *  even then, [AlignmentStatus.AWAITING_FIRST_PLATE_SOLVE] gets different wording from
 *  [AlignmentStatus.NOT_CALIBRATED], since only one of them actually needs the user to do
 *  anything. */
@Composable
private fun ReferenceStatusSection(
    alignmentStatus: AlignmentStatus,
    modifier: Modifier = Modifier,
) {
    // Not fillMaxWidth: this sits at TopStart alongside MapFollowZoomControls at TopEnd (see the
    // call site's own end-padding reservation), and a full-width scrim would stretch across that
    // corner and draw over it -- ReferenceStatusSection is composed after it, so on top.
    when (alignmentStatus) {
        AlignmentStatus.CALIBRATED -> {}
        AlignmentStatus.NOT_CALIBRATED -> Column(modifier) { CompassModeText(awaitingPlateSolve = false) }
        AlignmentStatus.AWAITING_FIRST_PLATE_SOLVE -> Column(modifier) { CompassModeText(awaitingPlateSolve = true) }
    }
}

/** No error figure quoted: the compass fallback's yaw error varies wildly with how much steel is
 *  near the phone, and it corrects no mounting offset at all, so any number would read as a bound
 *  it cannot honour. See [com.astrocompass.alignment.CompassAlignment].
 *
 *  [awaitingPlateSolve] swaps the second line: a camera setup with no live fix yet is already
 *  calibrated and just waiting on its background solver (see
 *  [com.astrocompass.guiding.AutoPlateSolveRefiner]), so telling it to "calibrate for real
 *  accuracy" would send the user to redo a setup they'd already finished. */
@Composable
private fun CompassModeText(awaitingPlateSolve: Boolean) {
    Column {
        Text("Rough — compass only", color = WarningAmber, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (awaitingPlateSolve) {
                "Camera calibrated — hold the scope still for the first plate solve."
            } else {
                "Calibrate for real accuracy."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun NotReadyContent(onOpenAlignment: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Not calibrated", style = MaterialTheme.typography.titleLarge)
        Text(
            "Calibrate the phone before it can point you toward anything.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Button(onClick = onOpenAlignment) { Text("Calibrate now") }
        }
    }
}

private fun formatDegrees(value: Double): String = (kotlin.math.round(value * 10) / 10).toString()
