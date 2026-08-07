package com.astroguider.guiding

import com.astroguider.astro.coords.CoordinateTransforms
import com.astroguider.astro.coords.HorizontalCoordinates
import com.astroguider.astro.time.AstroTime
import com.astroguider.catalog.SkyObject
import com.astroguider.location.ObserverLocation

/** Where a catalog object is *right now*, from this observer's location. */
fun SkyObject.currentHorizontal(location: ObserverLocation, epochMillis: Long): HorizontalCoordinates {
    val julianDay = AstroTime.julianDay(epochMillis)
    val julianCenturies = AstroTime.julianCenturiesJ2000(julianDay)
    val lst = AstroTime.localSiderealTime(julianDay, location.longitude)
    return CoordinateTransforms.equatorialToHorizontal(equatorialAt(julianCenturies), lst, location.latitude)
}
