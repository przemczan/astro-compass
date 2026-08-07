package com.astroguider.catalog

private val GREEK_LETTERS = mapOf(
    "Alp" to "α", "Bet" to "β", "Gam" to "γ", "Del" to "δ", "Eps" to "ε", "Zet" to "ζ",
    "Eta" to "η", "The" to "θ", "Iot" to "ι", "Kap" to "κ", "Lam" to "λ", "Mu" to "μ",
    "Nu" to "ν", "Xi" to "ξ", "Omi" to "ο", "Pi" to "π", "Rho" to "ρ", "Sig" to "σ",
    "Tau" to "τ", "Ups" to "υ", "Phi" to "φ", "Chi" to "χ", "Psi" to "ψ", "Ome" to "ω",
)

/**
 * Formats a Bayer/Flamsteed designation for display, e.g. "α Lyr" or "3 Lyr". The catalog blob
 * stores the plain ASCII abbreviation (e.g. "Alp") rather than the Greek letter, so that
 * [CatalogSearch] can match typed queries like "alp lyr" without needing to normalize Unicode.
 */
object BayerFlamsteed {

    fun formatForDisplay(bayer: String, flamsteed: Int, constellation: String): String {
        val symbol = GREEK_LETTERS[bayer] ?: bayer.ifEmpty { null }
        val parts = listOfNotNull(
            symbol,
            flamsteed.takeIf { it > 0 && symbol == null }?.toString(),
            constellation.ifEmpty { null },
        )
        return parts.joinToString(" ")
    }

    /** Lowercased, ASCII search keys a user might type: "alp lyr", "3 lyr". */
    fun searchKeys(bayer: String, flamsteed: Int, constellation: String): List<String> {
        val keys = mutableListOf<String>()
        if (bayer.isNotEmpty() && constellation.isNotEmpty()) {
            keys += "$bayer $constellation".lowercase()
        }
        if (flamsteed > 0 && constellation.isNotEmpty()) {
            keys += "$flamsteed $constellation".lowercase()
        }
        return keys
    }
}
