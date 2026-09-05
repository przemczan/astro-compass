@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.screens.alignment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.astrocompass.alignment.AlignmentType
import com.astrocompass.ui.components.AppBottomBar
import com.astrocompass.ui.components.AppMenuActions
import com.astrocompass.ui.components.ToolbarCancelButton

/**
 * The wizard's fork: which instrument this setup aligns with. A phone with a usable camera needs no
 * star syncs at all -- plate solving recovers a complete 3-DOF fit on its own -- so the choice
 * decides the whole rest of the flow, and is remembered afterwards because guiding behaves
 * differently under each (see [AlignmentType]).
 *
 * [lastAlignment] is shown, when there is one, so a user who only wanted to check how stale their
 * alignment is can read it and leave without redoing anything.
 */
@Composable
fun AlignmentTypeStep(
    lastAlignment: Pair<AlignmentType, Long>?,
    nowEpochMillis: Long,
    onChoose: (AlignmentType) -> Unit,
    menu: AppMenuActions,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Calibrate") }) },
        bottomBar = { AppBottomBar(menu) { ToolbarCancelButton(onExit) } },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (lastAlignment != null) {
                val (type, completedAt) = lastAlignment
                Text(
                    "Last calibrated ${formatAge(nowEpochMillis - completedAt)} ago · ${type.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
            }
            Text("How do you want to calibrate?", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            ChoiceCard(
                title = AlignmentType.SENSORS_ONLY.label,
                body = "Point the telescope at two or three bright stars and confirm each one. " +
                    "No camera needed.",
                onClick = { onChoose(AlignmentType.SENSORS_ONLY) },
            )
            Spacer(Modifier.height(12.dp))
            ChoiceCard(
                title = AlignmentType.PLATE_SOLVE.label,
                body = "Mount the phone on the telescope and calibrate its camera once. The app " +
                    "then photographs the sky while you guide and keeps itself accurate — no stars " +
                    "to point at.",
                onClick = { onChoose(AlignmentType.PLATE_SOLVE) },
            )
        }
    }
}

/** Coarse on purpose: the only question this answers is "is my alignment from tonight or from last
 *  week", which minutes-then-hours-then-days covers exactly. */
private fun formatAge(ageMillis: Long): String {
    val minutes = ageMillis / 60_000
    return when {
        minutes < 1 -> "less than a minute"
        minutes < 60 -> "$minutes min"
        minutes < 60 * 24 -> "${minutes / 60} h"
        else -> "${minutes / (60 * 24)} d"
    }
}
