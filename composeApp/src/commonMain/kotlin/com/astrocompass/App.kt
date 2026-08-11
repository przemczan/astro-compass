package com.astrocompass

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.astrocompass.astro.time.currentEpochMillis
import com.astrocompass.catalog.SearchCategory
import com.astrocompass.catalog.SkyObject
import com.astrocompass.guiding.currentHorizontal
import com.astrocompass.ui.BackHandler
import com.astrocompass.ui.screens.AlignmentScreen
import com.astrocompass.ui.screens.GuidanceScreen
import com.astrocompass.ui.screens.MapScreen
import com.astrocompass.ui.screens.SearchScreen
import com.astrocompass.ui.screens.SettingsScreen
import com.astrocompass.ui.skymap.SkyMapViewport
import com.astrocompass.ui.theme.AppTheme
import com.astrocompass.ui.theme.GuiderTheme

@Composable
fun GuiderApp(container: AppContainer) {
    val themeOverride by container.preferences.appTheme.collectAsState()
    val resolvedTheme = themeOverride ?: if (isSystemInDarkTheme()) AppTheme.Dark else AppTheme.Light

    GuiderTheme(appTheme = resolvedTheme) {
        // Marking a target and guiding on it are independent: selecting a target only marks it
        // (see MapScreen's Goto button); isGuiding tracks whether GuidanceScreen is showing.
        var selectedTarget by remember { mutableStateOf<SkyObject?>(null) }
        var isGuiding by remember { mutableStateOf(false) }
        var showAlignment by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var showSearch by remember { mutableStateOf(false) }

        // Hoisted here (rather than remembered inside MapScreen/SearchScreen) so the browse map's
        // position and the search screen's query/filter survive trips into Guidance/Alignment/
        // Settings/Search and back -- App.kt's `when` below tears down whichever screen composable
        // isn't currently selected.
        var searchViewport by remember { mutableStateOf(SkyMapViewport.DEFAULT) }
        var searchQuery by remember { mutableStateOf("") }
        var searchCategory by remember { mutableStateOf<SearchCategory?>(null) }

        val resolvedLocation by container.locationResolver.resolved.collectAsState()
        val magnitudeLimit by container.preferences.magnitudeLimit.collectAsState()
        val toleranceDegrees by container.preferences.onTargetToleranceDegrees.collectAsState()
        val showObjectImages by container.preferences.showObjectImages.collectAsState()

        val location = resolvedLocation

        // Exiting guidance keeps selectedTarget marked, so the Search screen's Goto button is
        // still there when the user lands back on it.
        val goBack: () -> Unit = {
            when {
                showSettings -> showSettings = false
                showAlignment -> showAlignment = false
                showSearch -> showSearch = false
                isGuiding -> isGuiding = false
            }
        }
        BackHandler(enabled = showSettings || showAlignment || showSearch || isGuiding, onBack = goBack)

        when {
            showSettings -> SettingsScreen(
                preferences = container.preferences,
                orientationSensor = container.orientationSensor,
                resolvedLocation = location,
                onBack = goBack,
                modifier = Modifier.fillMaxSize(),
            )

            showAlignment && location != null -> AlignmentScreen(
                catalogRepository = container.catalogRepository,
                location = location,
                onCapturePoint = container::captureAlignmentPoint,
                onSaveModel = { model -> container.saveAlignment(model) },
                onBack = goBack,
                onOpenSettings = { showSettings = true },
                modifier = Modifier.fillMaxSize(),
            )

            isGuiding && selectedTarget != null && location != null -> GuidanceScreen(
                target = selectedTarget!!,
                pointingService = container.pointingService,
                absoluteReference = container.absoluteReference.current,
                location = location,
                catalogRepository = container.catalogRepository,
                onTargetToleranceDegrees = toleranceDegrees,
                onSyncOnThisObject = {
                    container.syncOnObject(selectedTarget!!, currentEpochMillis())
                },
                onPlateSolve = { container.attemptPlateSolve() },
                onApplyPlateSolve = { attempt -> container.applyPlateSolve(attempt) },
                onOpenAlignment = { isGuiding = false; showAlignment = true },
                onOpenSettings = { showSettings = true },
                onExitGuiding = { isGuiding = false },
                showObjectPhotos = showObjectImages,
                modifier = Modifier.fillMaxSize(),
            )

            showSearch -> SearchScreen(
                catalogRepository = container.catalogRepository,
                magnitudeLimit = magnitudeLimit,
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                category = searchCategory,
                onCategoryChange = { searchCategory = it },
                onSelectResult = { target ->
                    selectedTarget = target
                    container.preferences.setLastTargetId(target.id)
                    if (location != null) {
                        val horizontal = target.currentHorizontal(location, currentEpochMillis())
                        searchViewport = searchViewport.copy(centerAzimuth = horizontal.azimuth, centerAltitude = horizontal.altitude)
                    }
                    showSearch = false
                },
                onBack = goBack,
                modifier = Modifier.fillMaxSize(),
            )

            else -> MapScreen(
                catalogRepository = container.catalogRepository,
                location = location,
                magnitudeLimit = magnitudeLimit,
                pointingService = container.pointingService,
                selectedTarget = selectedTarget,
                onSelectTarget = { target ->
                    selectedTarget = target
                    container.preferences.setLastTargetId(target.id)
                },
                onGoto = { isGuiding = true },
                viewport = searchViewport,
                onViewportChange = { searchViewport = it },
                showObjectPhotos = showObjectImages,
                onOpenSearch = { showSearch = true },
                onOpenSettings = { showSettings = true },
                onOpenAlignment = { showAlignment = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
