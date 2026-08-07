package com.astrocompass.astro.ephemeris

import com.astrocompass.astro.Angle
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.coords.Ecliptic
import com.astrocompass.astro.coords.EquatorialCoordinates
import com.astrocompass.astro.coords.Precession
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** J2000 osculating Keplerian elements and their per-Julian-century rates, valid 1800-2050 AD
 *  (JPL "Keplerian Elements for Approximate Positions of the Major Planets", Standish & Williams,
 *  ssd.jpl.nasa.gov/planets/approx_pos.html). Stated accuracy: ~25" for the terrestrial planets,
 *  a few arcminutes for the outer planets over this date range — comfortably inside this app's
 *  0.5-2° pointing budget without the extra higher-order (b, c, s, f) terms JPL adds for the
 *  outer planets' longer 3000 BC-3000 AD table. */
private data class OrbitalElements(
    val a: Double, val aDot: Double,
    val e: Double, val eDot: Double,
    val iDeg: Double, val iDotDeg: Double,
    val lDeg: Double, val lDotDeg: Double,
    val longPeriDeg: Double, val longPeriDotDeg: Double,
    val longNodeDeg: Double, val longNodeDotDeg: Double,
)

private val EARTH_ELEMENTS = OrbitalElements(
    a = 1.00000261, aDot = 0.00000562,
    e = 0.01671123, eDot = -0.00004392,
    iDeg = -0.00001531, iDotDeg = -0.01294668,
    lDeg = 100.46457166, lDotDeg = 35999.37244981,
    longPeriDeg = 102.93768193, longPeriDotDeg = 0.32327364,
    longNodeDeg = 0.0, longNodeDotDeg = 0.0,
)

private val PLANET_ELEMENTS: Map<SolarSystemBody, OrbitalElements> = mapOf(
    SolarSystemBody.MERCURY to OrbitalElements(
        a = 0.38709927, aDot = 0.00000037,
        e = 0.20563593, eDot = 0.00001906,
        iDeg = 7.00497902, iDotDeg = -0.00594749,
        lDeg = 252.25032350, lDotDeg = 149472.67411175,
        longPeriDeg = 77.45779628, longPeriDotDeg = 0.16047689,
        longNodeDeg = 48.33076593, longNodeDotDeg = -0.12534081,
    ),
    SolarSystemBody.VENUS to OrbitalElements(
        a = 0.72333566, aDot = 0.00000390,
        e = 0.00677672, eDot = -0.00004107,
        iDeg = 3.39467605, iDotDeg = -0.00078890,
        lDeg = 181.97909950, lDotDeg = 58517.81538729,
        longPeriDeg = 131.60246718, longPeriDotDeg = 0.00268329,
        longNodeDeg = 76.67984255, longNodeDotDeg = -0.27769418,
    ),
    SolarSystemBody.MARS to OrbitalElements(
        a = 1.52371034, aDot = 0.00001847,
        e = 0.09339410, eDot = 0.00007882,
        iDeg = 1.84969142, iDotDeg = -0.00813131,
        lDeg = -4.55343205, lDotDeg = 19140.30268499,
        longPeriDeg = -23.94362959, longPeriDotDeg = 0.44441088,
        longNodeDeg = 49.55953891, longNodeDotDeg = -0.29257343,
    ),
    SolarSystemBody.JUPITER to OrbitalElements(
        a = 5.20288700, aDot = -0.00011607,
        e = 0.04838624, eDot = -0.00013253,
        iDeg = 1.30439695, iDotDeg = -0.00183714,
        lDeg = 34.39644051, lDotDeg = 3034.74612775,
        longPeriDeg = 14.72847983, longPeriDotDeg = 0.21252668,
        longNodeDeg = 100.47390909, longNodeDotDeg = 0.20469106,
    ),
    SolarSystemBody.SATURN to OrbitalElements(
        a = 9.53667594, aDot = -0.00125060,
        e = 0.05386179, eDot = -0.00050991,
        iDeg = 2.48599187, iDotDeg = 0.00193609,
        lDeg = 49.95424423, lDotDeg = 1222.49362201,
        longPeriDeg = 92.59887831, longPeriDotDeg = -0.41897216,
        longNodeDeg = 113.66242448, longNodeDotDeg = -0.28867794,
    ),
    SolarSystemBody.URANUS to OrbitalElements(
        a = 19.18916464, aDot = -0.00196176,
        e = 0.04725744, eDot = -0.00004397,
        iDeg = 0.77263783, iDotDeg = -0.00242939,
        lDeg = 313.23810451, lDotDeg = 428.48202785,
        longPeriDeg = 170.95427630, longPeriDotDeg = 0.40805281,
        longNodeDeg = 74.01692503, longNodeDotDeg = 0.04240589,
    ),
    SolarSystemBody.NEPTUNE to OrbitalElements(
        a = 30.06992276, aDot = 0.00026291,
        e = 0.00859048, eDot = 0.00005105,
        iDeg = 1.77004347, iDotDeg = 0.00035372,
        lDeg = -55.12002969, lDotDeg = 218.45945325,
        longPeriDeg = 44.96476227, longPeriDotDeg = -0.32241464,
        longNodeDeg = 131.78422574, longNodeDotDeg = -0.00508664,
    ),
)

