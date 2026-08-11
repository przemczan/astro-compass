package com.astrocompass.ui.skymap

import com.astrocompass.astro.Angle
import com.astrocompass.astro.Matrix3
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.coords.CoordinateTransforms
import com.astrocompass.astro.coords.EquatorialCoordinates
import com.astrocompass.astro.coords.Precession
import com.astrocompass.astro.time.AstroTime
import com.astrocompass.catalog.ConstellationLine
import com.astrocompass.catalog.DeepSkyObject
import com.astrocompass.catalog.SkyObject
import com.astrocompass.catalog.SolarSystemObject
import com.astrocompass.location.ObserverLocation

/** Small enough that "a point this far north" is indistinguishable from the object's own position
 *  at any zoom this map draws photos at, but large enough to stay well clear of float precision
 *  loss in the trig -- only its *direction* from the object matters, not its magnitude. */
private val NORTH_OFFSET = Angle.ofDegrees(0.1)

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

    /**
     * ENU direction of a point [NORTH_OFFSET] north (in J2000 declination) of each Messier-numbered
     * object -- lets the sky map find which way *true equatorial north* points on *this* screen,
     * so a bundled photo (published north-up, the standard astrophotography convention) can be
     * rotated to match it. Deliberately does **not** apply [DeepSkyObject.positionAngleDegrees]:
     * that's the object's tilt *relative to north in its own photo*, which a north-up photo's
     * pixels already show correctly on their own -- applying it again on top would rotate twice.
     * [positionAngleDegrees] instead exists for a schematic (non-photo) glyph, which today's dot
     * markers don't attempt either.
     *
     * This screen renders alt-az (screen-up is the local zenith, see [SkyMap][com.astrocompass.ui.components.SkyMap]'s
     * doc comment), not equatorial (north-up) -- the two "up"s coincide only momentarily. The angle
     * between them (the parallactic angle) depends on observer latitude and local sidereal time
     * even though the object's own position angle does not, which is why this needs [location] and
     * [nowEpochMillis] at all despite the object's orientation itself being fixed.
     */
    fun northOffsetDirections(
        catalog: List<DeepSkyObject>,
        location: ObserverLocation,
        nowEpochMillis: Long,
    ): Map<String, Vector3> {
        val julianDay = AstroTime.julianDay(nowEpochMillis)
        val julianCenturies = AstroTime.julianCenturiesJ2000(julianDay)
        val j2000ToEnu = enuMatrixFor(location, julianDay) * Precession.rotationJ2000ToDate(julianCenturies)

        return catalog.asSequence()
            .filter { it.messier > 0 }
            .associate { obj ->
                val nudged = EquatorialCoordinates(obj.j2000.rightAscension, obj.j2000.declination + NORTH_OFFSET)
                obj.id to (j2000ToEnu * nudged.toUnitVector())
            }
    }

    private fun enuMatrixFor(location: ObserverLocation, julianDay: Double): Matrix3 {
        val lst = AstroTime.localSiderealTime(julianDay, location.longitude)
        return CoordinateTransforms.equatorialToEnuMatrix(lst, location.latitude)
    }
}
