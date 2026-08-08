package com.astrocompass.ui.skymap

import com.astrocompass.astro.Matrix3
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.coords.CoordinateTransforms
import com.astrocompass.astro.coords.Precession
import com.astrocompass.astro.time.AstroTime
import com.astrocompass.catalog.ConstellationLine
import com.astrocompass.catalog.SkyObject
import com.astrocompass.catalog.SolarSystemObject
import com.astrocompass.location.ObserverLocation

/**
 * Builds the "where is everything right now" snapshot the sky map projects: one ENU unit vector
 * per catalog object. Meant to be recomputed every few seconds (the sky moves ~15"/s, so a stale
 * cache is sub-pixel at any usable zoom for that long), not every frame -- [SkyMapScene] is the
 * per-frame path and takes this snapshot as input rather than recomputing it.
 *
 * Star/deep-sky positions share one precession-then-ENU rotation matrix ([Precession.rotationJ2000ToDate]
 * composed with [CoordinateTransforms.equatorialToEnuMatrix]) applied per object, instead of each
 * object repeating both stages' trig. Solar system bodies are the exception: there are only a
 * handful of them and their ephemerides already return equatorial-of-date directly, so they only
 * need the ENU half of that rotation, applied to [SkyObject.equatorialAt]'s result.
 */
object SkyMapDirectionCache {

    fun build(
        catalog: List<SkyObject>,
        location: ObserverLocation,
        nowEpochMillis: Long,
    ): List<Pair<SkyObject, Vector3>> {
        val julianDay = AstroTime.julianDay(nowEpochMillis)
        val julianCenturies = AstroTime.julianCenturiesJ2000(julianDay)
        val enuMatrix = enuMatrixFor(location, julianDay)
        val j2000ToEnu = enuMatrix * Precession.rotationJ2000ToDate(julianCenturies)

        return catalog.map { obj ->
            val direction = when (obj) {
                is SolarSystemObject -> enuMatrix * obj.equatorialAt(julianCenturies).toUnitVector()
                is com.astrocompass.catalog.StarObject -> j2000ToEnu * obj.j2000.toUnitVector()
                is com.astrocompass.catalog.DeepSkyObject -> j2000ToEnu * obj.j2000.toUnitVector()
            }
            obj to direction
        }
    }

    /** Same rotation as [build] applied to constellation-line vertices instead of catalog
     *  objects -- kept separate because [ConstellationLine] isn't a [SkyObject] (there's nothing
     *  to select or list about a stick figure), but the lines still need to track precession like
     *  everything else drawn on the map. Polyline structure is preserved so the caller can draw
     *  each one as a connected path. */
    fun buildConstellationDirections(
        lines: List<ConstellationLine>,
        location: ObserverLocation,
        nowEpochMillis: Long,
    ): List<List<Vector3>> {
        val julianDay = AstroTime.julianDay(nowEpochMillis)
        val julianCenturies = AstroTime.julianCenturiesJ2000(julianDay)
        val j2000ToEnu = enuMatrixFor(location, julianDay) * Precession.rotationJ2000ToDate(julianCenturies)

        return lines.flatMap { it.polylines }.map { polyline ->
            polyline.map { vertex -> j2000ToEnu * vertex.toUnitVector() }
        }
    }

    private fun enuMatrixFor(location: ObserverLocation, julianDay: Double): Matrix3 {
        val lst = AstroTime.localSiderealTime(julianDay, location.longitude)
        return CoordinateTransforms.equatorialToEnuMatrix(lst, location.latitude)
    }
}
