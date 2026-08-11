@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.astrocompass.astro.coords.HorizontalCoordinates
import com.astrocompass.astro.time.currentEpochMillis
import com.astrocompass.catalog.CatalogRepository
import com.astrocompass.catalog.DeepSkyObject
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

/** The main/home screen: a full-sky browse map plus whatever's currently marked. Search lives on
 *  its own screen ([SearchScreen]) reached via the top-bar search icon -- this screen only ever
 *  shows the magnitude-filtered catalog, never a query- or category-narrowed subset. */
@Composable
fun MapScreen(
    catalogRepository: CatalogRepository,
    location: ObserverLocation?,
    magnitudeLimit: Float,
    pointingService: PointingService,
    selectedTarget: SkyObject?,
    onSelectTarget: (SkyObject) -> Unit,
    onGoto: () -> Unit,
    viewport: SkyMapViewport,
    onViewportChange: (SkyMapViewport) -> Unit,
    showObjectPhotos: Boolean,
    onOpenSearch: () -> Unit,
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
                    IconButton(onClick = onOpenSearch) { Icon(Icons.Default.Search, contentDescription = "Search") }
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

        val mapObjects = remember(isLoaded, magnitudeLimit) {
            catalogRepository.all.filter { it.magnitude.isNaN() || it.magnitude <= magnitudeLimit }
        }
        val mapDirections = remember(mapObjects, now) { SkyMapDirectionCache.build(mapObjects, location, now) }
        val mapConstellationLines = remember(isLoaded, now) {
            if (isLoaded) SkyMapDirectionCache.buildConstellationDirections(catalogRepository.constellationLines, location, now) else emptyList()
        }
        val mapNorthOffsets = remember(mapObjects, now) {
            SkyMapDirectionCache.northOffsetDirections(mapObjects.filterIsInstance<DeepSkyObject>(), location, now)
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
            Box(Modifier.fillMaxWidth().weight(1f)) {
                SkyMap(
                    directions = mapDirections,
                    viewport = viewport,
                    onViewportChange = onViewportChange,
                    onManualInteraction = { followPointing = false },
                    constellationLines = mapConstellationLines,
                    northOffsetDirections = mapNorthOffsets,
                    showObjectPhotos = showObjectPhotos,
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
