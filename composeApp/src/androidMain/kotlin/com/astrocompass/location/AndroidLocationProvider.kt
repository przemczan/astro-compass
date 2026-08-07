package com.astrocompass.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.astrocompass.astro.Angle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Plain `android.location.LocationManager` -- no Play Services dependency, matching the
 *  plan's dependency-free stack. Permission is requested by the UI layer; if it is missing,
 *  `registerListener` simply throws and is caught here, leaving [location] at null. */
class AndroidLocationProvider(context: Context) : LocationProvider, LocationListener {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _location = MutableStateFlow<ObserverLocation?>(null)
    override val location: StateFlow<ObserverLocation?> = _location

    override fun start() {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }

        runCatching {
            providers.forEach { provider ->
                locationManager.requestLocationUpdates(provider, MIN_UPDATE_INTERVAL_MS, MIN_UPDATE_DISTANCE_M, this)
                locationManager.getLastKnownLocation(provider)?.let { onLocationChanged(it) }
            }
        }
    }

    override fun stop() {
        runCatching { locationManager.removeUpdates(this) }
    }

    override fun onLocationChanged(location: Location) {
        _location.value = ObserverLocation(
            latitude = Angle.ofDegrees(location.latitude),
            longitude = Angle.ofDegrees(location.longitude),
            elevationMeters = if (location.hasAltitude()) location.altitude else 0.0,
        )
    }

    @Deprecated("Deprecated in LocationListener, still called on older API levels")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private companion object {
        const val MIN_UPDATE_INTERVAL_MS = 10_000L
        const val MIN_UPDATE_DISTANCE_M = 10f
    }
}
