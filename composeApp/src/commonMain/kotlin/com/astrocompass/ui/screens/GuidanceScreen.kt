@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.astrocompass.astro.time.currentEpochMillis
import com.astrocompass.catalog.SkyObject
import com.astrocompass.guiding.AbsoluteReferenceState
import com.astrocompass.guiding.GuidanceCalculator
import com.astrocompass.guiding.PlateSolveAttempt
import com.astrocompass.guiding.PointingService
import com.astrocompass.guiding.currentHorizontal
import com.astrocompass.location.ObserverLocation
import com.astrocompass.ui.components.ArrowIndicator
import com.astrocompass.ui.components.DeltaBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private const val UPDATE_INTERVAL_MS = 50L
private const val SYNC_AGE_AMBER_SECONDS = 5 * 60L
private const val SYNC_AGE_RED_SECONDS = 15 * 60L
private const val STABILIZATION_MILLIS = 2_000L

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
    pointingService: PointingService,
    absoluteReference: StateFlow<AbsoluteReferenceState?>,
    location: ObserverLocation,
    onTargetToleranceDegrees: Double,
    onSyncOnThisObject: () -> Unit,
    onPlateSolve: suspend () -> PlateSolveAttempt?,
    onApplyPlateSolve: (PlateSolveAttempt) -> Unit,
    onOpenAlignment: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAligned by pointingService.isAligned.collectAsState()
    val currentPointing by pointingService.currentSkyDirection.collectAsState()
    val reference by absoluteReference.collectAsState()
    val syncedAt = reference?.establishedAtEpochMillis

    var now by remember { mutableStateOf(currentEpochMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(UPDATE_INTERVAL_MS)
            now = currentEpochMillis()
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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(target.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        if (!isAligned || currentPointing == null) {
            NotAlignedContent(onOpenAlignment, Modifier.padding(padding))
            return@Scaffold
        }

        val targetDirection = target.currentHorizontal(location, now).toEnu()
        val guidance = GuidanceCalculator.compute(currentPointing!!, targetDirection, onTargetToleranceDegrees)

        val haptic = LocalHapticFeedback.current
        var wasOnTarget by remember { mutableStateOf(false) }
        LaunchedEffect(guidance.isOnTarget) {
            if (guidance.isOnTarget && !wasOnTarget) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            wasOnTarget = guidance.isOnTarget
        }

        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SyncAgeChip(
                syncedAtEpochMillis = syncedAt,
                nowEpochMillis = now,
                onSync = onSyncOnThisObject,
                onPlateSolve = { plateSolveRunId = (plateSolveRunId ?: 0) + 1 },
            )

            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ArrowIndicator(guidance.arrowAngleDegrees, guidance.isOnTarget)
                    Text(
                        "${formatDegrees(guidance.separationDegrees)}° away",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    if (guidance.isOnTarget) {
                        Text("On target", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            DeltaBar("ALT", guidance.altitudeDeltaDegrees, Modifier.padding(bottom = 12.dp))
            DeltaBar("AZ (cross-track)", guidance.crossTrackDeltaDegrees)
        }
    }

    val state = plateSolveState
    if (state != null) {
        PlateSolveDialog(
            state = state,
            onApply = { attempt -> onApplyPlateSolve(attempt); closePlateSolve() },
            onDismiss = closePlateSolve,
        )
    }
}

@Composable
private fun SyncAgeChip(syncedAtEpochMillis: Long?, nowEpochMillis: Long, onSync: () -> Unit, onPlateSolve: () -> Unit) {
    val ageSeconds = syncedAtEpochMillis?.let { (nowEpochMillis - it) / 1000 } ?: Long.MAX_VALUE
    val color = when {
        ageSeconds > SYNC_AGE_RED_SECONDS -> MaterialTheme.colorScheme.error
        ageSeconds > SYNC_AGE_AMBER_SECONDS -> Color(0xFFFFA000)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Synced ${formatAge(ageSeconds)} ago", color = color, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onPlateSolve, label = { Text("Platesolve") })
            AssistChip(onClick = onSync, label = { Text("Sync on this object") })
        }
    }
}

@Composable
private fun NotAlignedContent(onOpenAlignment: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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

/** Walks through the "Platesolve" flow: a stabilization pause (so the tap that opened this dialog
 *  doesn't blur the photo), then solving, then a result the user must explicitly apply or
 *  discard -- never applied automatically, since a plausible-looking but wrong
 *  [com.astrocompass.guiding.CameraMounting] preset can only be caught by the user noticing the
 *  correction looks too large. */
@Composable
private fun PlateSolveDialog(state: PlateSolveUiState, onApply: (PlateSolveAttempt) -> Unit, onDismiss: () -> Unit) {
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
                        Text(
                            "A plausible drift correction is a few degrees. Tens of degrees " +
                                "usually means the Camera mounting setting is wrong.",
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
