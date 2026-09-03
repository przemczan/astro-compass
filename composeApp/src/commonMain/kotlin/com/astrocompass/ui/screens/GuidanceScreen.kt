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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.coords.HorizontalCoordinates
import com.astrocompass.astro.time.currentEpochMillis
import com.astrocompass.catalog.CatalogRepository
import com.astrocompass.catalog.MapObjectFilter
import com.astrocompass.catalog.SkyObject
import com.astrocompass.guiding.AbsoluteReferenceState
import com.astrocompass.guiding.GuidanceCalculator
import com.astrocompass.guiding.GuidingMode
import com.astrocompass.guiding.PlateSolveAttempt
import com.astrocompass.guiding.PointingOrigin
import com.astrocompass.guiding.ReferenceOrigin
import com.astrocompass.guiding.SkyPointingSource
import com.astrocompass.guiding.currentHorizontal
import com.astrocompass.location.ObserverLocation
import com.astrocompass.telescope.SlewOutcome
import com.astrocompass.telescope.SlewRatePreset
import com.astrocompass.ui.components.ArrowIndicator
import com.astrocompass.ui.components.DeltaBar
import com.astrocompass.ui.components.GuidingModeButton
import com.astrocompass.ui.components.MAP_ZOOM_STEP_FACTOR
import com.astrocompass.ui.components.MapFollowZoomControls
import com.astrocompass.ui.components.mapOverlayScrim
import com.astrocompass.ui.components.SkyMap
import com.astrocompass.ui.components.SkyMapMarker
import com.astrocompass.ui.components.TelescopeOptionsSheet
import com.astrocompass.ui.components.ToolbarActionButton
import com.astrocompass.ui.components.rememberSkyMapSnapshot
import com.astrocompass.ui.skymap.SkyMapViewport
import com.astrocompass.ui.theme.OnTargetGreen
import com.astrocompass.ui.theme.TelescopeBlue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val UPDATE_INTERVAL_MS = 50L
private const val CATALOG_REFRESH_INTERVAL_MS = 5_000L
private const val SYNC_AGE_AMBER_SECONDS = 5 * 60L
private const val SYNC_AGE_RED_SECONDS = 15 * 60L
private const val STABILIZATION_MILLIS = 2_000L

// ~25% smaller than the previous 110.dp / 24.sp (headlineSmall) so the readout takes up less of
// the map without the target marker underneath it staying hidden as long.
private val ARROW_SIZE = 80.dp
private val ANGLE_TEXT_SIZE = 18.sp

private val WarningAmber = Color(0xFFFFA000)

/** Local UI state for the "Platesolve" flow -- not reachable until [GuidanceScreen] has already
 *  gated on being aligned, since [com.astrocompass.AppContainer.attemptPlateSolve] requires an
 *  existing pointing direction to seed the search from. */
