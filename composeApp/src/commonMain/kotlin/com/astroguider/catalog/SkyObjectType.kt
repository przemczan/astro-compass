package com.astroguider.catalog

/**
 * Mirrors OpenNGC's `Type` column. Ordinal order is load-bearing: [tools/build-catalogs.mjs]
 * encodes this same order as a single byte per deep-sky object, so entries must never be
 * reordered or removed -- only appended.
 */
enum class SkyObjectType {
    STAR, DOUBLE_STAR, ASSOCIATION, OPEN_CLUSTER, GLOBULAR_CLUSTER, CLUSTER_AND_NEBULA,
    GALAXY, GALAXY_PAIR, GALAXY_TRIPLET, GALAXY_GROUP,
    PLANETARY_NEBULA, HII_REGION, DARK_NEBULA, EMISSION_NEBULA, NEBULA, REFLECTION_NEBULA,
    SUPERNOVA_REMNANT, NOVA, OTHER;

    companion object {
        fun fromOrdinalOrOther(ordinal: Int): SkyObjectType = entries.getOrElse(ordinal) { OTHER }
    }
}
