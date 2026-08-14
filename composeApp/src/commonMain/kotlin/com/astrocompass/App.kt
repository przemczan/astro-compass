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
import com.astrocompass.guiding.ReferenceOrigin
import com.astrocompass.guiding.currentHorizontal
import com.astrocompass.ui.BackHandler
import com.astrocompass.ui.screens.AlignmentScreen
import com.astrocompass.ui.screens.GuidanceScreen
import com.astrocompass.ui.screens.MapScreen
import com.astrocompass.ui.screens.NightWizardListScreen
import com.astrocompass.ui.screens.NightWizardOptionsScreen
import com.astrocompass.ui.screens.SearchScreen
import com.astrocompass.ui.screens.SettingsScreen
import com.astrocompass.ui.skymap.SkyMapViewport
import com.astrocompass.ui.theme.AppTheme
import com.astrocompass.ui.theme.GuiderTheme

@Composable
fun GuiderApp(container: AppContainer, onExitApp: () -> Unit = {}) {
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

        // Night Wizard: nightWizardObjects is the fixed candidate snapshot computed once by
        // NightWizardOptionsScreen's "Next" -- both the list-preview screen and the guide screen's
        // Prev/Next just walk this same list, never re-filtering. !nightWizardStarted means we're
        // still on the list-preview step; nightWizardStarted means isGuiding is showing it.
        var showNightWizardOptions by remember { mutableStateOf(false) }
        var nightWizardObjects by remember { mutableStateOf<List<SkyObject>?>(null) }
        var nightWizardStartEpochMillis by remember { mutableStateOf(0L) }
        var nightWizardIndex by remember { mutableStateOf(0) }
        var nightWizardStarted by remember { mutableStateOf(false) }
        // The user's manual override of the wizard's start time, session-only (not persisted) --
        // null means "use the computed nautical-twilight default".
        var nightWizardStartOverride by remember { mutableStateOf<Long?>(null) }

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
        val mapObjectFilter by container.preferences.mapObjectFilter.collectAsState()
        val nightWizardObjectFilter by container.preferences.nightWizardObjectFilter.collectAsState()
        val nightWizardMagnitudeLimit by container.preferences.nightWizardMagnitudeLimit.collectAsState()
        val nightWizardMinAltitudeDegrees by container.preferences.nightWizardMinAltitudeDegrees.collectAsState()
        // Distinct from PointingService.isAligned, which is also true under the compass fallback --
        // MapScreen's "Aligned" button specifically means a real star alignment, not a rough guess.
        val absoluteReferenceState by container.absoluteReference.current.collectAsState()
        val isStarAligned = absoluteReferenceState?.origin == ReferenceOrigin.STAR_ALIGNMENT

        val location = resolvedLocation

        // Returns to the wizard's Options step from anywhere further along (list preview or
        // guiding) -- always clearing the candidate snapshot so Options is unambiguously "step 1"
        // again: without this, a second back press from Options would fall through to
        // `nightWizardObjects != null` and bounce back into the list instead of reaching MapScreen.
        val openWizardOptions: () -> Unit = {
            isGuiding = false
            nightWizardStarted = false
            nightWizardObjects = null
            showNightWizardOptions = true
        }
        // Cancel: leaves the wizard entirely, back to MapScreen, discarding the session's start-time
        // override too so a future "Night wizard" tap starts from a fresh computed default.
        val cancelWizard: () -> Unit = {
            isGuiding = false
            showNightWizardOptions = false
            nightWizardObjects = null
            nightWizardStarted = false
            nightWizardStartOverride = null
        }

        // Exiting guidance keeps selectedTarget marked, so the Search screen's Goto button is
        // still there when the user lands back on it.
        val goBack: () -> Unit = {
            when {
                showSettings -> showSettings = false
                showAlignment -> showAlignment = false
                showNightWizardOptions -> cancelWizard()
                nightWizardObjects != null && !nightWizardStarted -> openWizardOptions()
                isGuiding && nightWizardObjects != null -> openWizardOptions()
                isGuiding -> isGuiding = false
                showSearch -> showSearch = false
            }
        }
        BackHandler(
            enabled = showSettings || showAlignment || showSearch || isGuiding ||
                showNightWizardOptions || (nightWizardObjects != null && !nightWizardStarted),
            onBack = goBack,
        )

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

            showNightWizardOptions && location != null -> NightWizardOptionsScreen(
                catalogRepository = container.catalogRepository,
                location = location,
                filter = nightWizardObjectFilter,
                onFilterChange = { container.preferences.setNightWizardObjectFilter(it) },
                magnitudeLimit = nightWizardMagnitudeLimit,
                onMagnitudeLimitChange = { container.preferences.setNightWizardMagnitudeLimit(it) },
                minAltitudeDegrees = nightWizardMinAltitudeDegrees,
                onMinAltitudeDegreesChange = { container.preferences.setNightWizardMinAltitudeDegrees(it) },
                startEpochMillis = nightWizardStartOverride,
                onStartEpochMillisChange = { nightWizardStartOverride = it },
                onNext = { candidates, startEpochMillis ->
                    nightWizardObjects = candidates
                    nightWizardStartEpochMillis = startEpochMillis
                    nightWizardIndex = 0
                    nightWizardStarted = false
                    showNightWizardOptions = false
                },
                onBack = goBack,
                modifier = Modifier.fillMaxSize(),
            )

            nightWizardObjects != null && !nightWizardStarted && location != null -> NightWizardListScreen(
                objects = nightWizardObjects!!,
                location = location,
                startEpochMillis = nightWizardStartEpochMillis,
                onStart = {
                    nightWizardStarted = true
                    nightWizardIndex = 0
                    val firstTarget = nightWizardObjects!!.first()
                    selectedTarget = firstTarget
                    container.preferences.setLastTargetId(firstTarget.id)
                    isGuiding = true
                },
                onBack = goBack,
                modifier = Modifier.fillMaxSize(),
            )

            isGuiding && location != null && (selectedTarget != null || nightWizardObjects != null) -> {
                val wizardObjects = nightWizardObjects
                val target = wizardObjects?.getOrNull(nightWizardIndex) ?: selectedTarget!!
                GuidanceScreen(
                    target = target,
                    pointingService = container.pointingService,
                    absoluteReference = container.absoluteReference.current,
                    location = location,
                    catalogRepository = container.catalogRepository,
                    onTargetToleranceDegrees = toleranceDegrees,
                    onSyncOnThisObject = { container.syncOnObject(target, currentEpochMillis()) },
                    onPlateSolve = { container.attemptPlateSolve() },
                    onApplyPlateSolve = { attempt -> container.applyPlateSolve(attempt) },
                    onOpenAlignment = { isGuiding = false; showAlignment = true },
                    onOpenSettings = { showSettings = true },
                    onExitGuiding = { if (wizardObjects != null) cancelWizard() else isGuiding = false },
                    showObjectPhotos = showObjectImages,
                    mapObjectFilter = mapObjectFilter,
                    wizardProgress = wizardObjects?.let { (nightWizardIndex + 1) to it.size },
                    onNextObject = {
                        if (wizardObjects != null) {
                            nightWizardIndex = (nightWizardIndex + 1).coerceAtMost(wizardObjects.size - 1)
                            val newTarget = wizardObjects[nightWizardIndex]
                            selectedTarget = newTarget
                            container.preferences.setLastTargetId(newTarget.id)
                        }
                    },
                    onPreviousObject = {
                        if (wizardObjects != null) {
                            nightWizardIndex = (nightWizardIndex - 1).coerceAtLeast(0)
                            val newTarget = wizardObjects[nightWizardIndex]
                            selectedTarget = newTarget
                            container.preferences.setLastTargetId(newTarget.id)
                        }
                    },
                    onOpenWizardOptions = openWizardOptions,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            showSearch -> SearchScreen(
                catalogRepository = container.catalogRepository,
                magnitudeLimit = magnitudeLimit,
                onMagnitudeLimitChange = { container.preferences.setMagnitudeLimit(it) },
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
                pointingService = container.pointingService,
                isStarAligned = isStarAligned,
                selectedTarget = selectedTarget,
                onSelectTarget = { target ->
                    selectedTarget = target
                    container.preferences.setLastTargetId(target.id)
                },
                onGoto = { isGuiding = true },
                viewport = searchViewport,
                onViewportChange = { searchViewport = it },
                showObjectPhotos = showObjectImages,
                mapObjectFilter = mapObjectFilter,
                onMapObjectFilterChange = { container.preferences.setMapObjectFilter(it) },
                onOpenSearch = { showSearch = true },
                onOpenSettings = { showSettings = true },
                onOpenAlignment = { showAlignment = true },
                onOpenNightWizard = { showNightWizardOptions = true },
                // A confirmed exit clears any star alignment first -- a stale one from a previous
                // session (potentially a different, unknown mounting) is worse than none at all;
                // the compass fallback re-engages automatically, same as after a fresh install.
                onExitApp = { container.clearAlignment(); onExitApp() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
