package com.astrocompass

import com.astrocompass.alignment.AlignmentModel
import com.astrocompass.alignment.AlignmentPoint
import com.astrocompass.alignment.AlignmentResult
import com.astrocompass.alignment.AlignmentSource
import com.astrocompass.alignment.AlignmentStore
import com.astrocompass.alignment.AlignmentType
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
import com.astrocompass.guiding.AutoPlateSolveRefiner
import com.astrocompass.guiding.CompassAbsoluteReference
import com.astrocompass.guiding.FreshestAbsoluteReference
import com.astrocompass.guiding.PlateSolveAttempt
import com.astrocompass.guiding.PointingService
import com.astrocompass.guiding.PrioritizedAbsoluteReference
import com.astrocompass.guiding.currentEquatorial
import com.astrocompass.guiding.currentHorizontal
import com.astrocompass.location.LocationProvider
import com.astrocompass.location.LocationResolver
import com.astrocompass.location.MagneticDeclinationProvider
import com.astrocompass.location.ObserverLocation
import com.astrocompass.platesolve.CameraCapture
import com.astrocompass.platesolve.CameraEnumerator
import com.astrocompass.platesolve.CapturedFrame
import com.astrocompass.platesolve.CentroidDetector
import com.astrocompass.platesolve.PlateSolver
import com.astrocompass.platesolve.ReferenceStar
import com.astrocompass.sensors.OrientationSensor
import com.astrocompass.settings.AppPreferences
import com.astrocompass.telescope.Lx200Codec
import com.astrocompass.telescope.Lx200TelescopeConnection
import com.astrocompass.telescope.MoveRatePreset
import com.astrocompass.telescope.SlewOutcome
import com.astrocompass.telescope.SlewRatePreset
import com.astrocompass.telescope.SyncOutcome
import com.astrocompass.telescope.TcpTelescopeTransport
import com.astrocompass.telescope.TelescopeConnection
import com.astrocompass.telescope.TelescopeConnectionState
import com.astrocompass.telescope.TelescopeDirection
import com.astrocompass.telescope.TelescopeEndpoint
import com.astrocompass.telescope.TelescopePointingSource
import com.astrocompass.telescope.TelescopeTransport
import com.astrocompass.update.AppUpdater
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
 * injected into [GuiderApp] -- dependency injection by constructor, grouped into one container
 * instead of one parameter per service.
 */
