@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.time.currentEpochMillis
import com.astrocompass.alignment.AlignmentPoint
import com.astrocompass.alignment.AlignmentResult
import com.astrocompass.alignment.AlignmentSolver
import com.astrocompass.alignment.AlignmentSource
import com.astrocompass.catalog.CatalogRepository
import com.astrocompass.catalog.SkyObject
import com.astrocompass.catalog.StarObject
import com.astrocompass.guiding.currentHorizontal
import com.astrocompass.location.ObserverLocation
import com.astrocompass.ui.components.SkyMap
import com.astrocompass.ui.components.SkyMapMarker
import com.astrocompass.ui.components.rememberSkyMapSnapshot
import com.astrocompass.ui.skymap.SkyMapViewport
import com.astrocompass.ui.theme.OnTargetGreen
import kotlinx.coroutines.delay

private const val SUGGESTION_MAGNITUDE_LIMIT = 3.5f
private const val SUGGESTION_MIN_ALTITUDE_DEGREES = 15.0
private const val MIN_SEPARATION_FOR_SUGGESTIONS_DEGREES = 30.0
private const val MAX_SUGGESTIONS = 50

/** How the list view orders [suggestStars]'s result -- [MAGNITUDE] matches the order the function
 *  already returns (brightest first), so that mode needs no re-sort. */
private enum class StarSortMode(val label: String) { MAGNITUDE("Magnitude"), NAME("Name") }

