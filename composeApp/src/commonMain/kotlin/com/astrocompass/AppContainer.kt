package com.astrocompass

import com.astrocompass.alignment.AlignmentModel
import com.astrocompass.alignment.AlignmentPoint
import com.astrocompass.alignment.AlignmentResult
import com.astrocompass.alignment.AlignmentSolver
import com.astrocompass.alignment.AlignmentSource
import com.astrocompass.alignment.AlignmentStore
import com.astrocompass.alignment.PlateSolveAlignment
import com.astrocompass.astro.Angle
import com.astrocompass.astro.Quaternion
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.coords.CoordinateTransforms
import com.astrocompass.astro.coords.EquatorialCoordinates
import com.astrocompass.astro.coords.HorizontalCoordinates
import com.astrocompass.astro.time.AstroTime
import com.astrocompass.astro.time.currentEpochMillis
import com.astrocompass.catalog.CatalogRepository
import com.astrocompass.catalog.SkyObject
import com.astrocompass.catalog.StarObject
import com.astrocompass.guiding.AbsoluteReference
import com.astrocompass.guiding.AlignmentAbsoluteReference
import com.astrocompass.guiding.CompassAbsoluteReference
import com.astrocompass.guiding.PlateSolveAttempt
import com.astrocompass.guiding.PointingService
import com.astrocompass.guiding.PrioritizedAbsoluteReference
import com.astrocompass.guiding.currentHorizontal
import com.astrocompass.location.LocationProvider
import com.astrocompass.location.LocationResolver
import com.astrocompass.location.MagneticDeclinationProvider
import com.astrocompass.location.ObserverLocation
import com.astrocompass.platesolve.CameraCapture
import com.astrocompass.platesolve.CapturedFrame
import com.astrocompass.platesolve.CentroidDetector
import com.astrocompass.platesolve.PlateSolver
import com.astrocompass.platesolve.ReferenceStar
import com.astrocompass.sensors.OrientationSensor
import com.astrocompass.settings.AppPreferences
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A drift correction, not a from-scratch search -- generous enough to absorb plausible gyro
 *  drift since the last sync, but narrow enough to keep [PlateSolver]'s candidate-pair matching
 *  fast on a phone CPU (see the tuning notes on `PlateSolverCatalogDensityTest`). */
private val PLATE_SOLVE_SEARCH_RADIUS = Angle.ofDegrees(15.0)

/** [PlateSolver]'s matching cost depends heavily on how many real catalog stars fall within
 *  [PLATE_SOLVE_SEARCH_RADIUS] of wherever the app is currently pointed -- that varies a lot by
 *  sky region (dense near the galactic plane, sparse elsewhere), and there's nothing that scales
 *  it down automatically. Without a hard cutoff a dense enough region -- or a scene with no real
 *  stars in it at all, so the search never converges -- can run for many minutes with no
 *  feedback, which reads as a hang rather than a slow failure. */
private const val PLATE_SOLVE_TIMEOUT_MILLIS = 30_000L

/**
 * Owns every long-lived service for the app's lifetime: sensor, location, catalog, preferences,
 * alignment. Built once by the platform entry point (`MainActivity` / `MainViewController`,
 * which supply the platform-specific [OrientationSensor]/[LocationProvider]/[Settings]) and
 * injected into [GuiderApp] -- same dependency-injection-by-constructor shape as
 * lightnet-mobile's `LightnetApp(serviceDiscovery, deviceRepository, httpClient)`, just with
 * more services, so it is grouped into one container instead of one parameter per service.
 */
