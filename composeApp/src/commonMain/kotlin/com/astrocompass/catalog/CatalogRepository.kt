package com.astrocompass.catalog

import astrocompass.composeapp.generated.resources.Res
import com.astrocompass.astro.ephemeris.SolarSystemBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Loads the bundled star/deep-sky blobs plus the always-available solar system bodies.
 *  [load] is suspend and must be called once, from a coroutine -- typically from `AppContainer`
 *  at startup. [isLoaded] lets the Search screen show a loading state until then. */
class CatalogRepository {
    private var _stars: List<StarObject> = emptyList()
    private var _deepSkyObjects: List<DeepSkyObject> = emptyList()
    private val solarSystemObjects: List<SolarSystemObject> =
        SolarSystemBody.entries.map { SolarSystemObject(it) }

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded

    suspend fun load() {
        val starBytes = Res.readBytes("files/stars.bin")
        val dsoBytes = Res.readBytes("files/dso.bin")
        // Decoding ~16k records (string allocations included) is real CPU work -- keep it off
        // the caller's dispatcher, which for AppContainer's startup launch is Dispatchers.Main.
        withContext(Dispatchers.Default) {
            _stars = CatalogFormat.decodeStars(starBytes)
            _deepSkyObjects = CatalogFormat.decodeDeepSkyObjects(dsoBytes)
        }
        _isLoaded.value = true
    }

    val all: List<SkyObject> get() = solarSystemObjects + _stars + _deepSkyObjects

    fun byId(id: String): SkyObject? = all.firstOrNull { it.id == id }
}
