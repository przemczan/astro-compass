@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.coords.HorizontalCoordinates
import com.astrocompass.astro.time.currentEpochMillis
import com.astrocompass.catalog.CatalogRepository
import com.astrocompass.catalog.MapObjectFilter
import com.astrocompass.catalog.SkyObject
import com.astrocompass.guiding.SkyPointingSource
import com.astrocompass.guiding.currentHorizontal
import com.astrocompass.location.ObserverLocation
import com.astrocompass.ui.BackHandler
import com.astrocompass.ui.components.MAP_ZOOM_STEP_FACTOR
import com.astrocompass.ui.components.AppBottomBar
import com.astrocompass.ui.components.AppMenuActions
import com.astrocompass.ui.components.MapFilterSheet
import com.astrocompass.ui.components.MapFollowZoomControls
import com.astrocompass.ui.components.SkyMap
import com.astrocompass.ui.components.SkyMapMarker
import com.astrocompass.ui.components.ToolbarActionButton
import com.astrocompass.ui.components.mapOverlayScrim
import com.astrocompass.ui.components.rememberSkyMapSnapshot
import com.astrocompass.ui.skymap.SkyMapViewport
import com.astrocompass.ui.theme.WarningAmber
import kotlinx.coroutines.launch

/** How long a second back press has to follow the first to actually exit, rather than just
 *  re-showing the "press back again" message -- long enough for a deliberate double press,
 *  short enough that it doesn't feel like the first press did nothing. */
private const val DOUBLE_BACK_TO_EXIT_WINDOW_MILLIS = 2_000L

/** The main/home screen: a full-sky browse map plus whatever's currently marked. Search lives on
 *  its own screen ([SearchScreen]) reached from the bottom toolbar -- this screen shows the
 *  same catalog [SkyMap]'s own zoom-driven reveal curves would show anywhere else (see
 *  [rememberSkyMapSnapshot]), never a query- or category-narrowed subset.
 *
 *  This is also the app's back-navigation root: [BackHandler] here only fires when nothing else
 *  (Settings/Alignment/Search/Guidance) is showing, since `App.kt`'s own `BackHandler` claims the
 *  back press for inter-screen navigation whenever one of those is up. A single press here shows a
 *  "press again to exit" [SnackbarHost] rather than exiting immediately, so a back press meant to
 *  dismiss something else (a keyboard, a sheet) can't accidentally quit the app instead. */
@Composable
fun MapScreen(
    catalogRepository: CatalogRepository,
    location: ObserverLocation?,
    pointingSource: SkyPointingSource,
    /** Marked in blue while a connected mount is reporting -- see
     *  [com.astrocompass.AppContainer.telescopeSkyDirection]. */
    telescopeDirection: Vector3?,
    menu: AppMenuActions,
    selectedTarget: SkyObject?,
    onSelectTarget: (SkyObject) -> Unit,
    onGoto: () -> Unit,
    viewport: SkyMapViewport,
    onViewportChange: (SkyMapViewport) -> Unit,
    showObjectPhotos: Boolean,
    dimBelowHorizon: Boolean,
    mapObjectFilter: MapObjectFilter,
    onMapObjectFilterChange: (MapObjectFilter) -> Unit,
    onOpenSearch: () -> Unit,
    onExitApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showFilterSheet by remember { mutableStateOf(false) }

    var lastBackPressEpochMillis by remember { mutableStateOf(0L) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    BackHandler(enabled = true) {
        val now = currentEpochMillis()
        if (now - lastBackPressEpochMillis <= DOUBLE_BACK_TO_EXIT_WINDOW_MILLIS) {
            onExitApp()
        } else {
            lastBackPressEpochMillis = now
            coroutineScope.launch { snackbarHostState.showSnackbar("Press back again to exit") }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Find an object") }) },
        bottomBar = {
            AppBottomBar(menu) {
                ToolbarActionButton(icon = Icons.Default.Search, label = "Search", onClick = onOpenSearch)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (location == null) {
            LocationRequiredPrompt(menu.onOpenSettings, Modifier.padding(padding))
            return@Scaffold
        }

        val isLoaded by catalogRepository.isLoaded.collectAsState()
        if (!isLoaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val snapshot = rememberSkyMapSnapshot(
            catalogRepository, location,
            filterKey = mapObjectFilter,
            catalogFilter = mapObjectFilter::matches,
        )
        val now = snapshot.nowEpochMillis

        // Off by default -- unlike Guidance, this is a browse map, so following the phone's
        // pointing isn't the reason someone opened it. Same recenter-on-tick pattern as
        // GuidanceScreen's follow effect otherwise, just gated behind an opt-in toggle.
        val currentPointing by pointingSource.currentSkyDirection.collectAsState()
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
                    directions = snapshot.directions,
                    viewport = viewport,
                    onViewportChange = onViewportChange,
                    onManualInteraction = { followPointing = false },
                    constellationLines = snapshot.constellationLines,
                    northOffsetDirections = snapshot.northOffsetDirections,
                    showObjectPhotos = showObjectPhotos,
                    dimBelowHorizon = dimBelowHorizon,
                    markers = listOfNotNull(
                        selectedTarget?.let { target ->
                            SkyMapMarker(
                                direction = target.currentHorizontal(location, now).toEnu(),
                                color = MaterialTheme.colorScheme.primary,
                                label = target.displayName,
                            )
                        },
                        currentPointing?.let { direction ->
                            SkyMapMarker(
                                direction = direction,
                                color = MaterialTheme.colorScheme.secondary,
                                label = "Phone",
                                labelAbove = true,
                            )
                        },
                        telescopeDirection?.let(SkyMapMarker::telescope),
                    ),
                    onSelect = { obj -> onSelectTarget(obj) },
                    modifier = Modifier.fillMaxSize(),
                )
                MapFollowZoomControls(
                    isFollowing = followPointing,
                    onEnableFollow = { followPointing = true },
                    onZoomIn = { onViewportChange(viewport.zoomedBy(MAP_ZOOM_STEP_FACTOR)) },
                    onZoomOut = { onViewportChange(viewport.zoomedBy(1f / MAP_ZOOM_STEP_FACTOR)) },
                    onOpenFilter = { showFilterSheet = true },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
                )
                if (!menu.isStarAligned) {
                    NotCalibratedBanner(
                        onCalibrate = menu.onOpenAlignment,
                        // End padding clears the control cluster sharing this corner of the map.
                        modifier = Modifier.align(Alignment.TopStart).padding(top = 8.dp, start = 8.dp, end = 64.dp),
                    )
                }
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

    if (showFilterSheet) {
        MapFilterSheet(
            filter = mapObjectFilter,
            onFilterChange = onMapObjectFilterChange,
            onDismiss = { showFilterSheet = false },
        )
    }
}

/** Shown on the map until a real calibration exists. Deliberately a nudge and not a wall: pointing
 *  still works off the compass fallback (see [com.astrocompass.alignment.CompassAlignment]), it is
 *  just rough -- which the menu's own badge alone was too easy to miss. */
@Composable
private fun NotCalibratedBanner(onCalibrate: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.mapOverlayScrim().clickable(onClick = onCalibrate).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Explore,
            contentDescription = null,
            tint = WarningAmber,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column {
            Text("Not calibrated", color = WarningAmber, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Pointing is rough — tap to calibrate.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
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
