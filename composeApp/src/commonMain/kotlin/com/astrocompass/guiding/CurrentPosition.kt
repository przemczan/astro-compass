package com.astrocompass.guiding

import com.astrocompass.astro.coords.CoordinateTransforms
import com.astrocompass.astro.coords.HorizontalCoordinates
import com.astrocompass.astro.time.AstroTime
import com.astrocompass.catalog.SkyObject
import com.astrocompass.location.ObserverLocation

/** Where a catalog object is *right now*, from this observer's location. */
fun SkyObject.currentHorizontal(location: ObserverLocation, epochMillis: Long): HorizontalCoordinates {
    val julianDay = AstroTime.julianDay(epochMillis)
    val julianCenturies = AstroTime.julianCenturiesJ2000(julianDay)
    val lst = AstroTime.localSiderealTime(julianDay, location.longitude)
    return CoordinateTransforms.equatorialToHorizontal(equatorialAt(julianCenturies), lst, location.latitude)
}
