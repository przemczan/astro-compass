@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astrocompass.catalog.MapObjectCategory
import com.astrocompass.catalog.MapObjectFilter
import kotlin.math.round

/** [MapObjectFilter.maxMagnitude]'s slider bounds. [MAGNITUDE_LIMIT_MAX] matches
 *  [com.astrocompass.ui.skymap.SkyMapScene]'s own hard ceilings for stars (8.5) and DSOs (16.0) --
 *  every object the map could ever draw is already brighter than that, so pinning the slider to
 *  its max end is a true no-op limit, not just a very large number. */
private const val MAGNITUDE_LIMIT_MIN = 0f
private const val MAGNITUDE_LIMIT_MAX = 16f
private const val MAGNITUDE_LIMIT_STEP = 0.5f

/** The map's "Filter" toolbar button opens this -- one toggle per [MapObjectCategory], each
 *  independently showing/hiding that category's objects on every screen sharing
 *  [rememberSkyMapSnapshot]'s filter (currently Map and Guidance; stars are never affected, and
 *  Alignment's map is stars-only regardless). */
@Composable
fun MapFilterSheet(
    filter: MapObjectFilter,
    onFilterChange: (MapObjectFilter) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(
                "Show on map",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(
                    if (filter.maxMagnitude == null) "No brightness limit" else "Objects mag ${formatMagnitude(filter.maxMagnitude)} or brighter",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = filter.maxMagnitude ?: MAGNITUDE_LIMIT_MAX,
                    onValueChange = { value ->
                        val limit = if (value >= MAGNITUDE_LIMIT_MAX) null else value
                        onFilterChange(filter.copy(maxMagnitude = limit))
                    },
                    valueRange = MAGNITUDE_LIMIT_MIN..MAGNITUDE_LIMIT_MAX,
                    steps = ((MAGNITUDE_LIMIT_MAX - MAGNITUDE_LIMIT_MIN) / MAGNITUDE_LIMIT_STEP).toInt() - 1,
                )
            }

            for (category in MapObjectCategory.entries) {
                FilterToggleRow(
                    label = category.label,
                    checked = filter.isShown(category),
                    onCheckedChange = { checked -> onFilterChange(filter.withShown(category, checked)) },
                )
            }
        }
    }
}

private fun formatMagnitude(value: Float): String = (round(value * 10) / 10).toString()

/** Internal (not private) so [com.astrocompass.ui.screens.NightWizardOptionsScreen] can reuse the
 *  same toggle-row look for its own [MapObjectCategory] filter. */
@Composable
internal fun FilterToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
