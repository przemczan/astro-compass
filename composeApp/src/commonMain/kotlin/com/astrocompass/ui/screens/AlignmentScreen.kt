@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.time.currentEpochMillis
import com.astrocompass.alignment.AlignmentModel
import com.astrocompass.alignment.AlignmentPoint
import com.astrocompass.alignment.AlignmentResult
import com.astrocompass.alignment.AlignmentSolver
import com.astrocompass.alignment.AlignmentSource
import com.astrocompass.catalog.CatalogRepository
import com.astrocompass.catalog.MapObjectFilter
import com.astrocompass.catalog.SkyObject
import com.astrocompass.catalog.StarObject
import com.astrocompass.guiding.GuidingMode
import com.astrocompass.guiding.currentHorizontal
import com.astrocompass.location.ObserverLocation
import com.astrocompass.telescope.MoveRatePreset
import com.astrocompass.telescope.SlewOutcome
import com.astrocompass.telescope.SyncOutcome
import com.astrocompass.telescope.TelescopeDirection
import com.astrocompass.ui.components.GuidingModeButton
import com.astrocompass.ui.components.MapFilterSheet
import com.astrocompass.ui.components.SkyMap
import com.astrocompass.ui.components.SkyMapMarker
import com.astrocompass.ui.components.TelescopeControlPad
import com.astrocompass.ui.components.ToolbarActionButton
import com.astrocompass.ui.components.mapOverlayScrim
import com.astrocompass.ui.components.rememberSkyMapSnapshot
import com.astrocompass.ui.skymap.SkyMapViewport
import com.astrocompass.ui.theme.OnTargetGreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SUGGESTION_MAGNITUDE_LIMIT = 3.5f
private const val SUGGESTION_MIN_ALTITUDE_DEGREES = 15.0
private const val MIN_SEPARATION_FOR_SUGGESTIONS_DEGREES = 30.0
private const val MAX_SUGGESTIONS = 50

/** How often the Start card re-asks the mount whether it is home. Brisk enough that a "Send home"
 *  slew clears the warning on its own, and alive only while that card is showing. */
private const val HOME_STATUS_POLL_INTERVAL_MILLIS = 2_000L

/** How the list view orders [suggestStars]'s result -- [MAGNITUDE] matches the order the function
 *  already returns (brightest first), so that mode needs no re-sort. */
private enum class StarSortMode(val label: String) { MAGNITUDE("Magnitude"), NAME("Name") }

/**
 * Walks the user through 2-3 star syncs, one star at a time: pick a star, point the telescope at
 * it, confirm. The sky map is the permanent backdrop for all of that -- every step renders as an
 * overlay on top of it rather than replacing it, so the surrounding sky stays available for
 * star-hopping by eye, and tapping a different star while one is already pending simply replaces
 * the pick. The map only ever *picks* a star while a pick could actually go somewhere; otherwise
 * it is an overview and nothing more.
 *
 * [guidingMode] chooses **which instrument the run aligns, never both**:
 *
 * - [GuidingMode.PHONE] fits the phone's own [AlignmentModel] from sensor readings, via
 *   [onCapturePoint] and [AlignmentSolver]. The mount is not touched.
 * - [GuidingMode.TELESCOPE] drives OnStep's own stateful alignment on the mount --
 *   [onBeginMountAlignment] arms it, each confirm feeds it one star through [onSyncTelescope], and
 *   [onSaveMountAlignmentModel] persists the result. No sensor reading is captured and no phone
 *   model is written: with the phone in the user's hand rather than on the telescope, a sensor
 *   direction taken at confirm time describes nothing.
 *
 * The run itself lives in [session], outside this composable, because the top bar's Settings
 * button tears the screen down -- and because re-arming a mount mid-run re-homes it.
 */
