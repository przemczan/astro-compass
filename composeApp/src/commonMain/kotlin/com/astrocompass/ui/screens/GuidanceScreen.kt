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
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import com.astrocompass.guiding.GuidanceCalculator
import com.astrocompass.guiding.PointingOrigin
import com.astrocompass.guiding.ReferenceOrigin
import com.astrocompass.guiding.SkyPointingSource
import com.astrocompass.guiding.currentHorizontal
import com.astrocompass.location.ObserverLocation
import com.astrocompass.telescope.SlewOutcome
import com.astrocompass.telescope.SlewRatePreset
import com.astrocompass.ui.components.AppBottomBar
import com.astrocompass.ui.components.AppMenuActions
import com.astrocompass.ui.components.DeltaBar
import com.astrocompass.ui.components.MAP_ZOOM_STEP_FACTOR
import com.astrocompass.ui.components.MapFilterSheet
import com.astrocompass.ui.components.MapFollowZoomControls
import com.astrocompass.ui.components.ToolbarCancelButton
import com.astrocompass.ui.components.mapOverlayScrim
import com.astrocompass.ui.components.SkyMap
import com.astrocompass.ui.components.SkyMapGuidancePath
import com.astrocompass.ui.components.SkyMapMarker
import com.astrocompass.ui.components.TelescopeOptionsSheet
import com.astrocompass.ui.components.ToolbarActionButton
import com.astrocompass.ui.components.rememberSkyMapSnapshot
import com.astrocompass.ui.skymap.SkyMapViewport
import com.astrocompass.ui.theme.GuidancePathAmber
import com.astrocompass.ui.theme.OnTargetGreen
import com.astrocompass.ui.theme.TelescopeBlue
import com.astrocompass.ui.theme.WarningAmber
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
     *  [com.astrocompass.AppContainer.telescopeSkyDirection]. In Telescope mode this is the same
     *  direction [pointingSource] serves, and the map draws one marker for both. */
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
    // Only ever called while pointingSource.origin is TELESCOPE (see the bottom toolbar) -- a
    // connected mount's own reported position is what "on target" means here, same target the
    // arrow already guides toward.
    onGoto: suspend () -> SlewOutcome,
    onAbortSlew: suspend () -> Unit,
    slewRatePreset: SlewRatePreset,
    onSlewRatePresetChange: suspend (SlewRatePreset) -> Unit,
    onReadTracking: suspend () -> Boolean?,
    onSetTracking: suspend (Boolean) -> Boolean,
    showObjectPhotos: Boolean,
    dimBelowHorizon: Boolean,
    mapObjectFilter: MapObjectFilter,
    onMapObjectFilterChange: (MapObjectFilter) -> Unit,
    // Night Wizard mode: non-null wizardProgress swaps the header/toolbar to walk through a fixed
    // object list instead of the single-target Search/Exit (or GOTO/Abort/Options) layout. `first` is the 1-based current index, `second` the total count.
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
    // mid-slew (see onSelectTarget's doc). Cleared by Abort or once the arrow reports on-target,
    // the same signal the haptic below already keys off; keyed on target's identity so switching
    // targets (including the wizard's Prev/Next) starts each one clean.
    var telescopeSlewing by remember(target) { mutableStateOf(false) }
    var mapViewport by remember { mutableStateOf(SkyMapViewport.DEFAULT) }
    var followPointing by remember { mutableStateOf(true) }
    // Deriving this directly during composition, rather than centering mapViewport itself via a
    // LaunchedEffect keyed on currentPointing, matters: that LaunchedEffect's state write landed
    // one recomposition *after* the currentPointing update that triggered it, so the current-
    // position marker (and the guidance path, projected under the same viewport) would render one
    // frame off-center before the camera caught up -- visible as a small jump on every sensor
    // sample while panning/tilting the phone. This has no such gap: displayedViewport is exactly
    // in step with currentPointing on every recomposition, sensor-driven or not.
    val displayedViewport = if (followPointing && currentPointing != null) {
        val horizontal = HorizontalCoordinates.fromEnu(currentPointing!!)
        mapViewport.copy(centerAzimuth = horizontal.azimuth, centerAltitude = horizontal.altitude)
    } else {
        mapViewport
    }

    // Tracking is read from the mount each time the options sheet opens rather than polled --
    // nothing else in the app reacts to it, so a second periodic command alongside the position
    // poll would buy nothing. Null means "not answered yet", which is what the sheet renders as a
    // spinner instead of an assumed state.
    var showFilterSheet by remember { mutableStateOf(false) }
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
                        // Nothing here corrects the phone's own alignment: a camera setup
                        // re-anchors itself in the background (see onAutoPlateSolveActive) and a
                        // sensors-only one is re-aligned through the wizard, in the menu.
                        if (showGuidanceActions) {
                            when (pointingOrigin) {
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

                                PointingOrigin.PHONE_SENSORS -> Unit
                            }
                        }
                        ToolbarCancelButton(onExitGuiding)
                    }
                }
            }
        },
    ) { padding ->
        if (currentPointing == null || phoneNeedsAlignment) {
            NotReadyContent(pointingOrigin, menu.onOpenAlignment, Modifier.padding(padding))
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
                onManualInteraction = { followPointing = false },
                constellationLines = snapshot.constellationLines,
                northOffsetDirections = snapshot.northOffsetDirections,
                showObjectPhotos = showObjectPhotos,
                dimBelowHorizon = dimBelowHorizon,
                // In Telescope mode the current-pointing marker already sits exactly where the
                // mount reports, so it takes the blue itself rather than a second marker being
                // stacked on the same spot -- on-target green still wins over both.
                guidancePath = SkyMapGuidancePath(
                    start = currentPointing!!,
                    end = targetDirection,
                    color = GuidancePathAmber,
                ),
                markers = listOfNotNull(
                    SkyMapMarker(direction = targetDirection, color = MaterialTheme.colorScheme.primary, label = target.displayName),
                    SkyMapMarker(
                        direction = currentPointing!!,
                        color = when {
                            guidance.isOnTarget -> OnTargetGreen
                            pointingOrigin == PointingOrigin.TELESCOPE -> TelescopeBlue
                            else -> MaterialTheme.colorScheme.secondary
                        },
                        // In Telescope mode this marker *is* the telescope's own position (see the
                        // dedup above), not the phone's -- see the same labeling in MapScreen.
                        label = if (pointingOrigin == PointingOrigin.TELESCOPE) "Telescope" else "Phone",
                        labelAbove = true,
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
                onOpenFilter = { showFilterSheet = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
            )

            ReferenceStatusSection(
                pointingOrigin = pointingOrigin,
                reference = activeReference,
                modifier = Modifier.align(Alignment.TopStart)
                    .padding(8.dp)
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

/** How the current pointing solution was obtained. [reference] describes the *phone's* alignment
 *  and is only consulted under [PointingOrigin.PHONE_SENSORS] -- a connected mount needs none of
 *  that, see [PointingOrigin]'s doc. Renders nothing once a real fit is in effect: only the compass
 *  fallback warrants a permanent overlay saying the whole solution is provisional. */
@Composable
private fun ReferenceStatusSection(
    pointingOrigin: PointingOrigin,
    reference: AbsoluteReferenceState?,
    modifier: Modifier = Modifier,
) {
    when (pointingOrigin) {
        PointingOrigin.TELESCOPE -> Column(modifier.fillMaxWidth()) { TelescopeStatusText() }
        PointingOrigin.PHONE_SENSORS -> when (reference?.origin) {
            ReferenceOrigin.STAR_ALIGNMENT -> {}
            ReferenceOrigin.COMPASS, null -> Column(modifier.fillMaxWidth()) { CompassModeText() }
        }
    }
}

@Composable
private fun TelescopeStatusText() {
    Text("Telescope connected", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

/** No error figure quoted: the compass fallback's yaw error varies wildly with how much steel is
 *  near the phone, and it corrects no mounting offset at all, so any number would read as a bound
 *  it cannot honour. See [com.astrocompass.alignment.CompassAlignment]. */
@Composable
private fun CompassModeText() {
    Column {
        Text("Rough — compass only", color = WarningAmber, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Calibrate for real accuracy.",
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
    }
}

private fun formatDegrees(value: Double): String = (kotlin.math.round(value * 10) / 10).toString()