private sealed interface PlateSolveUiState {
    data object Stabilizing : PlateSolveUiState
    data object Solving : PlateSolveUiState
    data class Result(val attempt: PlateSolveAttempt) : PlateSolveUiState
    data object Failed : PlateSolveUiState
}

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
     *  [com.astrocompass.AppContainer.telescopeSkyDirection]. In Telescope mode this is the same
     *  direction [pointingSource] serves, and the map draws one marker for both. */
    telescopeDirection: Vector3?,
    absoluteReference: StateFlow<AbsoluteReferenceState?>,
    location: ObserverLocation,
    catalogRepository: CatalogRepository,
    onTargetToleranceDegrees: Double,
    onSyncOnThisObject: () -> Unit,
    onPlateSolve: suspend () -> PlateSolveAttempt?,
    onApplyPlateSolve: (PlateSolveAttempt) -> Unit,
    onOpenAlignment: () -> Unit,
    onOpenSettings: () -> Unit,
    onExitGuiding: () -> Unit,
    // Only ever called while pointingSource.origin is TELESCOPE (see the bottom toolbar) -- a
    // connected mount's own reported position is what "on target" means here, same target the
    // arrow already guides toward.
    onGoto: suspend () -> SlewOutcome,
    onAbortSlew: suspend () -> Unit,
    // Which source drives the arrow. pointingSource already follows this (see
    // SelectablePointingSource), so nothing below branches on it -- it is here only to label the
    // mode button and to tell the picker which entry is current.
    guidingMode: GuidingMode,
    onGuidingModeChange: (GuidingMode) -> Unit,
    isTelescopeConnected: Boolean,
    slewRatePreset: SlewRatePreset,
    onSlewRatePresetChange: suspend (SlewRatePreset) -> Unit,
    onReadTracking: suspend () -> Boolean?,
    onSetTracking: suspend (Boolean) -> Boolean,
    showObjectPhotos: Boolean,
    mapObjectFilter: MapObjectFilter,
    // Night Wizard mode: non-null wizardProgress swaps the header/toolbar to walk through a fixed
    // object list instead of today's single-target Platesolve/Sync/Align/Exit (or GOTO/Abort)
    // layout. `first` is the 1-based current index, `second` the total count.
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
    val pointingReady by pointingSource.isReady.collectAsState()
    val currentPointing by pointingSource.currentSkyDirection.collectAsState()
    val pointingOrigin by pointingSource.origin.collectAsState()
    val reference by absoluteReference.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var now by remember { mutableStateOf(currentEpochMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(UPDATE_INTERVAL_MS)
            now = currentEpochMillis()
        }
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
    // mid-slew (see onSelectTarget's doc). Cleared by Abort or once the arrow reports on-target,
    // the same signal the haptic below already keys off; keyed on target's identity so switching
    // targets (including the wizard's Prev/Next) starts each one clean.
    var telescopeSlewing by remember(target) { mutableStateOf(false) }
    var mapViewport by remember { mutableStateOf(SkyMapViewport.DEFAULT) }
    var followPointing by remember { mutableStateOf(true) }
    LaunchedEffect(currentPointing, followPointing) {
        val pointing = currentPointing
        if (followPointing && pointing != null) {
            val horizontal = HorizontalCoordinates.fromEnu(pointing)
            mapViewport = mapViewport.copy(centerAzimuth = horizontal.azimuth, centerAltitude = horizontal.altitude)
        }
    }

    // The run token is deliberately separate from the displayed state: a LaunchedEffect must never
    // reassign its own key, or Compose cancels the running effect the moment it advances a phase --
    // taking the in-flight capture, and its timeout, down with it. Only starting or cancelling a
    // run touches the key; phase changes touch plateSolveState alone.
    var plateSolveRunId by remember { mutableStateOf<Int?>(null) }
    var plateSolveState by remember { mutableStateOf<PlateSolveUiState?>(null) }
    val closePlateSolve = {
        plateSolveRunId = null
        plateSolveState = null
    }
    LaunchedEffect(plateSolveRunId) {
        if (plateSolveRunId == null) return@LaunchedEffect
        plateSolveState = PlateSolveUiState.Stabilizing
        delay(STABILIZATION_MILLIS)
        plateSolveState = PlateSolveUiState.Solving
        val attempt = onPlateSolve()
        plateSolveState = if (attempt != null) PlateSolveUiState.Result(attempt) else PlateSolveUiState.Failed
    }

    // Tracking is read from the mount each time the options sheet opens rather than polled --
    // nothing else in the app reacts to it, so a second periodic command alongside the position
    // poll would buy nothing. Null means "not answered yet", which is what the sheet renders as a
    // spinner instead of an assumed state.
    var showTelescopeOptions by remember { mutableStateOf(false) }
    var trackingEnabled by remember { mutableStateOf<Boolean?>(null) }
    var trackingError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showTelescopeOptions) {
        if (!showTelescopeOptions) return@LaunchedEffect
        trackingEnabled = null
        trackingError = null
        trackingEnabled = onReadTracking()
    }

    // Hoisted above the Scaffold since both the content (the not-ready gate) and the bottom
    // toolbar (which hides its actions until there's something to act on) need it. A connected
    // telescope's own reported position is a complete answer with no phone alignment involved --
    // see PointingOrigin's doc -- so only the PHONE_SENSORS branch needs a star/compass reference
    // at all; pointingReady already covers telescope readiness (see TelescopePointingSource).
    val activeReference = reference
    val phoneNeedsAlignment = pointingOrigin == PointingOrigin.PHONE_SENSORS && activeReference == null
    val showGuidanceActions = pointingReady && currentPointing != null && !phoneNeedsAlignment

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(target.displayName) },
                navigationIcon = {
                    if (wizardProgress != null) {
                        IconButton(onClick = onBackToObjectList) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the object list")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
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
                BottomAppBar {
                    if (wizardProgress != null) {
                        val (index, total) = wizardProgress
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                            VerticalDivider(Modifier.height(32.dp).padding(horizontal = 4.dp))
                            ToolbarActionButton(icon = Icons.Default.Close, label = "Exit", onClick = onExitGuiding)
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GuidingModeButton(
                                mode = guidingMode,
                                telescopeConnected = isTelescopeConnected,
                                onModeChange = onGuidingModeChange,
                            )
                            VerticalDivider(Modifier.height(32.dp).padding(horizontal = 4.dp))
                            Row(Modifier.weight(1f)) {
                                if (showGuidanceActions) {
                                    when (pointingOrigin) {
                                        // Platesolve/Sync/Align all correct the *phone's* alignment
                                        // model -- meaningless while a mount drives guidance, so
                                        // GOTO/Abort/Options take their place instead.
                                        PointingOrigin.TELESCOPE -> {
                                            ToolbarActionButton(
                                                icon = Icons.Default.GpsFixed,
                                                label = "GOTO",
                                                onClick = {
                                                    telescopeSlewing = true
                                                    scope.launch {
                                                        when (val outcome = onGoto()) {
                                                            is SlewOutcome.Rejected -> {
                                                                telescopeSlewing = false
                                                                snackbarHostState.showSnackbar(outcome.reason)
                                                            }
                                                            SlewOutcome.NoConnection -> {
                                                                telescopeSlewing = false
                                                                snackbarHostState.showSnackbar("Telescope not connected")
                                                            }
                                                            SlewOutcome.Started -> {}
                                                        }
                                                    }
                                                },
                                            )
                                            ToolbarActionButton(
                                                icon = Icons.Default.Stop,
                                                label = "Abort",
                                                onClick = { scope.launch { onAbortSlew() }; telescopeSlewing = false },
                                            )
                                            ToolbarActionButton(
                                                icon = Icons.Default.Tune,
                                                label = "Options",
                                                onClick = { showTelescopeOptions = true },
                                            )
                                        }

                                        PointingOrigin.PHONE_SENSORS -> {
                                            ToolbarActionButton(
                                                icon = Icons.Default.CameraAlt,
                                                label = "Platesolve",
                                                onClick = { plateSolveRunId = (plateSolveRunId ?: 0) + 1 },
                                            )
                                            when (activeReference?.origin) {
                                                ReferenceOrigin.STAR_ALIGNMENT ->
                                                    ToolbarActionButton(icon = Icons.Default.Sync, label = "Sync Az", onClick = onSyncOnThisObject)
                                                ReferenceOrigin.COMPASS, null ->
                                                    ToolbarActionButton(icon = Icons.Default.Explore, label = "Align", onClick = onOpenAlignment)
                                            }
                                        }
                                    }
                                }
                            }
                            if (showGuidanceActions) {
                                VerticalDivider(Modifier.height(32.dp).padding(horizontal = 4.dp))
                            }
                            ToolbarActionButton(icon = Icons.Default.Close, label = "Exit", onClick = onExitGuiding)
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (currentPointing == null || phoneNeedsAlignment) {
            NotReadyContent(pointingOrigin, onOpenAlignment, Modifier.padding(padding))
            return@Scaffold
        }

        val targetDirection = target.currentHorizontal(location, now).toEnu()
        val guidance = GuidanceCalculator.compute(currentPointing!!, targetDirection, onTargetToleranceDegrees)

        val haptic = LocalHapticFeedback.current
        var wasOnTarget by remember { mutableStateOf(false) }
        LaunchedEffect(guidance.isOnTarget) {
            if (guidance.isOnTarget) {
                if (!wasOnTarget) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                telescopeSlewing = false
            }
            wasOnTarget = guidance.isOnTarget
        }

        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ReferenceStatusSection(pointingOrigin = pointingOrigin, reference = activeReference, nowEpochMillis = now)

            // The map fills essentially the whole remaining screen -- same as Search/Alignment's
            // map -- with the arrow/separation/delta-bar readout as overlays on top of it rather
            // than separate sections competing with it for vertical space.
            Box(Modifier.fillMaxWidth().weight(1f).padding(top = 16.dp)) {
                SkyMap(
                    directions = snapshot.directions,
                    viewport = mapViewport,
                    onViewportChange = { mapViewport = it },
                    onManualInteraction = { followPointing = false },
                    constellationLines = snapshot.constellationLines,
                    northOffsetDirections = snapshot.northOffsetDirections,
                    showObjectPhotos = showObjectPhotos,
                    // In Telescope mode the current-pointing marker already sits exactly where the
                    // mount reports, so it takes the blue itself rather than a second marker being
                    // stacked on the same spot -- on-target green still wins over both.
                    markers = listOfNotNull(
                        SkyMapMarker(direction = targetDirection, color = MaterialTheme.colorScheme.primary, label = target.displayName),
                        SkyMapMarker(
                            direction = currentPointing!!,
                            color = when {
                                guidance.isOnTarget -> OnTargetGreen
                                pointingOrigin == PointingOrigin.TELESCOPE -> TelescopeBlue
                                else -> MaterialTheme.colorScheme.secondary
                            },
                        ),
                        telescopeDirection
                            ?.takeIf { pointingOrigin != PointingOrigin.TELESCOPE }
                            ?.let(SkyMapMarker::telescope),
                    ),
                    onSelect = if (wizardProgress == null && !telescopeSlewing) onSelectTarget else null,
                    modifier = Modifier.fillMaxSize(),
                )
                MapFollowZoomControls(
                    isFollowing = followPointing,
                    onEnableFollow = { followPointing = true },
                    onZoomIn = { mapViewport = mapViewport.zoomedBy(MAP_ZOOM_STEP_FACTOR) },
                    onZoomOut = { mapViewport = mapViewport.zoomedBy(1f / MAP_ZOOM_STEP_FACTOR) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
                )

                // The arrow/separation readout sits at the top of the map, not the center, so it
                // never covers the target marker. The delta bars stay pinned to the opposite edge,
                // at the bottom.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .mapOverlayScrim()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    ArrowIndicator(guidance.arrowAngleDegrees, guidance.isOnTarget, arrowSize = ARROW_SIZE)
                    Text(
                        "${formatDegrees(guidance.separationDegrees)}° away",
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = ANGLE_TEXT_SIZE),
                    )
                    if (guidance.isOnTarget) {
                        Text("On target", style = MaterialTheme.typography.labelLarge)
                    }
                }

                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .mapOverlayScrim(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .padding(16.dp),
                ) {
                    DeltaBar("ALT", guidance.altitudeDeltaDegrees, Modifier.padding(bottom = 12.dp))
                    DeltaBar("AZ", guidance.crossTrackDeltaDegrees)
                }
            }
        }
    }

    val state = plateSolveState
    val origin = reference?.origin
    if (state != null && origin != null) {
        PlateSolveDialog(
            state = state,
            origin = origin,
            onApply = { attempt -> onApplyPlateSolve(attempt); closePlateSolve() },
            onDismiss = closePlateSolve,
        )
    }

    if (showTelescopeOptions) {
        TelescopeOptionsSheet(
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
            onDismiss = { showTelescopeOptions = false },
        )
    }
}

