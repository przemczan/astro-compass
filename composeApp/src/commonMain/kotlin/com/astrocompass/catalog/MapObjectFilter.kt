package com.astrocompass.catalog

/** The toggleable categories on the sky map's filter sheet -- see
 *  [com.astrocompass.ui.components.MapFilterSheet]. */
enum class MapObjectCategory(val label: String) {
    SOLAR_SYSTEM("Solar System"),
    GALAXIES("Galaxies"),
    NEBULAE("Nebulae"),
    CLUSTERS("Clusters"),
    /** OpenNGC's STAR/DOUBLE_STAR/NOVA/OTHER entries -- the same ones [SkyMap][com.astrocompass.ui.components.SkyMap]
     *  already draws with the "star-like" diamond glyph rather than an ellipse/circle/square, not a
     *  fifth kind of deep-sky object. Many of the STAR ones (e.g. NGC 733) are historical NGC/IC
     *  numbers that later turned out to just be ordinary foreground stars, not the nebula/cluster/
     *  galaxy originally catalogued -- OpenNGC keeps the entry (marked type "*") rather than
     *  removing it, so it still needs its own toggle to hide. */
    OTHER("Other"),
}

/** Which [MapObjectCategory] a DSO's [SkyObjectType] belongs to. */
fun SkyObjectType.toMapObjectCategory(): MapObjectCategory = when (this) {
    SkyObjectType.GALAXY, SkyObjectType.GALAXY_PAIR, SkyObjectType.GALAXY_TRIPLET, SkyObjectType.GALAXY_GROUP ->
        MapObjectCategory.GALAXIES
    SkyObjectType.OPEN_CLUSTER, SkyObjectType.GLOBULAR_CLUSTER, SkyObjectType.ASSOCIATION, SkyObjectType.CLUSTER_AND_NEBULA ->
        MapObjectCategory.CLUSTERS
    SkyObjectType.PLANETARY_NEBULA, SkyObjectType.HII_REGION, SkyObjectType.DARK_NEBULA, SkyObjectType.EMISSION_NEBULA,
    SkyObjectType.NEBULA, SkyObjectType.REFLECTION_NEBULA, SkyObjectType.SUPERNOVA_REMNANT ->
        MapObjectCategory.NEBULAE
    SkyObjectType.STAR, SkyObjectType.DOUBLE_STAR, SkyObjectType.NOVA, SkyObjectType.OTHER -> MapObjectCategory.OTHER
}

/** Which of the [MapObjectCategory] toggles are currently on -- a plain data class (not a raw
 *  predicate lambda) specifically so it has real structural equality: passed as
 *  [com.astrocompass.ui.components.rememberSkyMapSnapshot]'s `filterKey`, two instances with the
 *  same field values compare equal even though they're different objects, which is what lets
 *  Compose's `remember` skip re-filtering the whole catalog on recompositions where nothing about
 *  the filter actually changed. Real stars ([com.astrocompass.catalog.StarObject], not the
 *  [MapObjectCategory.OTHER] DSO entries above) are never gated by this at all. */
data class MapObjectFilter(
    val showSolarSystem: Boolean = true,
    val showGalaxies: Boolean = true,
    val showNebulae: Boolean = true,
    val showClusters: Boolean = true,
    val showOther: Boolean = true,
    /** Hides anything dimmer than this magnitude -- null (the default) applies no limit at all,
     *  matching the map's behavior before this existed. [SolarSystemObject]s and the ~10% of DSOs
     *  OpenNGC has no magnitude for are exempt rather than hidden, since [SkyObject.magnitude]'s
     *  NaN convention for both means there's nothing to compare against -- see
     *  [com.astrocompass.ui.components.MapFilterSheet]'s "(only for those we have the info)" slider. */
    val maxMagnitude: Float? = null,
) {
    fun matches(obj: SkyObject): Boolean {
        val categoryMatches = when (obj) {
            is SolarSystemObject -> showSolarSystem
            is DeepSkyObject -> isShown(obj.type.toMapObjectCategory())
            else -> true
        }
        if (!categoryMatches) return false
        return maxMagnitude == null || obj.magnitude.isNaN() || obj.magnitude <= maxMagnitude
    }

    /** Per-category get/set, so a filter-toggle UI can iterate [MapObjectCategory.entries] instead
     *  of writing one row per field by hand. */
    fun isShown(category: MapObjectCategory): Boolean = when (category) {
        MapObjectCategory.SOLAR_SYSTEM -> showSolarSystem
        MapObjectCategory.GALAXIES -> showGalaxies
        MapObjectCategory.NEBULAE -> showNebulae
        MapObjectCategory.CLUSTERS -> showClusters
        MapObjectCategory.OTHER -> showOther
    }

    fun withShown(category: MapObjectCategory, shown: Boolean): MapObjectFilter = when (category) {
        MapObjectCategory.SOLAR_SYSTEM -> copy(showSolarSystem = shown)
        MapObjectCategory.GALAXIES -> copy(showGalaxies = shown)
        MapObjectCategory.NEBULAE -> copy(showNebulae = shown)
        MapObjectCategory.CLUSTERS -> copy(showClusters = shown)
        MapObjectCategory.OTHER -> copy(showOther = shown)
    }
}
