package com.astrocompass.catalog

import com.astrocompass.astro.coords.EquatorialCoordinates

/** One constellation's stick figure, as a set of connected polylines (some constellations draw
 *  as more than one disjoint stroke) -- decoded from `constellations.bin`, see [CatalogFormat]. */
data class ConstellationLine(
    val abbreviation: String,
    val polylines: List<List<EquatorialCoordinates>>,
)
