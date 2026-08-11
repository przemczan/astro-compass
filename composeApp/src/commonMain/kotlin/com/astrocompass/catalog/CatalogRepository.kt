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
    private val solarSystemObjects: List<SolarSystemObject> =
        SolarSystemBody.entries.map { SolarSystemObject(it) }

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded

    /** All loaded objects, built once in [load] -- the sky map projects this list every few
     *  seconds, so it must not be rebuilt (three list concatenations) on every access. */
    var all: List<SkyObject> = solarSystemObjects
        private set

    /** The sky map's constellation stick figures -- see [ConstellationLine], `constellations.bin`. */
    var constellationLines: List<ConstellationLine> = emptyList()
        private set

    suspend fun load() {
        val starBytes = Res.readBytes("files/stars.bin")
        val dsoBytes = Res.readBytes("files/dso.bin")
        val constellationBytes = Res.readBytes("files/constellations.bin")
        // Decoding ~75k records (string allocations included) is real CPU work -- keep it off
        // the caller's dispatcher, which for AppContainer's startup launch is Dispatchers.Main.
        withContext(Dispatchers.Default) {
            val stars = CatalogFormat.decodeStars(starBytes)
            val deepSkyObjects = CatalogFormat.decodeDeepSkyObjects(dsoBytes)
            all = solarSystemObjects + stars + deepSkyObjects
            constellationLines = CatalogFormat.decodeConstellationLines(constellationBytes)
        }
        _isLoaded.value = true
    }

    fun byId(id: String): SkyObject? = all.firstOrNull { it.id == id }
}
