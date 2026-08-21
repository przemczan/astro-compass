@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astrocompass.catalog.SkyObject
import com.astrocompass.guiding.currentHorizontal
import com.astrocompass.location.ObserverLocation

/** Step 2 of the Night Wizard: a preview of [objects] (already filtered and ranked
 *  brightest-first by [com.astrocompass.guiding.NightWizardCandidates], a fixed snapshot -- this
 *  screen only displays it, it never re-filters). Rows aren't clickable; ordering is fixed and
 *  "Start" always begins at the brightest object. */
@Composable
fun NightWizardListScreen(
    objects: List<SkyObject>,
    location: ObserverLocation,
    startEpochMillis: Long,
    /** True once the run has a position to come back to -- Back from guiding lands here, and
     *  restarting the night from the first object because the button only knew how to say "Start"
     *  would silently throw that position away. */
    isResuming: Boolean,
    onStart: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Tonight's Objects") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (objects.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No objects match your criteria tonight -- try adjusting Options.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Scaffold
            }

            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                items(objects, key = { it.id }) { obj ->
                    val altitudeDegrees = obj.currentHorizontal(location, startEpochMillis).altitude.degrees
                    ListItem(
                        headlineContent = { Text(obj.displayName) },
                        supportingContent = {
                            Text(
                                if (obj.magnitude.isNaN()) {
                                    "${altitudeDegrees.toInt()}° above horizon"
                                } else {
                                    "mag ${formatMagnitude(obj.magnitude)} · ${altitudeDegrees.toInt()}° above horizon"
                                }
                            )
                        },
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                Button(onClick = onStart) { Text(if (isResuming) "Resume" else "Start") }
            }
        }
    }
}

private fun formatMagnitude(value: Float): String = (kotlin.math.round(value * 10) / 10).toString()
