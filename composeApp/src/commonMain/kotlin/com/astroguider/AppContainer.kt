package com.astroguider

import com.astroguider.alignment.AlignmentModel
import com.astroguider.alignment.AlignmentPoint
import com.astroguider.alignment.AlignmentResult
import com.astroguider.alignment.AlignmentSolver
import com.astroguider.alignment.AlignmentSource
import com.astroguider.alignment.AlignmentStore
import com.astroguider.catalog.CatalogRepository
import com.astroguider.catalog.SkyObject
import com.astroguider.guiding.AlignmentAbsoluteReference
import com.astroguider.guiding.PointingService
import com.astroguider.guiding.currentHorizontal
import com.astroguider.location.LocationProvider
import com.astroguider.location.LocationResolver
import com.astroguider.sensors.OrientationSensor
import com.astroguider.settings.AppPreferences
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    settings: Settings,
) {
    val preferences = AppPreferences(settings)
    val catalogRepository = CatalogRepository()
    val alignmentStore = AlignmentStore(settings)
    val absoluteReference = AlignmentAbsoluteReference()
    val locationResolver = LocationResolver(scope, locationProvider, preferences.manualLocation)
    val pointingService = PointingService(scope, orientationSensor, absoluteReference, preferences.telescopeAxis)

    init {
        absoluteReference.update(alignmentStore.load())
        scope.launch { catalogRepository.load() }
        orientationSensor.start()
        locationProvider.start()
    }

    fun saveAlignment(model: AlignmentModel) {
        alignmentStore.save(model)
        absoluteReference.update(model)
    }

    fun clearAlignment() {
        alignmentStore.clear()
        absoluteReference.update(null)
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
     *  so a prior 2-3 star fit's mounting correction survives the re-sync. Falls back to a
     *  from-scratch yaw-only solve only if there is no existing model yet. */
    fun syncOnObject(target: SkyObject, nowEpochMillis: Long): AlignmentResult? {
        val point = captureAlignmentPoint(target, AlignmentSource.RE_SYNC, nowEpochMillis) ?: return null
        val existingModel = alignmentStore.load()
        val result = if (existingModel != null) {
            AlignmentSolver.resync(existingModel, point, nowEpochMillis)
        } else {
            AlignmentSolver.solve(listOf(point), nowEpochMillis)
        }
        if (result is AlignmentResult.Success) saveAlignment(result.model)
        return result
    }
}
