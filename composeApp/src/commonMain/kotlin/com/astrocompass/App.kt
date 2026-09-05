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
import com.astrocompass.alignment.AlignmentType
import com.astrocompass.astro.time.currentEpochMillis
import com.astrocompass.catalog.SearchCategory
import com.astrocompass.catalog.SkyObject
import com.astrocompass.guiding.ReferenceOrigin
import com.astrocompass.guiding.currentHorizontal
import com.astrocompass.ui.BackHandler
import com.astrocompass.ui.components.AppMenuActions
import com.astrocompass.ui.screens.AlignmentScreen
import com.astrocompass.ui.screens.AlignmentSession
import com.astrocompass.ui.screens.GuidanceScreen
import com.astrocompass.ui.screens.MapScreen
import com.astrocompass.ui.screens.NightWizardListScreen
import com.astrocompass.ui.screens.NightWizardOptionsScreen
import com.astrocompass.ui.screens.SearchScreen
import com.astrocompass.ui.screens.SettingsScreen
import com.astrocompass.ui.screens.TelescopeScreen
import com.astrocompass.telescope.TelescopeConnectionState
import com.astrocompass.telescope.TelescopeEndpoint
import com.astrocompass.telescope.TelescopeTransportKind
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
        var showTelescope by remember { mutableStateOf(false) }

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
        // True while the Options step was reached *from* an in-progress run, so Back returns to it
        // instead of cancelling. Options reached straight from MapScreen leaves this false, where
        // Back does cancel -- there is nothing yet to return to.
        var resumeWizardAfterOptions by remember { mutableStateOf(false) }

        // Hoisted here (rather than remembered inside MapScreen/SearchScreen) so the browse map's
        // position and the search screen's query/filter survive trips into Guidance/Alignment/
        // Settings/Search and back -- App.kt's `when` below tears down whichever screen composable
        // isn't currently selected.
        // Hoisted for the same reason, plus one of its own: an armed mount alignment is state on the
        // *mount*, and forgetting it here would offer to re-arm -- re-homing a mount mid-run. See
        // AlignmentSession.
        val alignmentSession = remember { AlignmentSession() }

        var searchViewport by remember { mutableStateOf(SkyMapViewport.DEFAULT) }
        var searchQuery by remember { mutableStateOf("") }
        var searchCategory by remember { mutableStateOf<SearchCategory?>(null) }

        val resolvedLocation by container.locationResolver.resolved.collectAsState()
        val magnitudeLimit by container.preferences.magnitudeLimit.collectAsState()
        val toleranceDegrees by container.preferences.onTargetToleranceDegrees.collectAsState()
        val showObjectImages by container.preferences.showObjectImages.collectAsState()
        val dimBelowHorizon by container.preferences.dimBelowHorizon.collectAsState()
        val mapObjectFilter by container.preferences.mapObjectFilter.collectAsState()
        val nightWizardObjectFilter by container.preferences.nightWizardObjectFilter.collectAsState()
        val nightWizardMagnitudeLimit by container.preferences.nightWizardMagnitudeLimit.collectAsState()
        val nightWizardMinAltitudeDegrees by container.preferences.nightWizardMinAltitudeDegrees.collectAsState()
        // Distinct from PointingService.isAligned, which is also true under the compass fallback --
        // MapScreen's "Aligned" button specifically means a real star alignment, not a rough guess.
        val absoluteReferenceState by container.absoluteReference.current.collectAsState()
        val isStarAligned = absoluteReferenceState?.origin == ReferenceOrigin.STAR_ALIGNMENT
        val telescopeConnectionState by container.telescopeConnection.state.collectAsState()
        val isTelescopeConnected = telescopeConnectionState is TelescopeConnectionState.Connected
        val telescopeDirection by container.telescopeSkyDirection.collectAsState()
        val slewRatePreset by container.preferences.slewRatePreset.collectAsState()
        val selectedCameraId by container.preferences.selectedCameraId.collectAsState()
        val selectedPhysicalCameraId by container.preferences.selectedPhysicalCameraId.collectAsState()
        val telescopeBoresight by container.preferences.telescopeBoresight.collectAsState()
        val alignmentType by container.preferences.alignmentType.collectAsState()
        val alignmentCompletedAt by container.preferences.alignmentCompletedAtEpochMillis.collectAsState()
        val lastAlignment = alignmentType?.let { type -> alignmentCompletedAt?.let { type to it } }

        val location = resolvedLocation

        // The wizard's steps form a strictly linear stack -- MapScreen, Options, object list,
        // guiding -- and Back walks it in that order and no other. Options is reachable *forward*
        // from guiding's own toolbar as well, which is what resumeWizardAfterOptions exists for;
        // what Back must never do is treat that shortcut as the way back, since every pair of
        // steps that can each reach the other by Back is a loop with no exit to MapScreen.
        val backToWizardList: () -> Unit = {
            isGuiding = false
            nightWizardStarted = false
        }
        // Jumps forward to Options from anywhere further along. The run is left intact, so Back
        // from Options drops straight back into it -- but only from a *started* run, since the
        // object list's own Back already leads here.
        val openWizardOptions: () -> Unit = {
            resumeWizardAfterOptions = nightWizardStarted
            isGuiding = false
            showNightWizardOptions = true
        }
        // Back out of Options into the guiding run it was opened from, on the same object. Only
        // reached with nightWizardStarted set, so isGuiding is unconditionally restored -- leaving
        // it false would fall past every wizard branch in the `when` below to MapScreen.
        val resumeWizard: () -> Unit = {
            showNightWizardOptions = false
            resumeWizardAfterOptions = false
            isGuiding = true
        }
        // Cancel: leaves the wizard entirely, back to MapScreen, discarding the session's start-time
        // override too so a future "Night wizard" tap starts from a fresh computed default.
        //
        // Clears selectedTarget as well, unlike plain "exit guidance" below: the exception that
        // keeps a target marked exists for one the *user* picked, and whatever is selected here is
        // only ever an object the wizard walked them onto. Leaving it would put a marker and a live
        // Goto on MapScreen for something they never chose.
        val cancelWizard: () -> Unit = {
            selectedTarget = null
            isGuiding = false
            showNightWizardOptions = false
            nightWizardObjects = null
            nightWizardStarted = false
            nightWizardStartOverride = null
            resumeWizardAfterOptions = false
        }

        // Exiting guidance keeps selectedTarget marked, so the map's Goto button is still there when
        // the user lands back on it.
        //
        // Search is popped ahead of guidance and the wizard, matching the render `when` below:
        // Search opens *over* whatever screen it was reached from and returns to it, so a back press
        // that closed guidance instead would drop the screen underneath the one being dismissed.
        val goBack: () -> Unit = {
            when {
                showSettings -> showSettings = false
                // One step back up the alignment wizard, leaving the screen only from its first
                // step -- the same walk its toolbar's own Back button does.
                showAlignment -> if (!alignmentSession.stepBack()) showAlignment = false
                showTelescope -> showTelescope = false
                showSearch -> showSearch = false
                showNightWizardOptions -> if (resumeWizardAfterOptions) resumeWizard() else cancelWizard()
                nightWizardObjects != null && !nightWizardStarted -> openWizardOptions()
                isGuiding && nightWizardObjects != null -> backToWizardList()
                isGuiding -> isGuiding = false
            }
        }
        BackHandler(
            enabled = showSettings || showAlignment || showTelescope || showSearch || isGuiding ||
                showNightWizardOptions || (nightWizardObjects != null && !nightWizardStarted),
            onBack = goBack,
        )

        // The app-wide destinations every bottom bar's hamburger menu offers -- one value passed to
        // each screen rather than a handful of callbacks repeated per screen.
        val menuActions = AppMenuActions(
            isStarAligned = isStarAligned,
            onOpenAlignment = { isGuiding = false; showAlignment = true },
            onOpenNightWizard = { showNightWizardOptions = true },
            onOpenSettings = { showSettings = true },
            onOpenTelescope = { showTelescope = true },
            isTelescopeConnected = isTelescopeConnected,
        )

        when {
            showSettings -> SettingsScreen(
                preferences = container.preferences,
                orientationSensor = container.orientationSensor,
                appUpdater = container.appUpdater,
                resolvedLocation = location,
                onBack = goBack,
                modifier = Modifier.fillMaxSize(),
            )

            showAlignment && location != null -> AlignmentScreen(
                session = alignmentSession,
                catalogRepository = container.catalogRepository,
                location = location,
                cameraEnumerator = container.cameraEnumerator,
                telescopeDirection = telescopeDirection,
                mapObjectFilter = mapObjectFilter,
                onMapObjectFilterChange = { container.preferences.setMapObjectFilter(it) },
                onCapturePoint = container::captureAlignmentPoint,
                onSaveModel = { model ->
                    container.saveAlignment(model)
                    container.preferences.setAlignmentCompleted(AlignmentType.SENSORS_ONLY, currentEpochMillis())
                },
                selectedCameraId = selectedCameraId,
                selectedPhysicalCameraId = selectedPhysicalCameraId,
                telescopeBoresight = telescopeBoresight,
                onSaveCameraCalibration = { cameraId, physicalCameraId, boresight ->
                    container.preferences.setSelectedCameraId(cameraId)
                    container.preferences.setSelectedPhysicalCameraId(physicalCameraId)
                    container.preferences.setTelescopeBoresight(boresight)
                    container.preferences.setAlignmentCompleted(AlignmentType.PLATE_SOLVE, currentEpochMillis())
                },
                onSelectTelescopeAxis = { container.preferences.setTelescopeAxis(it) },
                lastAlignment = lastAlignment,
                nowEpochMillis = currentEpochMillis(),
                onGoto = { star -> container.slewTelescopeTo(star, currentEpochMillis()) },
                onBeginMountAlignment = { starCount -> container.beginTelescopeAlignment(starCount) },
                onReadAtHome = { container.readTelescopeAtHome() },
                onMoveHome = { container.moveTelescopeHome() },
                onSyncTelescope = { star, capturedAt -> container.syncTelescopeTo(star, capturedAt) },
                onSaveMountAlignmentModel = { container.saveTelescopeAlignmentModel() },
                onPressDirection = { direction -> container.startTelescopeMove(direction) },
                onReleaseDirection = { direction -> container.stopTelescopeMove(direction) },
                onMoveRateChange = { preset -> container.setTelescopeMoveRatePreset(preset) },
                onStopAllMotion = { container.stopAllTelescopeMotion() },
                menu = menuActions,
                onExit = { showAlignment = false },
                modifier = Modifier.fillMaxSize(),
            )

            showTelescope -> {
                TelescopeScreen(
                    connectionState = container.telescopeConnection.state,
                    reportedPosition = container.telescopeConnection.reportedPosition,
                    mountSyncResults = container.telescopeConnection.mountSyncResults,
                    initialTcpHost = container.preferences.telescopeTcpHost.value,
                    initialTcpPort = container.preferences.telescopeTcpPort.value,
                    onConnectTcp = { host, port ->
                        container.preferences.setTelescopeTcpHost(host)
                        container.preferences.setTelescopeTcpPort(port)
                        container.connectTelescope(
                            TelescopeEndpoint(TelescopeTransportKind.TCP, displayName = host, host = host, port = port),
                        )
                    },
                    showBluetoothSection = container.supportsBluetoothTelescope,
                    bondedBluetoothDevices = { container.bondedBluetoothDevices() },
                    onPairNewDevice = { container.pairNewBluetoothDevice() },
                    initialBluetoothAddress = container.preferences.telescopeBluetoothAddress.value,
                    onConnectBluetooth = { address, name ->
                        container.preferences.setTelescopeBluetoothAddress(address)
                        container.connectTelescope(
                            TelescopeEndpoint(TelescopeTransportKind.BLUETOOTH_CLASSIC, displayName = name, bluetoothAddress = address),
                        )
                    },
                    onDisconnect = { container.disconnectTelescope() },
                    onBack = goBack,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Matched ahead of guidance and the wizard: Search opens *over* whichever screen
            // launched it and its result lands back on that same screen, so selecting a result only
            // marks the target and closes Search -- the `when` then falls through to whatever was
            // underneath, guidance included (which re-reads selectedTarget every composition).
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
                // A recomputed candidate list restarts the run at the list-preview step, so
                // whatever it replaced is no longer something Back could return to.
                onNext = { candidates, startEpochMillis ->
                    nightWizardObjects = candidates
                    nightWizardStartEpochMillis = startEpochMillis
                    nightWizardIndex = 0
                    nightWizardStarted = false
                    resumeWizardAfterOptions = false
                    showNightWizardOptions = false
                },
                onBack = goBack,
                modifier = Modifier.fillMaxSize(),
            )

            nightWizardObjects != null && !nightWizardStarted && location != null -> NightWizardListScreen(
                objects = nightWizardObjects!!,
                location = location,
                startEpochMillis = nightWizardStartEpochMillis,
                isResuming = nightWizardIndex > 0,
                // Deliberately does not reset nightWizardIndex: a fresh candidate list already
                // arrives at 0 (see Options' onNext), so leaving it alone is what lets Back out of
                // guiding and back in again continue the night instead of restarting it.
                onStart = {
                    nightWizardStarted = true
                    val target = nightWizardObjects!![nightWizardIndex]
                    selectedTarget = target
                    container.preferences.setLastTargetId(target.id)
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
                    onSelectTarget = { newTarget ->
                        selectedTarget = newTarget
                        container.preferences.setLastTargetId(newTarget.id)
                    },
                    pointingSource = container.pointingService,
                    telescopeDirection = telescopeDirection,
                    absoluteReference = container.absoluteReference.current,
                    location = location,
                    catalogRepository = container.catalogRepository,
                    onTargetToleranceDegrees = toleranceDegrees,
                    onAutoPlateSolveActive = { active -> container.setAutoPlateSolveActive(active) },
                    menu = menuActions,
                    onOpenSearch = { showSearch = true },
                    onExitGuiding = { if (wizardObjects != null) cancelWizard() else isGuiding = false },
                    onGoto = { container.slewTelescopeTo(target, currentEpochMillis()) },
                    onAbortSlew = { container.abortTelescopeSlew() },
                    onMoveHome = { container.moveTelescopeHome() },
                    onDisconnectTelescope = { container.disconnectTelescope() },
                    onPressDirection = { direction -> container.startTelescopeMove(direction) },
                    onReleaseDirection = { direction -> container.stopTelescopeMove(direction) },
                    onMoveRateChange = { preset -> container.setTelescopeMoveRatePreset(preset) },
                    onStopAllMotion = { container.stopAllTelescopeMotion() },
                    slewRatePreset = slewRatePreset,
                    onSlewRatePresetChange = { container.setSlewRatePreset(it) },
                    onReadTracking = { container.readTelescopeTracking() },
                    onSetTracking = { container.setTelescopeTracking(it) },
                    showObjectPhotos = showObjectImages,
                    dimBelowHorizon = dimBelowHorizon,
                    mapObjectFilter = mapObjectFilter,
                    onMapObjectFilterChange = { container.preferences.setMapObjectFilter(it) },
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
                    onBackToObjectList = backToWizardList,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> MapScreen(
                catalogRepository = container.catalogRepository,
                location = location,
                pointingSource = container.pointingService,
                telescopeDirection = telescopeDirection,
                menu = menuActions,
                selectedTarget = selectedTarget,
                onSelectTarget = { target ->
                    selectedTarget = target
                    container.preferences.setLastTargetId(target.id)
                },
                onGoto = { isGuiding = true },
                viewport = searchViewport,
                onViewportChange = { searchViewport = it },
                showObjectPhotos = showObjectImages,
                dimBelowHorizon = dimBelowHorizon,
                mapObjectFilter = mapObjectFilter,
                onMapObjectFilterChange = { container.preferences.setMapObjectFilter(it) },
                onOpenSearch = { showSearch = true },
                onExitApp = onExitApp,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
