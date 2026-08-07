package com.astroguider.catalog

import com.astroguider.astro.Angle
import com.astroguider.astro.coords.EquatorialCoordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogSearchTest {

    private val vega = StarObject(
        hygId = 90979, hip = 91262, properName = "Vega", bayer = "Alp", flamsteed = 3,
        constellation = "Lyr", j2000 = coords(18.616, 38.78), magnitude = 0.03f,
    )
    private val unnamedLyraeStar = StarObject(
        hygId = 1, hip = 0, properName = "", bayer = "Bet", flamsteed = 0,
        constellation = "Lyr", j2000 = coords(18.83, 33.36), magnitude = 3.5f,
    )
    private val andromedaGalaxy = DeepSkyObject(
        catalogDesignation = "NGC0224", messier = 31, type = SkyObjectType.GALAXY,
        j2000 = coords(0.712, 41.27), magnitude = 3.44f, constellation = "And", commonName = "Andromeda Galaxy",
    )
    private val orionNebula = DeepSkyObject(
        catalogDesignation = "NGC1976", messier = 42, type = SkyObjectType.NEBULA,
        j2000 = coords(5.588, -5.39), magnitude = 4.0f, constellation = "Ori", commonName = "Orion Nebula",
    )
    private val faintUnnamedNebula = DeepSkyObject(
        catalogDesignation = "NGC9999", messier = 0, type = SkyObjectType.NEBULA,
        j2000 = coords(10.0, 10.0), magnitude = Float.NaN, constellation = "Leo", commonName = "",
    )
    private val faintNamedNebula = DeepSkyObject(
        catalogDesignation = "NGC8888", messier = 0, type = SkyObjectType.NEBULA,
        j2000 = coords(11.0, -20.0), magnitude = 10.0f, constellation = "Vul", commonName = "Faint Nebula",
    )

    private val catalog = listOf(vega, unnamedLyraeStar, andromedaGalaxy, orionNebula, faintUnnamedNebula, faintNamedNebula)

    private fun coords(raHours: Double, decDeg: Double) =
        EquatorialCoordinates(Angle.ofHours(raHours), Angle.ofDegrees(decDeg))

    @Test
    fun messierNumber_resolvesTheGalaxy() {
        val results = CatalogSearch.search("M31", catalog)
        assertEquals(andromedaGalaxy, results.first())
    }

    @Test
    fun commonName_resolvesTheGalaxy() {
        val results = CatalogSearch.search("andromeda", catalog)
        assertEquals(andromedaGalaxy, results.first())
    }

    @Test
    fun ngcDesignationWithSpace_resolvesTheGalaxy() {
        val results = CatalogSearch.search("NGC 224", catalog)
        assertEquals(andromedaGalaxy, results.first())
    }

    @Test
    fun properName_resolvesVega() {
        val results = CatalogSearch.search("vega", catalog)
        assertEquals(vega, results.first())
    }

    @Test
    fun bayerFlamsteedAbbreviation_resolvesVega() {
        val results = CatalogSearch.search("alp lyr", catalog)
        assertEquals(vega, results.first())
    }

    @Test
    fun withinTheSameMatchTier_brighterObjectRanksFirst() {
        // "Orion Nebula" (mag 4.0) and "Faint Nebula" (mag 10.0) both match "nebula" only by
        // substring (neither name starts with it), so they land in the same tier; the brighter
        // one must sort first.
        val results = CatalogSearch.search("nebula", catalog)
        val orionIndex = results.indexOf(orionNebula)
        val faintIndex = results.indexOf(faintNamedNebula)
        assertTrue(orionIndex in 0 until faintIndex)
    }

    @Test
    fun nullMagnitudeObject_stillAppears_orderedAfterMagnitudeBearingMatches() {
        val results = CatalogSearch.search("ngc", catalog, magnitudeLimit = 13f)
        assertTrue(faintUnnamedNebula in results)
        val lastMagnitudeBearingIndex = results.indexOfLast { !it.magnitude.isNaN() }
        assertTrue(results.indexOf(faintUnnamedNebula) > lastMagnitudeBearingIndex)
    }

    @Test
    fun exactDesignationMatch_bypassesTheMagnitudeLimit() {
        // Orion Nebula is magnitude 4.0 -- a limit of 1.0 would normally exclude it, but an
        // exact designation match must win regardless of magnitude.
        val exactResults = CatalogSearch.search("ngc1976", catalog, magnitudeLimit = 1f)
        assertTrue(orionNebula in exactResults)

        // The same tight limit, reached only via a non-exact (contains) match, does exclude it.
        val fuzzyResults = CatalogSearch.search("nebula", catalog, magnitudeLimit = 1f)
        assertTrue(orionNebula !in fuzzyResults)
    }

    @Test
    fun categoryFilter_excludesOtherCategories() {
        val results = CatalogSearch.search("n", catalog, category = SearchCategory.GALAXY)
        assertTrue(results.all { it.searchCategory == SearchCategory.GALAXY })
    }
}
