package com.astrocompass.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.astrocompass.alignment.AlignmentModel
import com.astrocompass.alignment.AlignmentPoint
import com.astrocompass.alignment.AlignmentSource
import com.astrocompass.alignment.AlignmentType
import com.astrocompass.astro.Vector3
import com.astrocompass.catalog.CatalogRepository
import com.astrocompass.catalog.MapObjectFilter
import com.astrocompass.catalog.SkyObject
import com.astrocompass.guiding.TelescopeAxis
import com.astrocompass.location.ObserverLocation
import com.astrocompass.platesolve.CameraEnumerator
import com.astrocompass.platesolve.TelescopeBoresight
import com.astrocompass.telescope.MoveRatePreset
import com.astrocompass.telescope.SlewOutcome
import com.astrocompass.telescope.SyncOutcome
import com.astrocompass.telescope.TelescopeDirection
import com.astrocompass.ui.components.AppMenuActions
import com.astrocompass.ui.screens.alignment.AlignmentTypeStep
import com.astrocompass.ui.screens.alignment.CameraCalibrationSteps
import com.astrocompass.ui.screens.alignment.StarAlignmentStep

/**
 * The alignment wizard's host: one linear flow that first asks which instrument this setup aligns
 * with, then runs the branch that answer chose. A phone with a usable camera never needs star
 * syncs -- plate solving recovers a complete fit from one photo -- so the two branches share
 * nothing beyond the step they forked from.
 *
 * Every step is a full screen with its own toolbar; this function only routes between them. The
 * step itself lives in [session] (see [AlignmentStep]), not here, so the menu's Settings entry --
 * which tears this screen down -- can't drop the user back at the start of a run in progress.
 */
@Composable
fun AlignmentScreen(
    session: AlignmentSession,
    catalogRepository: CatalogRepository,
    location: ObserverLocation,
    cameraEnumerator: CameraEnumerator,
    /** Marked in blue while a connected mount is reporting -- see
     *  [com.astrocompass.AppContainer.telescopeSkyDirection]. */
    telescopeDirection: Vector3?,
    mapObjectFilter: MapObjectFilter,
    onMapObjectFilterChange: (MapObjectFilter) -> Unit,
    onCapturePoint: (target: SkyObject, source: AlignmentSource, nowEpochMillis: Long) -> AlignmentPoint?,
    onSaveModel: (AlignmentModel) -> Unit,
    selectedCameraId: String?,
    selectedPhysicalCameraId: String?,
    telescopeBoresight: TelescopeBoresight?,
    onSaveCameraCalibration: (cameraId: String?, physicalCameraId: String?, boresight: TelescopeBoresight) -> Unit,
    /** Set from the wizard's own answers about how the phone is mounted, rather than left to
     *  Settings -- see [CameraCalibrationSteps]'s parameter of the same name for the timing. */
    onSelectTelescopeAxis: (TelescopeAxis) -> Unit,
    /** The last completed alignment's kind and time, or null if there has never been one. */
    lastAlignment: Pair<AlignmentType, Long>?,
    nowEpochMillis: Long,
    onGoto: suspend (target: SkyObject) -> SlewOutcome,
    onBeginMountAlignment: suspend (starCount: Int) -> Boolean,
    onReadAtHome: suspend () -> Boolean?,
    onMoveHome: suspend () -> Unit,
    onSyncTelescope: suspend (target: SkyObject, capturedAtEpochMillis: Long) -> SyncOutcome,
    onSaveMountAlignmentModel: suspend () -> Boolean,
    onPressDirection: suspend (TelescopeDirection) -> Unit,
    onReleaseDirection: suspend (TelescopeDirection) -> Unit,
    onMoveRateChange: suspend (MoveRatePreset) -> Unit,
    onStopAllMotion: () -> Unit,
    menu: AppMenuActions,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stepBack: () -> Unit = { if (!session.stepBack()) onExit() }

    when (session.step) {
        AlignmentStep.CHOOSE_TYPE -> AlignmentTypeStep(
            lastAlignment = lastAlignment,
            nowEpochMillis = nowEpochMillis,
            onChoose = { type ->
                when (type) {
                    // A sensors-only setup is always the phone clamped lengthwise along the tube;
                    // only the camera branch has a mirror to ask about (see its CHOOSE_MIRROR step).
                    AlignmentType.SENSORS_ONLY -> {
                        onSelectTelescopeAxis(TelescopeAxis.TOP_EDGE)
                        session.goTo(AlignmentStep.STAR_SYNC)
                    }
                    AlignmentType.PLATE_SOLVE -> session.goTo(AlignmentStep.CHOOSE_MIRROR)
                }
            },
            menu = menu,
            onExit = onExit,
            modifier = modifier,
        )

        AlignmentStep.STAR_SYNC -> StarAlignmentStep(
            session = session,
            catalogRepository = catalogRepository,
            location = location,
            telescopeDirection = telescopeDirection,
            mapObjectFilter = mapObjectFilter,
            onMapObjectFilterChange = onMapObjectFilterChange,
            onCapturePoint = onCapturePoint,
            onSaveModel = onSaveModel,
            onGoto = onGoto,
            onBeginMountAlignment = onBeginMountAlignment,
            onReadAtHome = onReadAtHome,
            onMoveHome = onMoveHome,
            onSyncTelescope = onSyncTelescope,
            onSaveMountAlignmentModel = onSaveMountAlignmentModel,
            onPressDirection = onPressDirection,
            onReleaseDirection = onReleaseDirection,
            onMoveRateChange = onMoveRateChange,
            onStopAllMotion = onStopAllMotion,
            menu = menu,
            onStepBack = stepBack,
            onExit = onExit,
            modifier = modifier,
        )

        AlignmentStep.CHOOSE_MIRROR,
        AlignmentStep.MOUNT_PHONE,
        AlignmentStep.POINT_TELESCOPE,
        AlignmentStep.CENTER_CROSSHAIR,
        AlignmentStep.DONE,
        -> CameraCalibrationSteps(
            session = session,
            cameraEnumerator = cameraEnumerator,
            currentSelectedCameraId = selectedCameraId,
            currentSelectedPhysicalCameraId = selectedPhysicalCameraId,
            currentBoresight = telescopeBoresight,
            onSave = onSaveCameraCalibration,
            onSelectTelescopeAxis = onSelectTelescopeAxis,
            menu = menu,
            onStepBack = stepBack,
            onExit = onExit,
            modifier = modifier,
        )
    }
}
