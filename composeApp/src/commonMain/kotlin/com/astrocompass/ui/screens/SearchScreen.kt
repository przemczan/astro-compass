@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.astrocompass.astro.coords.HorizontalCoordinates
import com.astrocompass.astro.time.currentEpochMillis
import com.astrocompass.catalog.CatalogRepository
import com.astrocompass.catalog.CatalogSearch
import com.astrocompass.catalog.SearchCategory
import com.astrocompass.catalog.SkyObject
import com.astrocompass.guiding.PointingService
import com.astrocompass.guiding.currentHorizontal
import com.astrocompass.location.ObserverLocation
import com.astrocompass.ui.components.MAP_ZOOM_STEP_FACTOR
import com.astrocompass.ui.components.MapFollowZoomControls
import com.astrocompass.ui.components.SkyMap
import com.astrocompass.ui.components.SkyMapMarker
import com.astrocompass.ui.components.ToolbarActionButton
import com.astrocompass.ui.skymap.SkyMapDirectionCache
import com.astrocompass.ui.skymap.SkyMapViewport
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(
    catalogRepository: CatalogRepository,
    location: ObserverLocation?,
    magnitudeLimit: Float,
    pointingService: PointingService,
    selectedTarget: SkyObject?,
    onSelectTarget: (SkyObject) -> Unit,
    onGoto: () -> Unit,
    viewport: SkyMapViewport,
    onViewportChange: (SkyMapViewport) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    category: SearchCategory?,
    onCategoryChange: (SearchCategory?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAlignment: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Find an object") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                Spacer(Modifier.weight(1f))
                ToolbarActionButton(icon = Icons.Default.Explore, label = "Align", onClick = onOpenAlignment)
                Spacer(Modifier.weight(1f))
            }
        },
    ) { padding ->
        if (location == null) {
            LocationRequiredPrompt(onOpenSettings, Modifier.padding(padding))
            return@Scaffold
        }

        val isLoaded by catalogRepository.isLoaded.collectAsState()
        if (!isLoaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        var now by remember { mutableStateOf(currentEpochMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(30_000)
                now = currentEpochMillis()
            }
        }

        val results = remember(query, category, isLoaded, magnitudeLimit) {
            if (query.isBlank()) emptyList()
            else CatalogSearch.search(query, catalogRepository.all, magnitudeLimit, category)
        }

        // The map is a browse mode, not just a different rendering of search results -- with no
        // query typed it shows the whole (category/magnitude-filtered) sky rather than nothing,
        // the way the list does. A typed query narrows the map to the same matches as the list.
        val mapObjects = remember(results, query, category, isLoaded, magnitudeLimit) {
            if (query.isBlank()) {
                catalogRepository.all.filter {
                    (category == null || it.searchCategory == category) && (it.magnitude.isNaN() || it.magnitude <= magnitudeLimit)
                }
            } else {
                results
            }
        }
        val mapDirections = remember(mapObjects, now) { SkyMapDirectionCache.build(mapObjects, location, now) }
        val mapConstellationLines = remember(isLoaded, now) {
            if (isLoaded) SkyMapDirectionCache.buildConstellationDirections(catalogRepository.constellationLines, location, now) else emptyList()
        }

        // With the list gone, the map is the only way to see a search match -- without this, a
        // query whose result sits outside the current view would show nothing at all instead of
        // just narrowing what's plotted. Re-centers (keeping the current zoom) on the best match
        // whenever the result set's content actually changes; browsing with no query never
        // triggers it, since results is empty then. Keyed on the best match's id rather than
        // `results` itself -- `results` is a fresh list instance every recomposition (including
        // re-entering this screen after a trip to Guidance/Alignment/Settings), so keying on the
        // list would re-fire this and fight the just-restored viewport position on every return.
        LaunchedEffect(results.firstOrNull()?.id) {
            val bestMatch = results.firstOrNull() ?: return@LaunchedEffect
            val horizontal = bestMatch.currentHorizontal(location, now)
            onViewportChange(viewport.copy(centerAzimuth = horizontal.azimuth, centerAltitude = horizontal.altitude))
        }

        // Off by default -- unlike Guidance, this is a browse map, so following the phone's
        // pointing isn't the reason someone opened it. Same recenter-on-tick pattern as
        // GuidanceScreen's follow effect otherwise, just gated behind an opt-in toggle.
        val currentPointing by pointingService.currentSkyDirection.collectAsState()
        var followPointing by remember { mutableStateOf(false) }
        LaunchedEffect(currentPointing, followPointing) {
            val pointing = currentPointing
            if (followPointing && pointing != null) {
                val horizontal = HorizontalCoordinates.fromEnu(pointing)
                onViewportChange(viewport.copy(centerAzimuth = horizontal.azimuth, centerAltitude = horizontal.altitude))
            }
        }

        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("M31, Vega, Jupiter...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
            )

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

            Box(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
                SkyMap(
                    directions = mapDirections,
                    viewport = viewport,
                    onViewportChange = onViewportChange,
                    onManualInteraction = { followPointing = false },
                    constellationLines = mapConstellationLines,
                    markers = listOfNotNull(selectedTarget?.let { target ->
                        SkyMapMarker(
                            direction = target.currentHorizontal(location, now).toEnu(),
                            color = MaterialTheme.colorScheme.primary,
                            label = target.displayName,
                        )
                    }),
                    onSelect = { obj -> onSelectTarget(obj) },
                    modifier = Modifier.fillMaxSize(),
                )
                MapFollowZoomControls(
                    isFollowing = followPointing,
                    onEnableFollow = { followPointing = true },
                    onZoomIn = { onViewportChange(viewport.zoomedBy(MAP_ZOOM_STEP_FACTOR)) },
                    onZoomOut = { onViewportChange(viewport.zoomedBy(1f / MAP_ZOOM_STEP_FACTOR)) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
                )
                if (selectedTarget != null) {
                    Row(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Button(onClick = onGoto) { Text("Goto ${selectedTarget.displayName}") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationRequiredPrompt(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.padding(bottom = 16.dp))
        Text("Location needed", style = MaterialTheme.typography.titleLarge)
        Text(
            "Every altitude and azimuth in this app depends on knowing where you are. Set your location to continue.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Button(onClick = onOpenSettings) { Text("Set location") }
        }
    }
}