class AppContainer(
    private val scope: CoroutineScope,
    val orientationSensor: OrientationSensor,
    val locationProvider: LocationProvider,
    val cameraCapture: CameraCapture,
    magneticDeclinationProvider: MagneticDeclinationProvider,
    settings: Settings,
) {
    val preferences = AppPreferences(settings)
    val catalogRepository = CatalogRepository()
    val alignmentStore = AlignmentStore(settings)
    val locationResolver = LocationResolver(scope, locationProvider, preferences.manualLocation)

    // Seeded here rather than in `init`: PrioritizedAbsoluteReference captures its initial value
    // at construction, and a stored alignment must be visible in it from the very first frame --
    // otherwise the Guidance screen can flash "Not aligned" before the flow's first emission.
    private val alignmentReference = AlignmentAbsoluteReference().apply { update(alignmentStore.load()) }
    private val compassReference =
        CompassAbsoluteReference(scope, orientationSensor, locationResolver.resolved, magneticDeclinationProvider)

    /** Star alignment when there is one, rough compass otherwise -- see
     *  [PrioritizedAbsoluteReference]. Screens distinguish the two by
     *  [AbsoluteReferenceState.origin][com.astrocompass.guiding.AbsoluteReferenceState.origin]. */
    val absoluteReference: AbsoluteReference =
        PrioritizedAbsoluteReference(scope, preferred = alignmentReference, fallback = compassReference)

    val pointingService = PointingService(scope, orientationSensor, absoluteReference, preferences.telescopeAxis)

    init {
        scope.launch { catalogRepository.load() }
        orientationSensor.start()
        locationProvider.start()
    }

    fun saveAlignment(model: AlignmentModel) {
        alignmentStore.save(model)
        alignmentReference.update(model)
    }

    /** Drops back to the rough compass reference, if this device can supply one. */
    fun clearAlignment() {
        alignmentStore.clear()
        alignmentReference.update(null)
    }

    /** Captures one star sync: the configured telescope axis, in both frames, right now. Null
     *  if the sensor hasn't produced a reading yet or location is unset -- both prerequisites
     *  the calling screen should already be gating on. */
    fun captureAlignmentPoint(target: SkyObject, source: AlignmentSource, nowEpochMillis: Long): AlignmentPoint? {
        val orientation = orientationSensor.orientation.value ?: return null
        val location = locationResolver.resolved.value ?: return null
        val skyDirection = target.currentHorizontal(location, nowEpochMillis).toEnu()
        val sensorDirection = orientation.deviceToWorld.rotate(preferences.telescopeAxis.value.deviceVector)
        return AlignmentPoint(skyDirection, sensorDirection, nowEpochMillis, target.id, source)
    }

    /** One-tap yaw-only re-sync against whatever is currently selected on the Guidance screen --
     *  the primary remedy for gyro drift, reachable without re-entering the alignment flow.
     *  Composes onto the existing model (see [AlignmentSolver.resync]) rather than replacing it,
     *  so a prior 2-3 star fit's mounting correction survives the re-sync.
     *
     *  Null without a star alignment to correct: a yaw-only sync on top of the compass fallback
     *  would look like a real alignment while carrying the compass's uncorrected mounting offset,
     *  which is exactly the thing a 2-3 star fit exists to absorb. Plate solving is the honest
     *  one-shot upgrade from compass mode -- see [attemptPlateSolve]. */
    fun syncOnObject(target: SkyObject, nowEpochMillis: Long): AlignmentResult? {
        val existingModel = alignmentStore.load() ?: return null
        val point = captureAlignmentPoint(target, AlignmentSource.RE_SYNC, nowEpochMillis) ?: return null
        val result = AlignmentSolver.resync(existingModel, point, nowEpochMillis)
        if (result is AlignmentResult.Success) saveAlignment(result.model)
        return result
    }

    /**
     * Takes one photo and plate-solves it against the loaded catalog, seeded from wherever
     * [pointingService] currently thinks the telescope points. Returns null if there's no pointing
     * direction or location yet, the camera capture failed, or too few stars matched.
     *
     * The seed can equally be the rough compass reference, which makes this the one path from
     * "no alignment at all" to a real one: [PlateSolveAlignment] recovers a complete 3-DOF fit
     * from a single photo, mounting offset included. It is not guaranteed from a compass seed
     * though -- magnetometer error near a telescope can exceed [PLATE_SOLVE_SEARCH_RADIUS], in
     * which case the solve simply finds nothing.
     *
     * The orientation sensor reading and clock time are read immediately after
     * [CameraCapture.captureFrame] returns, before the (comparatively slow) detection/matching
     * runs -- that's the shutter-time snapshot the whole feature exists to get right, not a value
     * read once the solve happens to finish. [PlateSolveAlignment.solve] is called right here too
     * (not deferred to [applyPlateSolve]), so the [PlateSolveAttempt] the caller reviews is
     * exactly what gets saved -- confirming it later can't silently pick up a changed
     * [AppPreferences.cameraMounting] or a fresher (and wrong, per the same invariant) sensor
     * reading.
     */
    suspend fun attemptPlateSolve(): PlateSolveAttempt? {
        val seedSkyDirection = pointingService.currentSkyDirection.value ?: return null
        val location = locationResolver.resolved.value ?: return null
        val frame = cameraCapture.captureFrame() ?: return null

        val orientation = orientationSensor.orientation.value ?: return null
        val capturedAtEpochMillis = currentEpochMillis()

        val julianDay = AstroTime.julianDay(capturedAtEpochMillis)
        val lst = AstroTime.localSiderealTime(julianDay, location.longitude)
        val seedBoresight = CoordinateTransforms.horizontalToEquatorial(
            HorizontalCoordinates.fromEnu(seedSkyDirection), lst, location.latitude,
        )

        // Blob detection and candidate matching are real CPU work (see the tuning notes on
        // `PlateSolverCatalogDensityTest`) -- keep them off whatever dispatcher the caller (the
        // Guidance screen's coroutine scope) happens to be running on, and bounded, since their
        // cost isn't (see PLATE_SOLVE_TIMEOUT_MILLIS).
        return withTimeoutOrNull(PLATE_SOLVE_TIMEOUT_MILLIS) {
            withContext(Dispatchers.Default) {
                solveAgainstCatalog(frame, seedBoresight, orientation.deviceToWorld, location, seedSkyDirection, capturedAtEpochMillis)
            }
        }
    }

    private fun solveAgainstCatalog(
        frame: CapturedFrame,
        seedBoresight: EquatorialCoordinates,
        deviceToWorld: Quaternion,
        location: ObserverLocation,
        seedSkyDirection: Vector3,
        capturedAtEpochMillis: Long,
    ): PlateSolveAttempt? {
        val detections = CentroidDetector.detect(frame.luminance, frame.width, frame.height)
        // Restricted to named/Bayer/Flamsteed stars even though stars.bin itself now also carries
        // unnamed mag<=7 field stars (added for sky map density) -- PlateSolver's candidate-pair
        // matching is O(candidates^2) per anchor pair (see PlateSolverCatalogDensityTest's tuning
        // notes) with nothing else bounding it below MAX_CANDIDATES within PLATE_SOLVE_SEARCH_RADIUS,
        // so letting the denser catalog flow straight through would multiply solve time by roughly
        // (total stars / named stars)^2 in a typical field. This keeps solver density -- and
        // therefore solve time -- exactly what it was before stars.bin grew.
        val referenceStars = catalogRepository.all
            .filterIsInstance<StarObject>()
            .filter { it.properName.isNotEmpty() || it.bayer.isNotEmpty() || it.flamsteed != 0 }
            .map { ReferenceStar(it.j2000.rightAscension, it.j2000.declination, it.magnitude) }

        val result = PlateSolver.solve(
            detections = detections,
            intrinsics = frame.intrinsics,
            seedBoresight = seedBoresight,
            searchRadius = PLATE_SOLVE_SEARCH_RADIUS,
            referenceStars = referenceStars,
        ) ?: return null

        val alignmentResult = PlateSolveAlignment.solve(
            plateSolveResult = result,
            deviceToWorld = deviceToWorld,
            cameraToDevice = preferences.cameraMounting.value.cameraToDevice,
            location = location,
            nowEpochMillis = capturedAtEpochMillis,
        )
        if (alignmentResult !is AlignmentResult.Success) return null

        val newPredictedPointing = alignmentResult.model.sensorToSky.rotate(
            deviceToWorld.rotate(preferences.telescopeAxis.value.deviceVector),
        )
        val correctionDegrees = seedSkyDirection.angleTo(newPredictedPointing).degrees

        return PlateSolveAttempt(result, alignmentResult.model, correctionDegrees)
    }

    /** Saves a [PlateSolveAttempt] the user has confirmed -- see [attemptPlateSolve] for why
     *  [PlateSolveAttempt.correctedModel] is already fully computed by the time it reaches here. */
    fun applyPlateSolve(attempt: PlateSolveAttempt) {
        saveAlignment(attempt.correctedModel)
    }
}
