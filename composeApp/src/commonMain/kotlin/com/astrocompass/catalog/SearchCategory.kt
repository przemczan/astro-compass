package com.astrocompass.catalog

/** Coarse buckets for the Search screen's filter chips. Not every [SkyObjectType] maps to a
 *  dedicated chip -- rare ones fall into [OTHER], which is still searchable, just unfiltered. */
enum class SearchCategory { STAR, PLANET, GALAXY, NEBULA, CLUSTER, OTHER }

fun SkyObjectType.searchCategory(): SearchCategory = when (this) {
    SkyObjectType.GALAXY, SkyObjectType.GALAXY_PAIR, SkyObjectType.GALAXY_TRIPLET, SkyObjectType.GALAXY_GROUP ->
        SearchCategory.GALAXY
    SkyObjectType.PLANETARY_NEBULA, SkyObjectType.HII_REGION, SkyObjectType.DARK_NEBULA,
    SkyObjectType.EMISSION_NEBULA, SkyObjectType.NEBULA, SkyObjectType.REFLECTION_NEBULA,
    SkyObjectType.SUPERNOVA_REMNANT ->
        SearchCategory.NEBULA
    SkyObjectType.OPEN_CLUSTER, SkyObjectType.GLOBULAR_CLUSTER, SkyObjectType.CLUSTER_AND_NEBULA,
    SkyObjectType.ASSOCIATION ->
        SearchCategory.CLUSTER
    SkyObjectType.STAR, SkyObjectType.DOUBLE_STAR, SkyObjectType.NOVA, SkyObjectType.OTHER ->
        SearchCategory.OTHER
}
