package com.astrocompass.astro.ephemeris

import com.astrocompass.astro.Angle
import com.astrocompass.astro.coords.CoordinateTransforms
import com.astrocompass.astro.time.AstroTime

/**
 * Finds when the Sun crosses nautical twilight altitude (-12°) -- the threshold this app uses
 * for "dark enough to start observing." No rise/set/twilight solver existed in this codebase
 * before this file. Works by coarsely sampling Sun altitude ([SunEphemeris]) forward from a
 * starting instant, detecting the first sign change in the requested direction, then bisecting
 * the bracketing interval down to sub-minute precision.
 */
object Twilight {

    private const val NAUTICAL_ALTITUDE_DEGREES = -12.0
    private const val SAMPLE_INTERVAL_MILLIS = 15 * 60_000L
    private const val SEARCH_WINDOW_MILLIS = 4 * 24 * 60 * 60_000L
    private const val BISECTION_TOLERANCE_MILLIS = 30_000L

    /** Next instant at/after [fromEpochMillis] the Sun's altitude falls through
     *  [NAUTICAL_ALTITUDE_DEGREES] while descending (evening nautical twilight/dusk). Null if the
     *  Sun never reaches that altitude within the search window (high-latitude summer). */
    fun nextNauticalDusk(fromEpochMillis: Long, latitude: Angle, longitude: Angle): Long? =
        findCrossing(fromEpochMillis, latitude, longitude, descending = true)

    /** Next instant at/after [fromEpochMillis] the Sun's altitude rises through
     *  [NAUTICAL_ALTITUDE_DEGREES] while ascending (morning nautical twilight/dawn). Null if the
     *  Sun never reaches that altitude within the search window (high-latitude winter). */
    fun nextNauticalDawn(fromEpochMillis: Long, latitude: Angle, longitude: Angle): Long? =
        findCrossing(fromEpochMillis, latitude, longitude, descending = false)

    private fun findCrossing(
        fromEpochMillis: Long,
        latitude: Angle,
        longitude: Angle,
        descending: Boolean,
    ): Long? {
        var previousTime = fromEpochMillis
        var previousAltitude = sunAltitudeDegrees(previousTime, latitude, longitude)
        val endTime = fromEpochMillis + SEARCH_WINDOW_MILLIS

        var time = fromEpochMillis + SAMPLE_INTERVAL_MILLIS
        while (time <= endTime) {
            val altitude = sunAltitudeDegrees(time, latitude, longitude)
            val crossed = if (descending) {
                previousAltitude > NAUTICAL_ALTITUDE_DEGREES && altitude <= NAUTICAL_ALTITUDE_DEGREES
            } else {
                previousAltitude < NAUTICAL_ALTITUDE_DEGREES && altitude >= NAUTICAL_ALTITUDE_DEGREES
            }
            if (crossed) {
                return bisect(previousTime, time, latitude, longitude, descending)
            }
            previousTime = time
            previousAltitude = altitude
            time += SAMPLE_INTERVAL_MILLIS
        }
        return null
    }

    private fun bisect(
        beforeTime: Long,
        afterTime: Long,
        latitude: Angle,
        longitude: Angle,
        descending: Boolean,
    ): Long {
        var lo = beforeTime
        var hi = afterTime
        while (hi - lo > BISECTION_TOLERANCE_MILLIS) {
            val mid = (lo + hi) / 2
            val altitude = sunAltitudeDegrees(mid, latitude, longitude)
            val stillBeforeCrossing = if (descending) {
                altitude > NAUTICAL_ALTITUDE_DEGREES
            } else {
                altitude < NAUTICAL_ALTITUDE_DEGREES
            }
            if (stillBeforeCrossing) lo = mid else hi = mid
        }
        return hi
    }

    private fun sunAltitudeDegrees(epochMillis: Long, latitude: Angle, longitude: Angle): Double {
        val julianDay = AstroTime.julianDay(epochMillis)
        val julianCenturies = AstroTime.julianCenturiesJ2000(julianDay)
        val lst = AstroTime.localSiderealTime(julianDay, longitude)
        val sunEquatorial = SunEphemeris.geocentricEquatorial(julianCenturies)
        return CoordinateTransforms.equatorialToHorizontal(sunEquatorial, lst, latitude).altitude.degrees
    }
}