class AppContainer(
    private val scope: CoroutineScope,
    val orientationSensor: OrientationSensor,
    val locationProvider: LocationProvider,
    val cameraCapture: CameraCapture,
    val cameraEnumerator: CameraEnumerator,
    val appUpdater: AppUpdater,
    magneticDeclinationProvider: MagneticDeclinationProvider,
    settings: Settings,
    tcpTransportFactory: (host: String, port: Int) -> TelescopeTransport = ::TcpTelescopeTransport,
    bluetoothTransportFactory: (address: String) -> TelescopeTransport,
    /** True only where Bluetooth Classic SPP is a real platform capability (Android) -- lets
     *  [com.astrocompass.ui.screens.TelescopeScreen] hide the whole Bluetooth section on iOS
     *  rather than showing an always-empty paired-device list. */
    val supportsBluetoothTelescope: Boolean = false,
    private val bondedBluetoothDevicesProvider: () -> List<Pair<String, String>> = { emptyList() },
    private val pairNewBluetoothDeviceAction: () -> Unit = {},
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

    /** Live plate solves while guiding a camera-calibrated setup -- see [AutoPlateSolveRefiner].
     *  Only ever running under [AlignmentType.PLATE_SOLVE]; see [setAutoPlateSolveActive]. */
    private val autoPlateSolveRefiner = AutoPlateSolveRefiner(
        scope = scope,
        orientationSensor = orientationSensor,
        telescopeAxis = preferences.telescopeAxis,
        attemptSolve = ::attemptPlateSolve,
        // Persisted once, not per solve: a warm start next launch is worth one write, while writing
        // every few seconds would churn storage for a value the running refiner already holds.
        onFirstSuccess = { attempt -> saveAlignment(attempt.correctedModel) },
    )

    /** The more recent of the live plate solve and the stored star alignment, and the rough compass
     *  only if there is neither -- see [FreshestAbsoluteReference] for why those two are ranked by
     *  age rather than by kind, and [PrioritizedAbsoluteReference] for why the compass is not.
     *  Screens distinguish a real fit from the compass by
     *  [AbsoluteReferenceState.origin][com.astrocompass.guiding.AbsoluteReferenceState.origin];
     *  which *kind* of fit produced it is deliberately not a distinction they draw. */
    val absoluteReference: AbsoluteReference = PrioritizedAbsoluteReference(
        scope,
        preferred = FreshestAbsoluteReference(scope, autoPlateSolveRefiner, alignmentReference),
        fallback = compassReference,
    )

    val pointingService = PointingService(scope, orientationSensor, absoluteReference, preferences.telescopeAxis)

    val telescopeConnection: TelescopeConnection =
        Lx200TelescopeConnection(scope, tcpTransportFactory, bluetoothTransportFactory, location = locationResolver.resolved)

    private val telescopePointingSource = TelescopePointingSource(scope, telescopeConnection, locationResolver.resolved)

    /** Where the mount reports itself pointing, or null while there is no connected mount reporting
     *  freshly -- every sky map marks this (see [com.astrocompass.ui.components.SkyMapMarker.telescope]).
     *  Gated on [TelescopePointingSource.isReady] rather than a plain null check on the direction,
     *  so a dropped connection clears the marker instead of leaving the last report as a ghost. */
    val telescopeSkyDirection: StateFlow<Vector3?> =
        combine(telescopePointingSource.currentSkyDirection, telescopePointingSource.isReady) { direction, isReady ->
            direction.takeIf { isReady }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Turns the background solve loop on while the Guidance screen is showing a plate-solve setup,
     *  and off otherwise -- there is nothing for it to correct outside guidance. */
    fun setAutoPlateSolveActive(active: Boolean) {
        val plateSolveSetup = preferences.alignmentType.value == AlignmentType.PLATE_SOLVE
        autoPlateSolveRefiner.setActive(active && plateSolveSetup)
    }

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

    /** [Lx200TelescopeConnection.connect] runs the mount-sync sequence (time, site, unpark)
     *  itself, against [locationResolver]'s current value, with no separate user-facing step --
     *  see its class doc for why that has to happen inside [TelescopeConnection.connect] rather
     *  than as a second call from here. */
    suspend fun connectTelescope(endpoint: TelescopeEndpoint) {
        telescopeConnection.connect(endpoint)
        // A mount remembers its own GOTO speed across power cycles, so it can disagree with the
        // preference the sheet displays until something re-asserts it. A no-op if the connect
        // above failed (see TelescopeConnection.setSlewRatePreset).
        //
        // This lands *after* connect() has started the position poll, which the mount-sync
        // sequence deliberately does not (see Lx200TelescopeConnection's class doc). Safe only
        // because :SX93 has no reply to misalign: Lx200Session's mutex keeps the write itself
        // atomic, and there is no read that a concurrent :GR#/:GD# could steal. A command that
        // *did* read a reply would have to go inside connect() instead.
        telescopeConnection.setSlewRatePreset(preferences.slewRatePreset.value)
    }

    suspend fun disconnectTelescope() = telescopeConnection.disconnect()

    /** [target]'s of-date equatorial coordinates -- see [SkyObject.currentEquatorial] -- sent
     *  straight to the mount, behind a re-assert of the chosen GOTO speed: OnStep silently ignores
     *  a speed change while a slew or guide is running (see [Lx200Codec.setSlewRatePreset]), so
     *  one picked mid-slew would otherwise never reach the mount while the sheet went on showing
     *  it. Re-asserting here doesn't guarantee the mount is idle -- a GOTO fired during another
     *  GOTO hits the same refusal -- it just makes every ordinary "change the speed, then slew"
     *  sequence land, which is enough for the setting to self-heal without tracking mount state. */
    suspend fun slewTelescopeTo(target: SkyObject, nowEpochMillis: Long): SlewOutcome {
        telescopeConnection.setSlewRatePreset(preferences.slewRatePreset.value)
        return telescopeConnection.slewTo(target.currentEquatorial(nowEpochMillis))
    }

    suspend fun abortTelescopeSlew() = telescopeConnection.abortSlew()

    suspend fun startTelescopeMove(direction: TelescopeDirection) = telescopeConnection.startMove(direction)

    suspend fun stopTelescopeMove(direction: TelescopeDirection) = telescopeConnection.stopMove(direction)

    suspend fun setTelescopeMoveRatePreset(preset: MoveRatePreset) = telescopeConnection.setMoveRatePreset(preset)

    /** The blanket stop, run on [scope] rather than the caller's so it still lands when the caller
     *  is a composable being torn down -- a hand-control button whose release never arrives (the
     *  screen closed mid-press) would otherwise leave the mount slewing with nothing to stop it. */
    fun stopAllTelescopeMotion() {
        scope.launch { telescopeConnection.abortSlew() }
    }

    /** Arms the mount's own multi-star alignment -- see [TelescopeConnection.beginAlignment] for
     *  what that does to the mount before any star is added. */
    suspend fun beginTelescopeAlignment(starCount: Int): Boolean =
        telescopeConnection.beginAlignment(starCount)

    /** Contributes [target] as one point of the alignment armed by [beginTelescopeAlignment] --
     *  or, with none armed, corrects the mount's pointing origin outright. Same command either
     *  way; see [TelescopeConnection.syncTo].
     *
     *  [nowEpochMillis] must be the instant the user confirmed the star was centered, for the same
     *  reason [AlignmentPoint] fixes its own capture time -- see that class's doc. */
    suspend fun syncTelescopeTo(target: SkyObject, nowEpochMillis: Long): SyncOutcome =
        telescopeConnection.syncTo(target.currentEquatorial(nowEpochMillis))

    /** Whether the mount says it is at home -- the precondition [beginTelescopeAlignment] silently
     *  assumes. Null when there's no connection or the mount didn't answer. */
    suspend fun readTelescopeAtHome(): Boolean? = telescopeConnection.readAtHome()

    suspend fun moveTelescopeHome() = telescopeConnection.moveToHome()

    /** Persists the model the last [syncTelescopeTo] of an alignment run completed. */
    suspend fun saveTelescopeAlignmentModel(): Boolean = telescopeConnection.saveAlignmentModel()

    /** Persists the GOTO speed and pushes it at the mount right away; also re-asserted by
     *  [slewTelescopeTo] and [connectTelescope]. */
    suspend fun setSlewRatePreset(preset: SlewRatePreset) {
        preferences.setSlewRatePreset(preset)
        telescopeConnection.setSlewRatePreset(preset)
    }

    /** False if the mount refused -- OnStep won't start tracking while parked. */
    suspend fun setTelescopeTracking(enabled: Boolean): Boolean = telescopeConnection.setTracking(enabled)

    suspend fun readTelescopeTracking(): Boolean? = telescopeConnection.readTrackingEnabled()

    /** Re-queried on every call rather than cached -- the paired-device list can change any time
     *  the user visits Android's own Bluetooth settings, and [com.astrocompass.ui.screens.TelescopeScreen]
     *  re-queries it on resume (see its `LifecycleResumeEffect`) so a pairing done via
     *  [pairNewBluetoothDevice] while the screen stays on-screen is picked up too. */
    fun bondedBluetoothDevices(): List<Pair<String, String>> = bondedBluetoothDevicesProvider()

    /** Opens the platform's Bluetooth settings so the user can pair a new device -- Android/iOS
     *  have no API to launch the "pair new device" step directly, so this is the closest
     *  equivalent to both "open pairing" and "open Bluetooth settings" on every platform. No-op
     *  where unsupported (iOS). */
    fun pairNewBluetoothDevice() = pairNewBluetoothDeviceAction()
}