object PlanetEphemeris {

    /** Geocentric equatorial position, referred to J2000 — the frame the orbital elements are
     *  defined in. Callers combine this with [com.astrocompass.astro.coords.Precession] to get
     *  coordinates of date, the same way catalog stars are precessed from their J2000 entries. */
    fun geocentricEquatorialJ2000(body: SolarSystemBody, julianCenturiesJ2000: Double): EquatorialCoordinates {
        require(body != SolarSystemBody.SUN && body != SolarSystemBody.MOON) {
            "PlanetEphemeris only covers the 7 non-Earth planets"
        }
        val elements = requireNotNull(PLANET_ELEMENTS[body])

        val earthPosition = heliocentricEcliptic(EARTH_ELEMENTS, julianCenturiesJ2000)
        val planetPosition = heliocentricEcliptic(elements, julianCenturiesJ2000)
        val geocentric = planetPosition - earthPosition

        val r = geocentric.length
        val longitude = Angle.ofRadians(atan2(geocentric.y, geocentric.x)).normalized()
        val latitude = Angle.ofRadians(asin((geocentric.z / r).coerceIn(-1.0, 1.0)))

        val obliquityJ2000 = Ecliptic.meanObliquity(0.0)
        return Ecliptic.toEquatorial(
            com.astrocompass.astro.coords.EclipticCoordinates(longitude, latitude),
            obliquityJ2000,
        )
    }

    fun geocentricEquatorialOfDate(body: SolarSystemBody, julianCenturiesJ2000: Double): EquatorialCoordinates =
        Precession.j2000ToDate(geocentricEquatorialJ2000(body, julianCenturiesJ2000), julianCenturiesJ2000)

    /** Heliocentric ecliptic (J2000) rectangular position, in AU. */
    private fun heliocentricEcliptic(elements: OrbitalElements, t: Double): Vector3 {
        val a = elements.a + elements.aDot * t
        val e = elements.e + elements.eDot * t
        val iRad = Angle.ofDegrees(elements.iDeg + elements.iDotDeg * t).radians
        val lRad = Angle.ofDegrees(elements.lDeg + elements.lDotDeg * t).radians
        val longPeriRad = Angle.ofDegrees(elements.longPeriDeg + elements.longPeriDotDeg * t).radians
        val longNodeRad = Angle.ofDegrees(elements.longNodeDeg + elements.longNodeDotDeg * t).radians

        val argPeri = longPeriRad - longNodeRad
        val meanAnomaly = wrapPi(lRad - longPeriRad)
        val eccentricAnomaly = solveKepler(meanAnomaly, e)

        val xOrbit = a * (cos(eccentricAnomaly) - e)
        val yOrbit = a * sqrt(1 - e * e) * sin(eccentricAnomaly)

        val cosArgPeri = cos(argPeri)
        val sinArgPeri = sin(argPeri)
        val cosNode = cos(longNodeRad)
        val sinNode = sin(longNodeRad)
        val cosI = cos(iRad)
        val sinI = sin(iRad)

        val x = (cosArgPeri * cosNode - sinArgPeri * sinNode * cosI) * xOrbit +
            (-sinArgPeri * cosNode - cosArgPeri * sinNode * cosI) * yOrbit
        val y = (cosArgPeri * sinNode + sinArgPeri * cosNode * cosI) * xOrbit +
            (-sinArgPeri * sinNode + cosArgPeri * cosNode * cosI) * yOrbit
        val z = (sinArgPeri * sinI) * xOrbit + (cosArgPeri * sinI) * yOrbit

        return Vector3(x, y, z)
    }

    private fun wrapPi(radians: Double): Double {
        var r = radians % (2 * kotlin.math.PI)
        if (r > kotlin.math.PI) r -= 2 * kotlin.math.PI
        if (r < -kotlin.math.PI) r += 2 * kotlin.math.PI
        return r
    }

    /** Newton-Raphson solution of Kepler's equation E - e sin(E) = M. */
    private fun solveKepler(meanAnomaly: Double, eccentricity: Double): Double {
        var e = meanAnomaly
        repeat(10) {
            val delta = (e - eccentricity * sin(e) - meanAnomaly) / (1 - eccentricity * cos(e))
            e -= delta
            if (abs(delta) < 1e-12) return e
        }
        return e
    }
}
