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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import kotlinx.coroutines.delay

private const val SUGGESTION_MAGNITUDE_LIMIT = 3.5f
private const val SUGGESTION_MIN_ALTITUDE_DEGREES = 15.0
private const val MIN_SEPARATION_FOR_SUGGESTIONS_DEGREES = 30.0
private const val MAX_SUGGESTIONS = 50

@Composable
fun AlignmentScreen(
    catalogRepository: CatalogRepository,
    location: ObserverLocation,
    onCapturePoint: (target: SkyObject, source: AlignmentSource, nowEpochMillis: Long) -> AlignmentPoint?,
    onSaveModel: (com.astrocompass.alignment.AlignmentModel) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var starCount by remember { mutableStateOf(2) }
    var points by remember { mutableStateOf(listOf<AlignmentPoint>()) }
    var pendingTarget by remember { mutableStateOf<StarObject?>(null) }
    var result by remember { mutableStateOf<AlignmentResult?>(null) }

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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Align") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
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
                            result = null
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
                            IconButton(onClick = { points = points.toMutableList().also { it.removeAt(index) }; result = null }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove")
                            }
                        },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }

            when (val r = result) {
                is AlignmentResult.Success -> AlignmentSuccessCard(r, onSave = { onSaveModel(r.model); onBack() })
                is AlignmentResult.Failure -> Text(
                    r.reason,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                null -> Unit
            }

            val target = pendingTarget
            when {
                target != null -> ConfirmSyncStep(
                    target = target,
                    location = location,
                    nowEpochMillis = now,
                    onConfirm = {
                        val point = onCapturePoint(target, AlignmentSource.MANUAL_SYNC, now)
                        if (point != null) {
                            points = points + point
                            result = null
                        }
                        pendingTarget = null
                    },
                    onPickDifferentStar = { pendingTarget = null },
                )

                points.size == starCount -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Button(onClick = { result = AlignmentSolver.solve(points, now) }) { Text("Compute alignment") }
                }

                else -> {
                    Text(
                        "Pick a star to sync",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(suggestions) { star ->
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

/** The "point, then confirm" step of the alignment wizard: the user centers the telescope on
 *  [target] before tapping confirm, rather than a single tap syncing immediately -- syncing the
 *  instant a star is picked, before the telescope is actually pointed at it, would capture a
 *  wrong sensor direction. */
@Composable
private fun ConfirmSyncStep(
    target: StarObject,
    location: ObserverLocation,
    nowEpochMillis: Long,
    onConfirm: () -> Unit,
    onPickDifferentStar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val horizontal = target.currentHorizontal(location, nowEpochMillis)
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Point at ${target.displayName}", style = MaterialTheme.typography.titleMedium)
            Text(
                "alt ${formatDegrees(horizontal.altitude.degrees)}° · az ${formatDegrees(horizontal.azimuth.degrees)}°",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
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

@Composable
private fun AlignmentSuccessCard(result: AlignmentResult.Success, onSave: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Icon(Icons.Default.Check, contentDescription = null)
                Text(" Alignment ready", style = MaterialTheme.typography.titleMedium)
            }
            Text("RMS residual: ${formatDegrees(result.model.rmsResidualDegrees)}°")
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
                Button(onClick = onSave) { Text("Save alignment") }
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
