@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.astrocompass.astro.time.currentEpochMillis
import com.astrocompass.catalog.SkyObject
import com.astrocompass.guiding.AbsoluteReferenceState
import com.astrocompass.guiding.GuidanceCalculator
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

@Composable
fun GuidanceScreen(
    target: SkyObject,
    pointingService: PointingService,
    absoluteReference: StateFlow<AbsoluteReferenceState?>,
    location: ObserverLocation,
    onTargetToleranceDegrees: Double,
    onSyncOnThisObject: () -> Unit,
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
            SyncAgeChip(syncedAt, now, onSyncOnThisObject)

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
}

@Composable
private fun SyncAgeChip(syncedAtEpochMillis: Long?, nowEpochMillis: Long, onSync: () -> Unit) {
    val ageSeconds = syncedAtEpochMillis?.let { (nowEpochMillis - it) / 1000 } ?: Long.MAX_VALUE
    val color = when {
        ageSeconds > SYNC_AGE_RED_SECONDS -> MaterialTheme.colorScheme.error
        ageSeconds > SYNC_AGE_AMBER_SECONDS -> Color(0xFFFFA000)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Synced ${formatAge(ageSeconds)} ago", color = color, style = MaterialTheme.typography.bodyMedium)
        AssistChip(onClick = onSync, label = { Text("Sync on this object") })
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

private fun formatAge(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

private fun formatDegrees(value: Double): String = (kotlin.math.round(value * 10) / 10).toString()
