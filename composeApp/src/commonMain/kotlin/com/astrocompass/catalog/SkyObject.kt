package com.astrocompass.catalog

import com.astrocompass.astro.coords.EquatorialCoordinates
import com.astrocompass.astro.coords.Precession
import com.astrocompass.astro.ephemeris.SolarSystemBody
import com.astrocompass.astro.ephemeris.SolarSystemEphemeris

/** Anything the Search screen can find and the Guidance screen can point at. */
sealed interface SkyObject {
    val id: String
    val displayName: String
    /** V-band magnitude, or [Float.NaN] if unknown. Fainter/unknown objects sort last. */
    val magnitude: Float
    val searchCategory: SearchCategory

    /** Equatorial position at the given moment -- J2000 catalog entries are precessed to date;
     *  solar system bodies are already computed "of date" by their ephemeris. */
    fun equatorialAt(julianCenturiesJ2000: Double): EquatorialCoordinates
}

data class StarObject(
    val hygId: Int,
    val hip: Int,
    val properName: String,
    val bayer: String,
    val flamsteed: Int,
    val constellation: String,
    val j2000: EquatorialCoordinates,
    override val magnitude: Float,
) : SkyObject {
    override val id: String = if (hip > 0) "HIP$hip" else "HYG$hygId"
    override val searchCategory = SearchCategory.STAR

    override val displayName: String
        get() = properName.ifEmpty {
            BayerFlamsteed.formatForDisplay(bayer, flamsteed, constellation).ifEmpty { id }
        }

    fun searchKeys(): List<String> = buildList {
        if (properName.isNotEmpty()) add(properName.lowercase())
        addAll(BayerFlamsteed.searchKeys(bayer, flamsteed, constellation))
        if (hip > 0) add("hip $hip")
    }

    override fun equatorialAt(julianCenturiesJ2000: Double): EquatorialCoordinates =
        Precession.j2000ToDate(j2000, julianCenturiesJ2000)
}

data class DeepSkyObject(
    /** OpenNGC's raw designation, e.g. "NGC0224" or "IC0001". */
    val catalogDesignation: String,
    /** Messier number, or 0 if this object has none. */
    val messier: Int,
    val type: SkyObjectType,
    val j2000: EquatorialCoordinates,
    override val magnitude: Float,
    val constellation: String,
    val commonName: String,
    /** Apparent long axis in arcminutes, or [Float.NaN] if OpenNGC has no size measurement for
     *  this object -- same "unknown" convention as [magnitude]. */
    val majorAxisArcmin: Float = Float.NaN,
    /** Apparent short axis in arcminutes, or [Float.NaN] if unmeasured -- notably common even when
     *  [majorAxisArcmin] is known (e.g. small/round objects), not just when the whole object is
     *  unmeasured. Callers that need a size for a circular fallback should treat NaN here as
     *  "same as [majorAxisArcmin]", not as "no size at all". */
    val minorAxisArcmin: Float = Float.NaN,
    /** Position angle in degrees, measured east of north -- how [majorAxisArcmin] is rotated
     *  relative to true north at this object's location. [Float.NaN] if unmeasured (common for
     *  round objects, where orientation has no visible meaning anyway). */
    val positionAngleDegrees: Float = Float.NaN,
) : SkyObject {
    override val id: String = catalogDesignation
    override val searchCategory = type.searchCategory()

    /** "NGC 224" / "IC 1" -- catalog letters, space, number with leading zeros stripped. */
    val prettyDesignation: String
        get() {
            val prefixLength = catalogDesignation.indexOfFirst { it.isDigit() }.let { if (it < 0) catalogDesignation.length else it }
            val prefix = catalogDesignation.substring(0, prefixLength)
            val number = catalogDesignation.substring(prefixLength).trimStart('0').ifEmpty { "0" }
            return "$prefix $number"
        }

    val messierLabel: String? get() = if (messier > 0) "M$messier" else null

    override val displayName: String
        get() = commonName.ifEmpty { messierLabel ?: prettyDesignation }

    fun searchKeys(): List<String> = buildList {
        add(catalogDesignation.lowercase())
        add(prettyDesignation.lowercase())
        messierLabel?.let { add(it.lowercase()) }
        if (commonName.isNotEmpty()) add(commonName.lowercase())
    }

    override fun equatorialAt(julianCenturiesJ2000: Double): EquatorialCoordinates =
        Precession.j2000ToDate(j2000, julianCenturiesJ2000)
}

data class SolarSystemObject(val body: SolarSystemBody) : SkyObject {
    override val id: String = body.name
    override val displayName: String = body.name.lowercase().replaceFirstChar { it.uppercase() }
    override val magnitude: Float = Float.NaN
    override val searchCategory = SearchCategory.PLANET

    override fun equatorialAt(julianCenturiesJ2000: Double): EquatorialCoordinates =
        SolarSystemEphemeris.geocentricEquatorialOfDate(body, julianCenturiesJ2000)
}
