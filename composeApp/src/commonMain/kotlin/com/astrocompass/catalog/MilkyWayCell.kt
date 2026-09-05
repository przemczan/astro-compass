package com.astrocompass.catalog

import com.astrocompass.astro.coords.EquatorialCoordinates

/** One cell of the rasterized Milky Way density map -- decoded from `milkyway.bin`, see
 *  [CatalogFormat]. [level] is which of d3-celestial's five nested brightness contours (1 =
 *  widest/faintest, 5 = smallest/brightest, centered near Sagittarius) this cell's center falls
 *  inside; there's no cell at all where no contour applies. */
data class MilkyWayCell(
    val position: EquatorialCoordinates,
    val level: Int,
)

/** [cells] plus the RA/Dec grid spacing (degrees) they were rasterized at -- the sky map derives
 *  each cell's on-screen blob radius from [gridStepDegrees] rather than a hardcoded constant, so
 *  neighboring cells keep overlapping into a continuous cloud (not a grid of visible dots) at
 *  every zoom level even if `tools/build-catalogs.mjs` retunes the resolution. */
data class MilkyWayCatalog(
    val gridStepDegrees: Float,
    val cells: List<MilkyWayCell>,
)
