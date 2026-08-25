package com.astrocompass.settings

import com.astrocompass.astro.Angle
import com.astrocompass.catalog.MapObjectFilter
import com.astrocompass.guiding.CameraMounting
import com.astrocompass.guiding.TelescopeAxis
import com.astrocompass.location.ObserverLocation
import com.astrocompass.sensors.SensorSource
import com.astrocompass.telescope.SlewRatePreset
import com.astrocompass.ui.theme.AppTheme
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow

/** All user-facing and Advanced-section settings, backed by [Settings]. Hand-rolled
 *  reactive-preference pattern: a [MutableStateFlow] seeded from storage, with a setter that
 *  updates both the flow and storage. */
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

    /** Beta: swaps an object's sky-map dot for its bundled photo once zoomed in enough --
     *  see [com.astrocompass.ui.components.SkyMap]'s `objectPhotos`. Defaults on; the setting
     *  exists as an escape hatch since sourcing/orientation on the bundled photos is still rough. */
    val showObjectImages = MutableStateFlow(settings.getBoolean(KEY_SHOW_OBJECT_IMAGES, true))
    fun setShowObjectImages(show: Boolean) {
        showObjectImages.value = show
        settings.putBoolean(KEY_SHOW_OBJECT_IMAGES, show)
    }

    /** The sky map's category filter (see [com.astrocompass.ui.components.MapFilterSheet]) --
     *  every category on by default, matching the map's behavior before this filter existed. */
    val mapObjectFilter = MutableStateFlow(
        MapObjectFilter(
            showSolarSystem = settings.getBoolean(KEY_SHOW_SOLAR_SYSTEM, true),
            showGalaxies = settings.getBoolean(KEY_SHOW_GALAXIES, true),
            showNebulae = settings.getBoolean(KEY_SHOW_NEBULAE, true),
            showClusters = settings.getBoolean(KEY_SHOW_CLUSTERS, true),
            showOther = settings.getBoolean(KEY_SHOW_OTHER, true),
            maxMagnitude = settings.getFloatOrNull(KEY_MAP_MAX_MAGNITUDE),
        )
    )
    fun setMapObjectFilter(filter: MapObjectFilter) {
        mapObjectFilter.value = filter
        settings.putBoolean(KEY_SHOW_SOLAR_SYSTEM, filter.showSolarSystem)
        settings.putBoolean(KEY_SHOW_GALAXIES, filter.showGalaxies)
        settings.putBoolean(KEY_SHOW_NEBULAE, filter.showNebulae)
        settings.putBoolean(KEY_SHOW_CLUSTERS, filter.showClusters)
        settings.putBoolean(KEY_SHOW_OTHER, filter.showOther)
        if (filter.maxMagnitude == null) settings.remove(KEY_MAP_MAX_MAGNITUDE) else settings.putFloat(KEY_MAP_MAX_MAGNITUDE, filter.maxMagnitude)
    }

    /** The Night Wizard's own type filter -- separate from [mapObjectFilter] since each screen's
     *  filter is independently scoped (see [magnitudeLimit]'s doc comment for the same reasoning
     *  applied to Search). Every category on by default. */
    val nightWizardObjectFilter = MutableStateFlow(
        MapObjectFilter(
            showSolarSystem = settings.getBoolean(KEY_WIZARD_SHOW_SOLAR_SYSTEM, true),
            showGalaxies = settings.getBoolean(KEY_WIZARD_SHOW_GALAXIES, true),
            showNebulae = settings.getBoolean(KEY_WIZARD_SHOW_NEBULAE, true),
            showClusters = settings.getBoolean(KEY_WIZARD_SHOW_CLUSTERS, true),
            showOther = settings.getBoolean(KEY_WIZARD_SHOW_OTHER, true),
        )
    )
    fun setNightWizardObjectFilter(filter: MapObjectFilter) {
        nightWizardObjectFilter.value = filter
        settings.putBoolean(KEY_WIZARD_SHOW_SOLAR_SYSTEM, filter.showSolarSystem)
        settings.putBoolean(KEY_WIZARD_SHOW_GALAXIES, filter.showGalaxies)
        settings.putBoolean(KEY_WIZARD_SHOW_NEBULAE, filter.showNebulae)
        settings.putBoolean(KEY_WIZARD_SHOW_CLUSTERS, filter.showClusters)
        settings.putBoolean(KEY_WIZARD_SHOW_OTHER, filter.showOther)
    }

    val nightWizardMagnitudeLimit = MutableStateFlow(settings.getFloat(KEY_WIZARD_MAGNITUDE_LIMIT, DEFAULT_WIZARD_MAGNITUDE_LIMIT))
    fun setNightWizardMagnitudeLimit(limit: Float) {
        nightWizardMagnitudeLimit.value = limit
        settings.putFloat(KEY_WIZARD_MAGNITUDE_LIMIT, limit)
    }

    val nightWizardMinAltitudeDegrees = MutableStateFlow(settings.getFloat(KEY_WIZARD_MIN_ALTITUDE, DEFAULT_WIZARD_MIN_ALTITUDE_DEGREES))
    fun setNightWizardMinAltitudeDegrees(degrees: Float) {
        nightWizardMinAltitudeDegrees.value = degrees
        settings.putFloat(KEY_WIZARD_MIN_ALTITUDE, degrees)
    }

    /** Last-used telescope TCP host/port (see [com.astrocompass.ui.screens.TelescopeScreen]).
     *  The port default follows SkyFi's documented convention, not a true LX200 standard --
     *  editable from the start since it's a guess, not a protocol constant. */
    val telescopeTcpHost = MutableStateFlow(settings.getStringOrNull(KEY_TELESCOPE_TCP_HOST) ?: "")
    fun setTelescopeTcpHost(host: String) {
        telescopeTcpHost.value = host
        settings.putString(KEY_TELESCOPE_TCP_HOST, host)
    }

    val telescopeTcpPort = MutableStateFlow(settings.getInt(KEY_TELESCOPE_TCP_PORT, DEFAULT_TELESCOPE_TCP_PORT))
    fun setTelescopeTcpPort(port: Int) {
        telescopeTcpPort.value = port
        settings.putInt(KEY_TELESCOPE_TCP_PORT, port)
    }

    /** Last-used paired Bluetooth telescope device address (Android only -- see
     *  [com.astrocompass.telescope.bondedTelescopeCandidates]). Null = none remembered. */
    val telescopeBluetoothAddress = MutableStateFlow(settings.getStringOrNull(KEY_TELESCOPE_BLUETOOTH_ADDRESS))
    fun setTelescopeBluetoothAddress(address: String?) {
        telescopeBluetoothAddress.value = address
        if (address == null) settings.remove(KEY_TELESCOPE_BLUETOOTH_ADDRESS) else settings.putString(KEY_TELESCOPE_BLUETOOTH_ADDRESS, address)
    }

    /** The GOTO speed sent to a connected mount (see [SlewRatePreset]). Persisted because OnStep
     *  offers no way to read the preset back -- it stores a derived microseconds-per-step value,
     *  not the 1..5 selection -- so remembering what was last asked for is the only way the sheet
     *  can show the mount's actual speed. */
    val slewRatePreset = MutableStateFlow(
        settings.getStringOrNull(KEY_SLEW_RATE_PRESET)?.let { runCatching { SlewRatePreset.valueOf(it) }.getOrNull() }
            ?: SlewRatePreset.DEFAULT
    )
    fun setSlewRatePreset(preset: SlewRatePreset) {
        slewRatePreset.value = preset
        settings.putString(KEY_SLEW_RATE_PRESET, preset.name)
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
        const val KEY_SHOW_OBJECT_IMAGES = "show_object_images"
        const val KEY_SHOW_SOLAR_SYSTEM = "show_solar_system"
        const val KEY_SHOW_GALAXIES = "show_galaxies"
        const val KEY_SHOW_NEBULAE = "show_nebulae"
        const val KEY_SHOW_CLUSTERS = "show_clusters"
        const val KEY_SHOW_OTHER = "show_other"
        const val KEY_MAP_MAX_MAGNITUDE = "map_max_magnitude"
        const val KEY_WIZARD_SHOW_SOLAR_SYSTEM = "wizard_show_solar_system"
        const val KEY_WIZARD_SHOW_GALAXIES = "wizard_show_galaxies"
        const val KEY_WIZARD_SHOW_NEBULAE = "wizard_show_nebulae"
        const val KEY_WIZARD_SHOW_CLUSTERS = "wizard_show_clusters"
        const val KEY_WIZARD_SHOW_OTHER = "wizard_show_other"
        const val KEY_WIZARD_MAGNITUDE_LIMIT = "wizard_magnitude_limit"
        const val KEY_WIZARD_MIN_ALTITUDE = "wizard_min_altitude_degrees"
        const val KEY_TELESCOPE_TCP_HOST = "telescope_tcp_host"
        const val KEY_TELESCOPE_TCP_PORT = "telescope_tcp_port"
        const val KEY_TELESCOPE_BLUETOOTH_ADDRESS = "telescope_bluetooth_address"
        const val KEY_SLEW_RATE_PRESET = "telescope_slew_rate_preset"

        const val DEFAULT_TOLERANCE_DEGREES = 0.5
        const val DEFAULT_MAGNITUDE_LIMIT = 13f
        const val DEFAULT_WIZARD_MAGNITUDE_LIMIT = 9f
        const val DEFAULT_WIZARD_MIN_ALTITUDE_DEGREES = 20f
        const val DEFAULT_TELESCOPE_TCP_PORT = 4030
    }
}
