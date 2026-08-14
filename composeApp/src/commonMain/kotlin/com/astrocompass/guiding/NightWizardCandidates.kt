package com.astrocompass.guiding

import com.astrocompass.astro.ephemeris.SolarSystemBody
import com.astrocompass.catalog.DeepSkyObject
import com.astrocompass.catalog.MapObjectFilter
import com.astrocompass.catalog.SkyObject
import com.astrocompass.catalog.SolarSystemObject
import com.astrocompass.catalog.StarObject
import com.astrocompass.catalog.toMapObjectCategory
import com.astrocompass.location.ObserverLocation

/**
 * Filters and ranks the catalog for the Night Wizard: which deep-sky/solar-system objects are
 * worth observing tonight, brightest first. Plain stars are out of scope (matches
 * [MapObjectFilter]'s categories, which never include a Stars bucket) and the Sun is always
 * excluded regardless of filters -- pointing an unfiltered telescope at it is a real hazard.
 * Solar System objects have no computed magnitude ([SolarSystemObject.magnitude] is always
 * NaN), so they bypass the magnitude filter entirely and sort first rather than last.
 *
 * Deep-sky objects with an unknown magnitude are excluded by the magnitude filter, not passed
 * through -- unlike [com.astrocompass.catalog.CatalogSearch] (where an exact name/designation
 * match should win regardless of brightness), a brightness-vetted "what can I see tonight" list
 * has nothing honest to say about an object whose magnitude was never measured. A large fraction
 * of OpenNGC's GALAXY_PAIR/GALAXY_TRIPLET/GALAXY_GROUP entries (and others) have no magnitude at
 * all, so passing them through was flooding results at any magnitude limit, however strict.
 */
object NightWizardCandidates {

    fun compute(
        objects: List<SkyObject>,
        filter: MapObjectFilter,
        magnitudeLimit: Float,
        minAltitudeDegrees: Float,
        location: ObserverLocation,
        startEpochMillis: Long,
    ): List<SkyObject> = objects
        .asSequence()
        .filter { matchesTypeAndMagnitude(it, filter, magnitudeLimit) }
        .filter { it.currentHorizontal(location, startEpochMillis).altitude.degrees >= minAltitudeDegrees }
        .sortedWith(compareBy<SkyObject> { it !is SolarSystemObject }.thenBy { it.magnitude })
        .toList()

    private fun matchesTypeAndMagnitude(obj: SkyObject, filter: MapObjectFilter, magnitudeLimit: Float): Boolean =
        when (obj) {
            is SolarSystemObject -> obj.body != SolarSystemBody.SUN && filter.showSolarSystem
            is DeepSkyObject -> filter.isShown(obj.type.toMapObjectCategory()) && obj.magnitude <= magnitudeLimit
            is StarObject -> false
        }
}