@Composable
fun AlignmentScreen(
    catalogRepository: CatalogRepository,
    location: ObserverLocation,
    /** Marked in blue while a connected mount is reporting -- see
     *  [com.astrocompass.AppContainer.telescopeSkyDirection]. */
    telescopeDirection: Vector3?,
    onCapturePoint: (target: SkyObject, source: AlignmentSource, nowEpochMillis: Long) -> AlignmentPoint?,
    onSaveModel: (com.astrocompass.alignment.AlignmentModel) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var starCount by remember { mutableStateOf(2) }
    var points by remember { mutableStateOf(listOf<AlignmentPoint>()) }
    var pendingTarget by remember { mutableStateOf<StarObject?>(null) }
    var mapMode by remember { mutableStateOf(true) }
    var sortMode by remember { mutableStateOf(StarSortMode.MAGNITUDE) }
    var viewport by remember { mutableStateOf(SkyMapViewport.DEFAULT) }

    // Derived, not state written from an effect: the solve is pure and instant, so the last
    // "Confirm sync" completes the alignment on its own instead of parking the user on a
    // "Compute" button. Solving at the final point's capture time rather than "now" keeps the
    // model's timestamp on the same instant the fit actually describes.
    val result = remember(points, starCount) {
        if (points.size == starCount) AlignmentSolver.solve(points, points.last().capturedAtEpochMillis) else null
    }

    var now by remember { mutableStateOf(currentEpochMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = currentEpochMillis()
        }
    }

    val catalogLoaded by catalogRepository.isLoaded.collectAsState()
    val suggestions = remember(catalogLoaded, now, points) {
        if (!catalogLoaded) emptyList() else suggestStars(catalogRepository, location, now, points.map { it.skyDirection })
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
    val snapshot = rememberSkyMapSnapshot(catalogRepository, location, catalogFilter = { it is StarObject })
    val syncedMarkers = remember(points) {
        points.map { point ->
            SkyMapMarker(
                direction = point.skyDirection,
                color = OnTargetGreen,
                label = catalogRepository.byId(point.targetId)?.displayName,
            )
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Align") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(2, 3).forEachIndexed { index, count ->
                    SegmentedButton(
                        selected = starCount == count,
                        onClick = {
                            starCount = count
                            points = points.take(count)
                            pendingTarget = null
                        },
                        shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(index, 2),
                        label = { Text("$count stars") },
                    )
                }
            }

            if (points.isNotEmpty()) {
                Text(
                    "Synced (${points.size}/$starCount)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
                points.forEachIndexed { index, point ->
                    ListItem(
                        headlineContent = { Text(catalogRepository.byId(point.targetId)?.displayName ?: point.targetId) },
                        trailingContent = {
                            IconButton(onClick = { points = points.toMutableList().also { it.removeAt(index) } }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove")
                            }
                        },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }

            val target = pendingTarget
            when {
                result is AlignmentResult.Success ->
                    AlignmentCompleteCard(result, onDone = { onSaveModel(result.model); onBack() })

                target != null -> ConfirmSyncStep(
                    target = target,
                    location = location,
                    nowEpochMillis = now,
                    existingPoints = points,
                    onConfirm = {
                        onCapturePoint(target, AlignmentSource.MANUAL_SYNC, now)?.let { points = points + it }
                        pendingTarget = null
                    },
                    onPickDifferentStar = { pendingTarget = null },
                )

                result is AlignmentResult.Failure -> Column {
                    Text(result.reason, color = MaterialTheme.colorScheme.error)
                    Text(
                        "Remove one of the stars above, then pick a different one.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                else -> Column(Modifier.fillMaxWidth().weight(1f)) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Pick a star to sync", style = MaterialTheme.typography.titleSmall)
                        Row {
                            IconButton(onClick = { mapMode = false }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.List,
                                    contentDescription = "List view",
                                    tint = if (!mapMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { mapMode = true }) {
                                Icon(
                                    Icons.Default.Map,
                                    contentDescription = "Map view",
                                    tint = if (mapMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (mapMode) {
                        SkyMap(
                            directions = snapshot.directions,
                            viewport = viewport,
                            onViewportChange = { viewport = it },
                            // Combined here rather than inside syncedMarkers' `remember(points)`,
                            // which would freeze the mount's marker at its first report.
                            markers = syncedMarkers + listOfNotNull(telescopeDirection?.let(SkyMapMarker::telescope)),
                            constellationLines = snapshot.constellationLines,
                            onSelect = { obj -> (obj as? StarObject)?.let { pendingTarget = it } },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    } else {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            StarSortMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = sortMode == mode,
                                    onClick = { sortMode = mode },
                                    shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(index, StarSortMode.entries.size),
                                    label = { Text("Sort: ${mode.label}") },
                                )
                            }
                        }
                        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(sortedSuggestions) { star ->
                                val horizontal = star.currentHorizontal(location, now)
                                ListItem(
                                    headlineContent = { Text(star.displayName) },
                                    supportingContent = { Text("mag ${formatDegrees(star.magnitude.toDouble())} · alt ${formatDegrees(horizontal.altitude.degrees)}°") },
                                    modifier = Modifier.clickable { pendingTarget = star },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The "point, then confirm" step of the alignment wizard: the user centers the telescope on
 *  [target] before tapping confirm, rather than a single tap syncing immediately -- syncing the
 *  instant a star is picked, before the telescope is actually pointed at it, would capture a
 *  wrong sensor direction.
 *
 *  [existingPoints] drives two warnings that only the sky map's full-catalog picker can trigger --
 *  the list view's suggestions are already filtered clear of both. Neither blocks "Confirm sync":
 *  a below-horizon pick may just mean the telescope will be pointed there shortly, and the
 *  too-close warning only anticipates what [AlignmentSolver.solve] would reject outright anyway. */
@Composable
private fun ConfirmSyncStep(
    target: StarObject,
    location: ObserverLocation,
    nowEpochMillis: Long,
    existingPoints: List<AlignmentPoint>,
    onConfirm: () -> Unit,
    onPickDifferentStar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val horizontal = target.currentHorizontal(location, nowEpochMillis)
    val direction = horizontal.toEnu()
    val tooCloseTo = existingPoints.firstOrNull { direction.angleTo(it.skyDirection) < AlignmentSolver.MIN_STAR_SEPARATION }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
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
            if (tooCloseTo != null) {
                Text(
                    "Less than ${AlignmentSolver.MIN_STAR_SEPARATION.degrees.toInt()}° from an already-synced star -- " +
                        "the fit will likely be rejected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                "Center the telescope on ${target.displayName}, then confirm.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                OutlinedButton(onClick = onPickDifferentStar, modifier = Modifier.padding(end = 12.dp)) {
                    Text("Choose a different star")
                }
                Button(onClick = onConfirm) { Text("Confirm sync") }
            }
        }
    }
}

/** The last sync's confirmation: the model is already solved by the time this appears, so the
 *  only remaining decision is whether to keep it -- [onDone] saves it and leaves the screen. */
@Composable
private fun AlignmentCompleteCard(result: AlignmentResult.Success, onDone: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Icon(Icons.Default.Check, contentDescription = null)
                Text(" Alignment complete", style = MaterialTheme.typography.titleMedium)
            }
            Text("RMS residual: ${formatDegrees(result.model.rmsResidualDegrees)}°")
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
                Button(onClick = onDone) { Text("OK") }
            }
        }
    }
}

/** The brightest currently-visible stars, sorted brightest-first. Only excludes stars too close
 *  to an already-*confirmed* point -- [AlignmentSolver] itself rejects any too-close pair at
 *  compute time, so suggestions don't also need to be mutually far apart from each other, which
 *  would needlessly shrink a list meant to offer real choice. */
private fun suggestStars(
    catalogRepository: CatalogRepository,
    location: ObserverLocation,
    nowEpochMillis: Long,
    alreadyChosen: List<com.astrocompass.astro.Vector3>,
): List<StarObject> {
    return catalogRepository.all
        .filterIsInstance<StarObject>()
        .filter { it.magnitude <= SUGGESTION_MAGNITUDE_LIMIT }
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