/** How the current pointing solution was obtained -- the action that improves it (GOTO/Abort for
 *  a mount, Sync for a star fit, Align for the compass fallback) lives in the bottom toolbar, see
 *  [GuidanceScreen]'s `showGuidanceActions` block. [reference] describes the *phone's* alignment
 *  and is only consulted under [PointingOrigin.PHONE_SENSORS] -- a connected mount needs none of
 *  that, see [PointingOrigin]'s doc. */
@Composable
private fun ReferenceStatusSection(pointingOrigin: PointingOrigin, reference: AbsoluteReferenceState?, nowEpochMillis: Long) {
    Column(Modifier.fillMaxWidth()) {
        when (pointingOrigin) {
            PointingOrigin.TELESCOPE -> TelescopeStatusText()
            PointingOrigin.PHONE_SENSORS -> when (reference?.origin) {
                ReferenceOrigin.STAR_ALIGNMENT -> SyncAgeText(reference.establishedAtEpochMillis, nowEpochMillis)
                ReferenceOrigin.COMPASS, null -> CompassModeText()
            }
        }
    }
}

@Composable
private fun TelescopeStatusText() {
    Text("Telescope connected", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SyncAgeText(syncedAtEpochMillis: Long, nowEpochMillis: Long) {
    val ageSeconds = (nowEpochMillis - syncedAtEpochMillis) / 1000
    val color = when {
        ageSeconds > SYNC_AGE_RED_SECONDS -> MaterialTheme.colorScheme.error
        ageSeconds > SYNC_AGE_AMBER_SECONDS -> WarningAmber
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text("Synced ${formatAge(ageSeconds)} ago", color = color, style = MaterialTheme.typography.bodyMedium)
}

/** No error figure quoted: the compass fallback's yaw error varies wildly with how much steel is
 *  near the phone, and it corrects no mounting offset at all, so any number would read as a bound
 *  it cannot honour. See [com.astrocompass.alignment.CompassAlignment]. */
@Composable
private fun CompassModeText() {
    Column {
        Text("Rough — compass only", color = WarningAmber, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Align on stars for real accuracy.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** [PointingOrigin.TELESCOPE] reaches this only in the brief window between connecting and the
 *  first polled position report -- no action needed there, it resolves itself, unlike
 *  [PointingOrigin.PHONE_SENSORS] genuinely needing the user to align. */
@Composable
private fun NotReadyContent(pointingOrigin: PointingOrigin, onOpenAlignment: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (pointingOrigin) {
            PointingOrigin.TELESCOPE -> {
                Text("Waiting for telescope position...", style = MaterialTheme.typography.titleLarge)
                CircularProgressIndicator(Modifier.padding(top = 24.dp))
            }

            PointingOrigin.PHONE_SENSORS -> {
                Text("Not aligned", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Sync on at least one star before the app can point you toward anything.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Button(onClick = onOpenAlignment) { Text("Align now") }
                }
            }
        }
    }
}

/** Walks through the "Platesolve" flow: a stabilization pause (so the tap that opened this dialog
 *  doesn't blur the photo), then solving, then a result the user must explicitly apply or
 *  discard -- never applied automatically, since a plausible-looking but wrong
 *  [com.astrocompass.guiding.CameraMounting] preset can only be caught by the user noticing the
 *  correction looks too large. */
@Composable
private fun PlateSolveDialog(
    state: PlateSolveUiState,
    origin: ReferenceOrigin,
    onApply: (PlateSolveAttempt) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (state) {
                    is PlateSolveUiState.Stabilizing -> {
                        Text("Hold steady...", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Point the camera at open sky. Capturing shortly.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                        )
                        CircularProgressIndicator(Modifier.padding(bottom = 16.dp))
                        OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    }

                    is PlateSolveUiState.Solving -> {
                        Text("Solving...", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "This can take a few seconds against a dense star field.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                        )
                        CircularProgressIndicator(Modifier.padding(bottom = 16.dp).size(32.dp))
                        OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    }

                    is PlateSolveUiState.Result -> {
                        val attempt = state.attempt
                        Text("Plate solve result", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${attempt.result.matchedStarCount} stars matched, " +
                                "${formatDegrees(attempt.result.rmsResidualDegrees)}° RMS",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            "Correction: ${formatDegrees(attempt.correctionDegrees)}°",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                        // What counts as a suspicious correction depends entirely on what is
                        // being corrected: a star fit only drifts, while the compass fallback
                        // starts out wrong, so the same number means opposite things.
                        Text(
                            when (origin) {
                                ReferenceOrigin.STAR_ALIGNMENT ->
                                    "A plausible drift correction is a few degrees. Tens of " +
                                        "degrees usually means the Camera mounting setting is wrong."

                                ReferenceOrigin.COMPASS ->
                                    "This replaces the rough compass estimate with a real " +
                                        "alignment, so a correction of tens of degrees is normal here."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            OutlinedButton(onClick = onDismiss, modifier = Modifier.padding(end = 12.dp)) { Text("Discard") }
                            Button(onClick = { onApply(attempt) }) { Text("Apply") }
                        }
                    }

                    is PlateSolveUiState.Failed -> {
                        Text("Plate solve failed", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Not enough stars matched. Try open sky, away from clouds or light " +
                                "pollution, and hold the phone steady.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            Button(onClick = onDismiss) { Text("OK") }
                        }
                    }
                }
            }
        }
    }
}

private fun formatAge(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

private fun formatDegrees(value: Double): String = (kotlin.math.round(value * 10) / 10).toString()