@Composable
fun AlignmentScreen(
    session: AlignmentSession,
    catalogRepository: CatalogRepository,
    location: ObserverLocation,
    /** Marked in blue while a connected mount is reporting -- see
     *  [com.astrocompass.AppContainer.telescopeSkyDirection]. */
    telescopeDirection: Vector3?,
    /** Shared with [com.astrocompass.ui.screens.MapScreen] and [com.astrocompass.ui.screens.GuidanceScreen]
     *  -- the same "Filter" button and sheet, the same [MapObjectFilter.maxMagnitude]. Category
     *  toggles are inert here since [suggestions] and the map's own catalog subset are already
     *  narrowed to [StarObject], which [MapObjectFilter.matches] never gates on category. */
    mapObjectFilter: MapObjectFilter,
    onMapObjectFilterChange: (MapObjectFilter) -> Unit,
    onCapturePoint: (target: SkyObject, source: AlignmentSource, nowEpochMillis: Long) -> AlignmentPoint?,
    onSaveModel: (AlignmentModel) -> Unit,
    guidingMode: GuidingMode,
    onGuidingModeChange: (GuidingMode) -> Unit,
    isTelescopeConnected: Boolean,
    /** Slews a connected mount to the pending star, so the user only has to correct the last
     *  degree or so by hand instead of finding it from scratch. */
    onGoto: suspend (target: SkyObject) -> SlewOutcome,
    /** Arms the mount's own alignment for this many stars. Destructive -- see
     *  [com.astrocompass.telescope.TelescopeConnection.beginAlignment]. */
    onBeginMountAlignment: suspend (starCount: Int) -> Boolean,
    /** Whether the mount says it is at home, or null while unknown -- see
     *  [com.astrocompass.telescope.TelescopeConnection.readAtHome]. */
    onReadAtHome: suspend () -> Boolean?,
    onMoveHome: suspend () -> Unit,
    /** Feeds the mount one alignment star, as of `capturedAtEpochMillis` -- fired on the confirming
     *  tap, the one instant "the telescope is on this star" is true. */
    onSyncTelescope: suspend (target: SkyObject, capturedAtEpochMillis: Long) -> SyncOutcome,
    onSaveMountAlignmentModel: suspend () -> Boolean,
    /** Hand-controller motion for the "Controls" overlay -- press/release and the rate it moves at.
     *  [onStopAllMotion] is deliberately not suspending; see [TelescopeControlPad]. */
    onPressDirection: suspend (TelescopeDirection) -> Unit,
    onReleaseDirection: suspend (TelescopeDirection) -> Unit,
    onMoveRateChange: suspend (MoveRatePreset) -> Unit,
    onStopAllMotion: () -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingTarget by remember { mutableStateOf<StarObject?>(null) }
    var showList by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(StarSortMode.MAGNITUDE) }
    var viewport by remember { mutableStateOf(SkyMapViewport.DEFAULT) }
    // Every mount command is a round trip over a slow serial link; without this a second tap
    // during one would queue a duplicate align point behind it.
    var mountCommandInFlight by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val alignsMount = guidingMode == GuidingMode.TELESCOPE
    // Idempotent, so returning from Settings doesn't wipe the run -- see AlignmentSession.switchTo.
    LaunchedEffect(guidingMode) {
        session.switchTo(guidingMode)
        pendingTarget = null
    }

    // Asking to start over. Deliberately screen-local rather than part of the session: losing it
    // (a trip through Settings) falls back to the run the mount is actually in the middle of, which
    // is the safe direction to fail -- the unsafe one would be offering Start for a mount that is
    // already armed, and session.mountAlignmentActive stays true until a re-arm actually lands.
    var showControls by remember { mutableStateOf(false) }
    var moveRate by remember { mutableStateOf(MoveRatePreset.DEFAULT) }
    // Sent when the pad opens as well as when the rate is picked: the rate lives on the mount, and
    // the pad's own default has never been sent to it, so opening without this would move at
    // whatever the mount was last set to while the label claimed otherwise.
    LaunchedEffect(showControls, moveRate) {
        if (showControls) onMoveRateChange(moveRate)
    }

    // Press and release are separate coroutines, and a quick tap launches them back to back --
    // joining the press before releasing is what stops an inverted pair from leaving the mount
    // slewing with no stop coming. Per-axis, so holding two arrows at once still works.
    val pressJobs = remember { mutableMapOf<TelescopeDirection, Job>() }

    var restartRequested by remember { mutableStateOf(false) }
    val startCardShowing = alignsMount && (!session.mountAlignmentActive || restartRequested)

    // Polled, not read once: "Send home" is fire-and-forget and the slew takes seconds, so a single
    // re-read would always land mid-slew and leave a red warning up that only clears by leaving the
    // screen. Runs only while the Start card is the thing on screen -- the one moment it decides
    // anything -- so this is not a second permanent command stream alongside the position poll.
    var atHome by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(startCardShowing) {
        if (!startCardShowing) {
            atHome = null
            return@LaunchedEffect
        }
        while (true) {
            atHome = onReadAtHome()
            delay(HOME_STATUS_POLL_INTERVAL_MILLIS)
        }
    }

    // Derived, not state written from an effect: the solve is pure and instant, so the last
    // "Confirm sync" completes the alignment on its own instead of parking the user on a
    // "Compute" button. Solving at the final point's capture time rather than "now" keeps the
    // model's timestamp on the same instant the fit actually describes. Telescope runs have no
    // phone-side solve at all -- the model being built lives on the mount.
    val phoneResult = remember(session.points, session.starCount, alignsMount) {
        if (!alignsMount && session.points.size == session.starCount) {
            AlignmentSolver.solve(session.points, session.points.last().capturedAtEpochMillis)
        } else {
            null
        }
    }

    var now by remember { mutableStateOf(currentEpochMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = currentEpochMillis()
        }
    }

    // Deliberately doesn't touch the viewport: a pick shouldn't fight whatever pan/zoom the user
    // already has set up, whether it comes from the map or the list.
    val pickStar: (StarObject) -> Unit = { star ->
        pendingTarget = star
        showList = false
    }

    val catalogLoaded by catalogRepository.isLoaded.collectAsState()
    val syncedNames = List(session.syncedCount) { syncedStarName(session, catalogRepository, it) }
    val syncedDirections = if (alignsMount) {
        session.mountAlignedStars.map { it.currentHorizontal(location, now).toEnu() }
    } else {
        session.points.map { it.skyDirection }
    }
    val suggestions = remember(catalogLoaded, now, session.syncedCount, mapObjectFilter.maxMagnitude) {
        if (!catalogLoaded) {
            emptyList()
        } else {
            suggestStars(catalogRepository, location, now, syncedDirections, mapObjectFilter.maxMagnitude)
        }
    }
    val sortedSuggestions = remember(suggestions, sortMode) {
        when (sortMode) {
            StarSortMode.MAGNITUDE -> suggestions
            StarSortMode.NAME -> suggestions.sortedBy { it.displayName }
        }
    }
    // Deliberately its own (slower) refresh cadence, decoupled from the `now` above -- that one
    // exists for suggestStars and the alignment countdown/age display, which want to be current to
    // the second, not for how often the map itself needs to be re-precessed.
    val snapshot = rememberSkyMapSnapshot(
        catalogRepository, location,
        filterKey = mapObjectFilter,
        catalogFilter = { it is StarObject && mapObjectFilter.matches(it) },
    )
    val syncedMarkers = syncedDirections.mapIndexed { index, direction ->
        SkyMapMarker(direction = direction, color = OnTargetGreen, label = syncedNames.getOrNull(index))
    }
    val pendingMarker = pendingTarget?.let {
        SkyMapMarker(
            direction = it.currentHorizontal(location, now).toEnu(),
            color = MaterialTheme.colorScheme.primary,
            label = it.displayName,
        )
    }

    // Picking is allowed even with a target already pending -- tapping another star just replaces
    // it, so there's no separate "choose a different star" step. With the last star already synced
    // there is nothing a further pick could feed, and a mount run additionally has nothing to pick
    // *into* until its sequence is armed. Gates the list the same way as the map, or the list would
    // just be the other way in.
    val canPickStar = !session.isComplete && (!alignsMount || session.mountAlignmentActive)

    val confirmPhoneSync: (StarObject) -> Unit = { target ->
        onCapturePoint(target, AlignmentSource.MANUAL_SYNC, currentEpochMillis())?.let(session::addPoint)
        pendingTarget = null
    }
    // Only clears the pick once the mount has actually taken the star: a refused point leaves the
    // user exactly where they were, free to re-center and confirm again.
    val confirmMountSync: (StarObject) -> Unit = { target ->
        mountCommandInFlight = true
        scope.launch {
            when (onSyncTelescope(target, currentEpochMillis())) {
                SyncOutcome.Synced -> {
                    session.addMountAlignedStar(target)
                    pendingTarget = null
                }
                SyncOutcome.Rejected -> snackbarHostState.showSnackbar("Mount refused ${target.displayName} as an alignment star")
                SyncOutcome.NoConnection -> snackbarHostState.showSnackbar("Telescope not connected")
            }
            mountCommandInFlight = false
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (alignsMount) "Align telescope" else "Align phone") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GuidingModeButton(
                        mode = guidingMode,
                        telescopeConnected = isTelescopeConnected,
                        onModeChange = onGuidingModeChange,
                    )
                    VerticalDivider(Modifier.height(32.dp).padding(horizontal = 4.dp))
                    Row(Modifier.weight(1f)) {
                        if (alignsMount) {
                            ToolbarActionButton(
                                icon = Icons.Default.GpsFixed,
                                label = "GOTO",
                                enabled = pendingTarget != null,
                                onClick = {
                                    val star = pendingTarget ?: return@ToolbarActionButton
                                    scope.launch {
                                        val message = when (val outcome = onGoto(star)) {
                                            is SlewOutcome.Rejected -> outcome.reason
                                            SlewOutcome.NoConnection -> "Telescope not connected"
                                            SlewOutcome.Started -> "Slewing to ${star.displayName}"
                                        }
                                        snackbarHostState.showSnackbar(message)
                                    }
                                },
                            )
                        }
                        if (alignsMount) {
                            ToolbarActionButton(
                                icon = Icons.Default.Gamepad,
                                label = "Controls",
                                onClick = { showControls = !showControls },
                                containerColor = if (showControls) MaterialTheme.colorScheme.primaryContainer else null,
                                contentColor = if (showControls) MaterialTheme.colorScheme.onPrimaryContainer else LocalContentColor.current,
                            )
                        }
                        if (alignsMount && session.mountAlignmentActive) {
                            // Drops back to the Start card rather than re-arming straight away, so a
                            // restart goes through the same at-home check as the first attempt --
                            // re-arming is the only way out of a half-finished sequence (OnStep has no
                            // cancel), and doing it from the wrong position is what made it wrong.
                            ToolbarActionButton(
                                icon = Icons.Default.RestartAlt,
                                label = "Restart",
                                enabled = !mountCommandInFlight,
                                onClick = {
                                    pendingTarget = null
                                    restartRequested = true
                                },
                            )
                        }
                        ToolbarActionButton(icon = Icons.Default.Visibility, label = "Filter", onClick = { showFilterSheet = true })
                        ToolbarActionButton(
                            icon = if (showList) Icons.Default.Map else Icons.AutoMirrored.Filled.List,
                            label = if (showList) "Map" else "List",
                            enabled = showList || canPickStar,
                            onClick = { showList = !showList },
                        )
                    }
                    VerticalDivider(Modifier.height(32.dp).padding(horizontal = 4.dp))
                    ToolbarActionButton(icon = Icons.Default.Close, label = "Exit", onClick = onBack)
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            SkyMap(
                directions = snapshot.directions,
                viewport = viewport,
                onViewportChange = { viewport = it },
                markers = syncedMarkers + listOfNotNull(pendingMarker, telescopeDirection?.let(SkyMapMarker::telescope)),
                constellationLines = snapshot.constellationLines,
                // Null whenever a pick would go nowhere (see canPickStar): the map stays pannable
                // as an overview, it just stops being a picker.
                onSelect = if (canPickStar) { obj -> (obj as? StarObject)?.let(pickStar) } else null,
                modifier = Modifier.fillMaxSize(),
            )

            AlignmentProgressOverlay(
                starCount = session.starCount,
                // Absent, not disabled, once the mount's sequence is armed -- see
                // AlignmentSession.canChangeStarCount.
                onStarCountChange = if (session.canChangeStarCount) {
                    { count -> session.changeStarCount(count); pendingTarget = null }
                } else {
                    null
                },
                syncedNames = syncedNames,
                // A mount's sequence is append-only: OnStep offers no "drop that star" command, so
                // there is nothing to hang a remove button on.
                onRemovePoint = if (alignsMount) null else session::removePointAt,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            )

            val target = pendingTarget
            // The step cards and the control pad share one bottom-anchored column so the pad
            // stacks *above* whichever card is showing rather than covering it -- centering a star
            // means reading the instruction and driving the mount at the same time.
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (showControls) {
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
                        modifier = Modifier.mapOverlayScrim().padding(8.dp),
                    )
                }
                val stepModifier = Modifier.fillMaxWidth()
                when {
                    alignsMount && session.isComplete -> MountAlignmentCompleteCard(
                        starCount = session.starCount,
                        busy = mountCommandInFlight,
                        onSave = {
                            mountCommandInFlight = true
                            scope.launch {
                                if (onSaveMountAlignmentModel()) {
                                    session.clear()
                                    onBack()
                                } else {
                                    mountCommandInFlight = false
                                    snackbarHostState.showSnackbar("Mount refused to store the model — it stays active until power off")
                                }
                            }
                        },
                        modifier = stepModifier,
                    )

                    phoneResult is AlignmentResult.Success -> AlignmentCompleteCard(
                        result = phoneResult,
                        onDone = { onSaveModel(phoneResult.model); session.clear(); onBack() },
                        modifier = stepModifier,
                    )

                    startCardShowing -> StartMountAlignmentCard(
                        starCount = session.starCount,
                        atHome = atHome,
                        alreadyArmed = session.mountAlignmentActive,
                        busy = mountCommandInFlight,
                        onSendHome = {
                            scope.launch {
                                onMoveHome()
                                snackbarHostState.showSnackbar("Sending the mount home…")
                            }
                        },
                        // markMountAlignmentArmed only after the mount has taken the command, so the
                        // app's idea of the run and the mount's never diverge -- including on a restart,
                        // where the previous sequence stays the app's truth until this one replaces it.
                        onStart = {
                            mountCommandInFlight = true
                            scope.launch {
                                if (onBeginMountAlignment(session.starCount)) {
                                    session.markMountAlignmentArmed()
                                    restartRequested = false
                                } else {
                                    snackbarHostState.showSnackbar("Mount refused to start a ${session.starCount}-star alignment")
                                }
                                mountCommandInFlight = false
                            }
                        },
                        modifier = stepModifier,
                    )

                    target != null -> ConfirmSyncStep(
                        target = target,
                        location = location,
                        nowEpochMillis = now,
                        existingDirections = syncedDirections,
                        alignsMount = alignsMount,
                        busy = mountCommandInFlight,
                        onConfirm = { if (alignsMount) confirmMountSync(target) else confirmPhoneSync(target) },
                        modifier = stepModifier,
                    )

                    phoneResult is AlignmentResult.Failure -> StepCard(stepModifier) {
                        Text(phoneResult.reason, color = MaterialTheme.colorScheme.error)
                        Text(
                            "Remove one of the synced stars, then pick a different one.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    else -> StepCard(stepModifier) {
                        Text("Pick a star to sync", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Tap one on the map, or switch to the list of the brightest stars up right now.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (showList) {
                StarListOverlay(
                    stars = sortedSuggestions,
                    location = location,
                    nowEpochMillis = now,
                    sortMode = sortMode,
                    onSortModeChange = { sortMode = it },
                    onPick = pickStar,
                    modifier = Modifier.fillMaxSize(),
                )
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
}

/** Whichever kind of evidence this run collects, the star's display name for slot [index]. */
private fun syncedStarName(session: AlignmentSession, catalogRepository: CatalogRepository, index: Int): String =
    session.mountAlignedStars.getOrNull(index)?.displayName
        ?: session.points[index].let { catalogRepository.byId(it.targetId)?.displayName ?: it.targetId }

/** The map's top-left overlay: how many stars this run uses, and which are already synced.
 *  A null [onStarCountChange] or [onRemovePoint] renders the corresponding control as absent
 *  rather than disabled -- both are cases where the action does not exist at all, not cases where
 *  it is temporarily unavailable. */
@Composable
private fun AlignmentProgressOverlay(
    starCount: Int,
    onStarCountChange: ((Int) -> Unit)?,
    syncedNames: List<String>,
    onRemovePoint: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.mapOverlayScrim().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (onStarCountChange != null) {
            SingleChoiceSegmentedButtonRow {
                listOf(2, 3).forEachIndexed { index, count ->
                    SegmentedButton(
                        selected = starCount == count,
                        onClick = { onStarCountChange(count) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        label = { Text("$count stars") },
                    )
                }
            }
        }
        if (syncedNames.isNotEmpty()) {
            Text(
                "Synced ${syncedNames.size}/$starCount",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            syncedNames.forEachIndexed { index, name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    if (onRemovePoint != null) {
                        IconButton(onClick = { onRemovePoint(index) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove")
                        }
                    }
                }
            }
        }
    }
}

/** The list alternative to picking off the map: the brightest stars up right now, laid over the
 *  map rather than replacing it, so the map keeps its viewport and switching back is instant. */
@Composable
private fun StarListOverlay(
    stars: List<StarObject>,
    location: ObserverLocation,
    nowEpochMillis: Long,
    sortMode: StarSortMode,
    onSortModeChange: (StarSortMode) -> Unit,
    onPick: (StarObject) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                StarSortMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = sortMode == mode,
                        onClick = { onSortModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, StarSortMode.entries.size),
                        label = { Text("Sort: ${mode.label}") },
                    )
                }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(stars) { star ->
                    val horizontal = star.currentHorizontal(location, nowEpochMillis)
                    ListItem(
                        headlineContent = { Text(star.displayName) },
                        supportingContent = { Text("mag ${formatDegrees(star.magnitude.toDouble())} · alt ${formatDegrees(horizontal.altitude.degrees)}°") },
                        modifier = Modifier.clickable { onPick(star) },
                    )
                }
            }
        }
    }
}

