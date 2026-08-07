package com.astroguider.catalog

/**
 * Ranks by match quality first (exact > prefix > contains), then by brightness within a tier.
 * An exact match always appears regardless of magnitude -- a user who typed the exact name or
 * designation gets it even if it is faint. Non-exact matches are subject to [magnitudeLimit];
 * objects with no known magnitude are never dropped by that limit, only sorted after every
 * magnitude-bearing match in the same tier (Kotlin's Float ordering already places NaN last).
 */
object CatalogSearch {

    private const val EXACT = 0
    private const val PREFIX = 1
    private const val CONTAINS = 2

    fun search(
        query: String,
        objects: List<SkyObject>,
        magnitudeLimit: Float = 13f,
        category: SearchCategory? = null,
    ): List<SkyObject> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isEmpty()) return emptyList()

        return objects
            .asSequence()
            .filter { category == null || it.searchCategory == category }
            .mapNotNull { obj ->
                val tier = bestTier(normalizedQuery, searchKeysFor(obj)) ?: return@mapNotNull null
                if (tier != EXACT && !(obj.magnitude.isNaN() || obj.magnitude <= magnitudeLimit)) {
                    return@mapNotNull null
                }
                obj to tier
            }
            .sortedWith(compareBy({ it.second }, { it.first.magnitude }))
            .map { it.first }
            .toList()
    }

    private fun bestTier(query: String, keys: List<String>): Int? =
        keys.mapNotNull { matchTier(query, it) }.minOrNull()

    private fun matchTier(query: String, key: String): Int? = when {
        key == query -> EXACT
        key.startsWith(query) -> PREFIX
        key.contains(query) -> CONTAINS
        else -> null
    }

    private fun normalize(text: String): String =
        text.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun searchKeysFor(obj: SkyObject): List<String> = when (obj) {
        is StarObject -> obj.searchKeys()
        is DeepSkyObject -> obj.searchKeys()
        is SolarSystemObject -> listOf(obj.displayName.lowercase())
    }
}
