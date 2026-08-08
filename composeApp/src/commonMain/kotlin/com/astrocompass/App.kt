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
import com.astrocompass.catalog.SkyObject
import com.astrocompass.ui.BackHandler
import com.astrocompass.ui.screens.AlignmentScreen
import com.astrocompass.ui.screens.GuidanceScreen
import com.astrocompass.ui.screens.SearchScreen
import com.astrocompass.ui.screens.SettingsScreen
import com.astrocompass.ui.theme.AppTheme
import com.astrocompass.ui.theme.GuiderTheme

@Composable
fun GuiderApp(container: AppContainer) {
    val themeOverride by container.preferences.appTheme.collectAsState()
    val resolvedTheme = themeOverride ?: if (isSystemInDarkTheme()) AppTheme.Dark else AppTheme.Light

    GuiderTheme(appTheme = resolvedTheme) {
        var selectedTarget by remember { mutableStateOf<SkyObject?>(null) }
        var showGuidance by remember { mutableStateOf(false) }
        var showAlignment by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }

        val resolvedLocation by container.locationResolver.resolved.collectAsState()
        val magnitudeLimit by container.preferences.magnitudeLimit.collectAsState()
        val toleranceDegrees by container.preferences.onTargetToleranceDegrees.collectAsState()

        val location = resolvedLocation

        val goBack: () -> Unit = {
            when {
                showSettings -> showSettings = false
                showAlignment -> showAlignment = false
                showGuidance -> { showGuidance = false; selectedTarget = null }
            }
        }
        BackHandler(enabled = showSettings || showAlignment || showGuidance, onBack = goBack)

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
                modifier = Modifier.fillMaxSize(),
            )

            showGuidance && selectedTarget != null && location != null -> GuidanceScreen(
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
                onOpenAlignment = { showGuidance = false; showAlignment = true },
                onBack = goBack,
                modifier = Modifier.fillMaxSize(),
            )

            else -> SearchScreen(
                catalogRepository = container.catalogRepository,
                location = location,
                magnitudeLimit = magnitudeLimit,
                onSelectTarget = { target ->
                    selectedTarget = target
                    container.preferences.setLastTargetId(target.id)
                    showGuidance = true
                },
                onOpenSettings = { showSettings = true },
                onOpenAlignment = { showAlignment = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