/** Every step of the flow is a scrimmed panel pinned to the bottom of the map, so the sky above it
 *  -- and the marker for whichever star the step is about -- stays visible throughout. */
@Composable
private fun StepCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .mapOverlayScrim(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .padding(16.dp),
    ) {
        content()
    }
}

/** Arming the mount's alignment gets its own deliberate step rather than happening on the first
 *  confirm, because the command behind it re-homes the mount, throws away its current model and
 *  forces tracking on -- and the protocol has no way to take any of that back (see
 *  [com.astrocompass.telescope.TelescopeConnection.beginAlignment]). Spelling out the
 *  precondition is the whole point of the step: the mount is about to declare wherever it now
 *  stands to be home. */
@Composable
private fun StartMountAlignmentCard(
    starCount: Int,
    atHome: Boolean?,
    alreadyArmed: Boolean,
    busy: Boolean,
    onSendHome: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StepCard(modifier) {
        Text(
            if (alreadyArmed) "Start the $starCount-star alignment over" else "Start a $starCount-star mount alignment",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Starting declares wherever the mount is standing to be its home position, discards " +
                "its current alignment model and turns tracking on. There is no undo.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (alreadyArmed) {
            Text(
                "The mount is still armed from the run in progress — starting replaces it, and " +
                    "the stars it has already taken are discarded.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (atHome == false) {
            Text(
                "The mount reports it is not at home. Start now and it will believe home is " +
                    "wherever it is currently pointed — send it home first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            OutlinedButton(onClick = onSendHome, enabled = !busy, modifier = Modifier.padding(end = 12.dp)) {
                Text("Send home")
            }
            Button(onClick = onStart, enabled = !busy) { Text("Start") }
        }
    }
}

/** The "point, then confirm" step: the user centers the telescope on [target] before tapping
 *  confirm, rather than a single tap syncing immediately -- syncing the instant a star is picked,
 *  before the telescope is actually pointed at it, would record a wrong direction. Under
 *  [alignsMount] that is still true after a GOTO: the slew gets close, but only the user's own
 *  centering makes "the telescope is on this star" a fact worth handing the mount, so the
 *  instruction says so rather than implying GOTO finished the job. Picking a different star while
 *  this step is showing happens on the map, not through a button here -- see `canPickStar`.
 *
 *  [existingDirections] drives two warnings that only the sky map's full-catalog picker can
 *  trigger -- the list view's suggestions are already filtered clear of both. Neither blocks
 *  confirming: a below-horizon pick may just mean the telescope will be pointed there shortly, and
 *  the too-close warning only anticipates what the fit would reject anyway. */
@Composable
private fun ConfirmSyncStep(
    target: StarObject,
    location: ObserverLocation,
    nowEpochMillis: Long,
    existingDirections: List<Vector3>,
    alignsMount: Boolean,
    busy: Boolean,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val horizontal = target.currentHorizontal(location, nowEpochMillis)
    val direction = horizontal.toEnu()
    val tooClose = existingDirections.any { direction.angleTo(it) < AlignmentSolver.MIN_STAR_SEPARATION }
    StepCard(modifier) {
        Text("Point at ${target.displayName}", style = MaterialTheme.typography.titleMedium)
        Text(
            "alt ${formatDegrees(horizontal.altitude.degrees)}° · az ${formatDegrees(horizontal.azimuth.degrees)}°",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        if (horizontal.altitude.degrees < 0) {
            Text(
                "This star is below the horizon right now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (tooClose) {
            // Only the phone solver actually rejects this (AlignmentSolver.MIN_STAR_SEPARATION);
            // OnStep's own addStar enforces no separation at all, so for a mount run the same
            // closeness is a quality hint rather than a predicted refusal.
            Text(
                if (alignsMount) {
                    "Within ${AlignmentSolver.MIN_STAR_SEPARATION.degrees.toInt()}° of an already-synced star -- " +
                        "stars further apart give the mount a better model."
                } else {
                    "Less than ${AlignmentSolver.MIN_STAR_SEPARATION.degrees.toInt()}° from an already-synced star -- " +
                        "the fit will likely be rejected."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Text(
            if (alignsMount) {
                "Use GOTO to get close, then center ${target.displayName} in the eyepiece by hand and confirm."
            } else {
                "Center the telescope on ${target.displayName}, then confirm."
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Button(onClick = onConfirm, enabled = !busy) { Text("Confirm sync") }
        }
    }
}

/** The last phone sync's confirmation: the model is already solved by the time this appears, so
 *  the only remaining decision is whether to keep it -- [onDone] saves it and leaves the screen. */
@Composable
private fun AlignmentCompleteCard(result: AlignmentResult.Success, onDone: () -> Unit, modifier: Modifier = Modifier) {
    StepCard(modifier) {
        Row {
            Icon(Icons.Default.Check, contentDescription = null)
            Text(" Phone alignment complete", style = MaterialTheme.typography.titleMedium)
        }
        Text("RMS residual: ${formatDegrees(result.model.rmsResidualDegrees)}°")
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
            Button(onClick = onDone) { Text("OK") }
        }
    }
}

/** The mount computed and applied its own model on the last accepted star, so there is no residual
 *  to show and nothing to approve -- the one thing left is [onSave], which persists it so it
 *  survives the next power cycle. */
@Composable
private fun MountAlignmentCompleteCard(starCount: Int, busy: Boolean, onSave: () -> Unit, modifier: Modifier = Modifier) {
    StepCard(modifier) {
        Row {
            Icon(Icons.Default.Check, contentDescription = null)
            Text(" Telescope aligned on $starCount stars", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "The model is already in use. Store it so the mount keeps it after power off.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
            Button(onClick = onSave, enabled = !busy) { Text("Store on mount") }
        }
    }
}

/** The brightest currently-visible stars, sorted brightest-first. Only excludes stars too close
 *  to an already-*confirmed* point -- the fit itself rejects any too-close pair anyway, so
 *  suggestions don't also need to be mutually far apart from each other, which would needlessly
 *  shrink a list meant to offer real choice.
 *
 *  [filterMaxMagnitude] is [MapObjectFilter.maxMagnitude] from the map's own "Filter" sheet -- it
 *  can only narrow the list further, never loosen it past [SUGGESTION_MAGNITUDE_LIMIT]: that limit
 *  is tuned for alignment-star quality, not a display preference, so a user who's opened the filter
 *  up wide to browse dim stars on the map still only gets sensible alignment candidates here. */
private fun suggestStars(
    catalogRepository: CatalogRepository,
    location: ObserverLocation,
    nowEpochMillis: Long,
    alreadyChosen: List<Vector3>,
    filterMaxMagnitude: Float?,
): List<StarObject> {
    val magnitudeLimit = filterMaxMagnitude?.let { minOf(it, SUGGESTION_MAGNITUDE_LIMIT) } ?: SUGGESTION_MAGNITUDE_LIMIT
    return catalogRepository.all
        .filterIsInstance<StarObject>()
        .filter { it.magnitude <= magnitudeLimit }
        .map { it to it.currentHorizontal(location, nowEpochMillis) }
        .filter { (_, horizontal) -> horizontal.altitude.degrees >= SUGGESTION_MIN_ALTITUDE_DEGREES }
        .filter { (_, horizontal) ->
            val direction = horizontal.toEnu()
            alreadyChosen.all { it.angleTo(direction).degrees >= MIN_SEPARATION_FOR_SUGGESTIONS_DEGREES }
        }
        .sortedBy { (star, _) -> star.magnitude }
        .take(MAX_SUGGESTIONS)
        .map { (star, _) -> star }
}

private fun formatDegrees(value: Double): String = (kotlin.math.round(value * 10) / 10).toString()
