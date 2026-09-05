@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astrocompass.catalog.CatalogRepository
import com.astrocompass.catalog.CatalogSearch
import com.astrocompass.catalog.SearchCategory
import com.astrocompass.catalog.SkyObject
import com.astrocompass.catalog.searchDisplayLabel

private const val MAGNITUDE_LIMIT_MIN = 1f
private const val MAGNITUDE_LIMIT_MAX = 16f
private const val MAGNITUDE_LIMIT_STEP = 0.5f

/** Search, reached from [MapScreen]'s top-bar search icon -- no map here, just the query/filter
 *  controls and a results list. Picking a result hands it back to the caller (which marks it and
 *  centers the map on it, see `App.kt`'s `onSelectResult`) and this screen closes itself.
 *  [magnitudeLimit] lives here (not Settings) since search results are the only thing it affects --
 *  see [com.astrocompass.catalog.MapObjectFilter] for the map's own, separate filtering. */
@Composable
fun SearchScreen(
    catalogRepository: CatalogRepository,
    magnitudeLimit: Float,
    onMagnitudeLimitChange: (Float) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    category: SearchCategory?,
    onCategoryChange: (SearchCategory?) -> Unit,
    onSelectResult: (SkyObject) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        val isLoaded by catalogRepository.isLoaded.collectAsState()

        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("M31, Vega, Jupiter...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
            )

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    "Results mag ${formatMagnitude(magnitudeLimit)} or brighter",
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

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    SearchCategory.STAR to "Star",
                    SearchCategory.PLANET to "Planet",
                    SearchCategory.GALAXY to "Galaxy",
                    SearchCategory.NEBULA to "Nebula",
                    SearchCategory.CLUSTER to "Cluster",
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = category == value,
                        onClick = { onCategoryChange(if (category == value) null else value) },
                        label = { Text(label) },
                    )
                }
            }

            if (!isLoaded) {
                Box(Modifier.fillMaxSize().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Scaffold
            }

            val results = remember(query, category, isLoaded, magnitudeLimit) {
                if (query.isBlank()) emptyList()
                else CatalogSearch.search(query, catalogRepository.all, magnitudeLimit, category)
            }

            when {
                query.isBlank() -> SearchHint("Type a name to search the catalog.")
                results.isEmpty() -> SearchHint("No matches for \"$query\".")
                else -> LazyColumn(Modifier.padding(top = 8.dp)) {
                    items(results, key = { it.id }) { obj ->
                        ListItem(
                            headlineContent = { Text(obj.searchDisplayLabel()) },
                            supportingContent = if (obj.magnitude.isNaN()) null else {
                                { Text("mag ${formatMagnitude(obj.magnitude)}") }
                            },
                            modifier = Modifier.clickable { onSelectResult(obj) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatMagnitude(value: Float): String = (kotlin.math.round(value * 10) / 10).toString()
