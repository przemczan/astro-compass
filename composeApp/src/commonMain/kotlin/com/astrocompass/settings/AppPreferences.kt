package com.astrocompass.settings

import com.astrocompass.astro.Angle
import com.astrocompass.guiding.CameraMounting
import com.astrocompass.guiding.TelescopeAxis
import com.astrocompass.location.ObserverLocation
import com.astrocompass.sensors.SensorSource
import com.astrocompass.ui.theme.AppTheme
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow

/** All user-facing and Advanced-section settings, backed by [Settings]. Follows the same
 *  hand-rolled reactive-preference pattern as lightnet-mobile's `DemoSettings`: a
 *  [MutableStateFlow] seeded from storage, with a setter that updates both the flow and storage. */
class AppPreferences(private val settings: Settings) {

    val telescopeAxis = MutableStateFlow(
        settings.getStringOrNull(KEY_TELESCOPE_AXIS)?.let { runCatching { TelescopeAxis.valueOf(it) }.getOrNull() }
            ?: TelescopeAxis.DEFAULT
    )
    fun setTelescopeAxis(axis: TelescopeAxis) {
        telescopeAxis.value = axis
        settings.putString(KEY_TELESCOPE_AXIS, axis.name)
    }

    val onTargetToleranceDegrees = MutableStateFlow(settings.getDouble(KEY_TOLERANCE, DEFAULT_TOLERANCE_DEGREES))
    fun setOnTargetToleranceDegrees(degrees: Double) {
        val clamped = degrees.coerceIn(0.05, 10.0)
        onTargetToleranceDegrees.value = clamped
        settings.putDouble(KEY_TOLERANCE, clamped)
    }

    val magnitudeLimit = MutableStateFlow(settings.getFloat(KEY_MAGNITUDE_LIMIT, DEFAULT_MAGNITUDE_LIMIT))
    fun setMagnitudeLimit(limit: Float) {
        magnitudeLimit.value = limit
        settings.putFloat(KEY_MAGNITUDE_LIMIT, limit)
    }

    /** Null = follow the system light/dark setting. */
    val appTheme = MutableStateFlow(
        settings.getStringOrNull(KEY_APP_THEME)?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() }
    )
    fun setAppTheme(theme: AppTheme?) {
        appTheme.value = theme
        if (theme == null) settings.remove(KEY_APP_THEME) else settings.putString(KEY_APP_THEME, theme.name)
    }

    /** Advanced-only manual override of the auto-selected pointing sensor. Null = auto. */
    val sensorSourceOverride = MutableStateFlow(
        settings.getStringOrNull(KEY_SENSOR_OVERRIDE)?.let { runCatching { SensorSource.valueOf(it) }.getOrNull() }
    )
    fun setSensorSourceOverride(source: SensorSource?) {
        sensorSourceOverride.value = source
        if (source == null) settings.remove(KEY_SENSOR_OVERRIDE) else settings.putString(KEY_SENSOR_OVERRIDE, source.name)
    }

    /** Advanced-only: which [CameraMounting] preset to use when applying a plate-solve
     *  correction. See [CameraMounting]'s doc comment for why this can't be a fixed constant. */
    val cameraMounting = MutableStateFlow(
        settings.getStringOrNull(KEY_CAMERA_MOUNTING)?.let { runCatching { CameraMounting.valueOf(it) }.getOrNull() }
            ?: CameraMounting.DEFAULT
    )
    fun setCameraMounting(mounting: CameraMounting) {
        cameraMounting.value = mounting
        settings.putString(KEY_CAMERA_MOUNTING, mounting.name)
    }

    val manualLocation = MutableStateFlow(readManualLocation())
    fun setManualLocation(location: ObserverLocation?) {
        manualLocation.value = location
        if (location == null) {
            settings.remove(KEY_MANUAL_LAT)
            settings.remove(KEY_MANUAL_LON)
            settings.remove(KEY_MANUAL_ELEVATION)
        } else {
            settings.putDouble(KEY_MANUAL_LAT, location.latitude.degrees)
            settings.putDouble(KEY_MANUAL_LON, location.longitude.degrees)
            settings.putDouble(KEY_MANUAL_ELEVATION, location.elevationMeters)
        }
    }
    private fun readManualLocation(): ObserverLocation? {
        val lat = settings.getDoubleOrNull(KEY_MANUAL_LAT) ?: return null
        val lon = settings.getDoubleOrNull(KEY_MANUAL_LON) ?: return null
        val elevation = settings.getDouble(KEY_MANUAL_ELEVATION, 0.0)
        return ObserverLocation(Angle.ofDegrees(lat), Angle.ofDegrees(lon), elevation)
    }

    val lastTargetId = MutableStateFlow(settings.getStringOrNull(KEY_LAST_TARGET))
    fun setLastTargetId(id: String?) {
        lastTargetId.value = id
        if (id == null) settings.remove(KEY_LAST_TARGET) else settings.putString(KEY_LAST_TARGET, id)
    }

    private companion object {
        const val KEY_TELESCOPE_AXIS = "telescope_axis"
        const val KEY_TOLERANCE = "on_target_tolerance_degrees"
        const val KEY_MAGNITUDE_LIMIT = "magnitude_limit"
        const val KEY_APP_THEME = "app_theme"
        const val KEY_SENSOR_OVERRIDE = "sensor_source_override"
        const val KEY_CAMERA_MOUNTING = "camera_mounting"
        const val KEY_MANUAL_LAT = "manual_lat"
        const val KEY_MANUAL_LON = "manual_lon"
        const val KEY_MANUAL_ELEVATION = "manual_elevation"
        const val KEY_LAST_TARGET = "last_target_id"

        const val DEFAULT_TOLERANCE_DEGREES = 0.5
        const val DEFAULT_MAGNITUDE_LIMIT = 13f
    }
}
