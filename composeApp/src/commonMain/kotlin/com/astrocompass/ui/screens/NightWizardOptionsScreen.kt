@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.astrocompass.astro.time.currentEpochMillis
import com.astrocompass.catalog.CatalogRepository
import com.astrocompass.catalog.MapObjectCategory
import com.astrocompass.catalog.MapObjectFilter
import com.astrocompass.catalog.SkyObject
import com.astrocompass.guiding.NightWizardCandidates
import com.astrocompass.guiding.NightWizardDefaultStartTime
import com.astrocompass.location.ObserverLocation
import com.astrocompass.ui.components.FilterToggleRow
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

private const val MAGNITUDE_LIMIT_MIN = 1f
private const val MAGNITUDE_LIMIT_MAX = 16f
private const val MAGNITUDE_LIMIT_STEP = 0.5f
private const val MIN_ALTITUDE_MIN = 0f
private const val MIN_ALTITUDE_MAX = 90f
private const val MIN_ALTITUDE_STEP = 5f

/** Step 1 of the Night Wizard: what to observe tonight, reached from [MapScreen]'s "Night wizard"
 *  button. [startEpochMillis] is null until the user overrides it -- until then, the default
 *  computed from [NightWizardDefaultStartTime] is shown and used. "Next" filters/ranks the whole
 *  catalog once via [NightWizardCandidates.compute] and hands the resulting fixed list to the
 *  caller, which owns it for the rest of the wizard (the list screen and the guide screen's
 *  Prev/Next both just walk this same snapshot, not a re-filtered one). */
@Composable
fun NightWizardOptionsScreen(
    catalogRepository: CatalogRepository,
    location: ObserverLocation,
    filter: MapObjectFilter,
    onFilterChange: (MapObjectFilter) -> Unit,
    magnitudeLimit: Float,
    onMagnitudeLimitChange: (Float) -> Unit,
    minAltitudeDegrees: Float,
    onMinAltitudeDegreesChange: (Float) -> Unit,
    startEpochMillis: Long?,
    onStartEpochMillisChange: (Long?) -> Unit,
    onNext: (candidates: List<SkyObject>, startEpochMillis: Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeZone = remember { TimeZone.currentSystemDefault() }
    val now = remember { currentEpochMillis() }
    val defaultStart = remember(location, now) {
        NightWizardDefaultStartTime.compute(now, location.latitude, location.longitude)
    }
    val effectiveStartEpochMillis = startEpochMillis ?: defaultStart.startEpochMillis
    var showTimePicker by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Night Wizard") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text("Show", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            for (category in MapObjectCategory.entries) {
                FilterToggleRow(
                    label = category.label,
                    checked = filter.isShown(category),
                    onCheckedChange = { checked -> onFilterChange(filter.withShown(category, checked)) },
                )
            }

            Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(
                    "Objects mag ${formatMagnitude(magnitudeLimit)} or brighter",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = magnitudeLimit,
                    onValueChange = onMagnitudeLimitChange,
                    valueRange = MAGNITUDE_LIMIT_MIN..MAGNITUDE_LIMIT_MAX,
                    steps = ((MAGNITUDE_LIMIT_MAX - MAGNITUDE_LIMIT_MIN) / MAGNITUDE_LIMIT_STEP).toInt() - 1,
                )
            }

            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    "At least ${minAltitudeDegrees.toInt()}° above horizon",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = minAltitudeDegrees,
                    onValueChange = onMinAltitudeDegreesChange,
                    valueRange = MIN_ALTITUDE_MIN..MIN_ALTITUDE_MAX,
                    steps = ((MIN_ALTITUDE_MAX - MIN_ALTITUDE_MIN) / MIN_ALTITUDE_STEP).toInt() - 1,
                )
            }

            Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp)) {
                Text("Starts at", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(formatLocalTime(effectiveStartEpochMillis, timeZone))
                }
                if (!defaultStart.twilightKnown && startEpochMillis == null) {
                    Text(
                        "Couldn't determine tonight's twilight for this location -- starting now.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Button(onClick = {
                    val candidates = NightWizardCandidates.compute(
                        objects = catalogRepository.all,
                        filter = filter,
                        magnitudeLimit = magnitudeLimit,
                        minAltitudeDegrees = minAltitudeDegrees,
                        location = location,
                        startEpochMillis = effectiveStartEpochMillis,
                    )
                    onNext(candidates, effectiveStartEpochMillis)
                }) { Text("Next") }
            }
        }
    }

    if (showTimePicker) {
        StartTimePickerDialog(
            initialEpochMillis = effectiveStartEpochMillis,
            timeZone = timeZone,
            onConfirm = { picked -> onStartEpochMillisChange(picked); showTimePicker = false },
            onDismiss = { showTimePicker = false },
        )
    }
}

@Composable
private fun StartTimePickerDialog(
    initialEpochMillis: Long,
    timeZone: TimeZone,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialLocal = Instant.fromEpochMilliseconds(initialEpochMillis).toLocalDateTime(timeZone)
    val state = rememberTimePickerState(initialHour = initialLocal.hour, initialMinute = initialLocal.minute, is24Hour = true)
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Start time", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 16.dp))
                TimePicker(state = state)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.padding(end = 12.dp)) { Text("Cancel") }
                    Button(onClick = {
                        val picked = LocalDateTime(initialLocal.date, LocalTime(state.hour, state.minute))
                        onConfirm(picked.toInstant(timeZone).toEpochMilliseconds())
                    }) { Text("Set") }
                }
            }
        }
    }
}

private fun formatLocalTime(epochMillis: Long, timeZone: TimeZone): String {
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(timeZone)
    return "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
}

private fun formatMagnitude(value: Float): String = (kotlin.math.round(value * 10) / 10).toString()
