package com.astrocompass.astro.ephemeris

import com.astrocompass.astro.coords.EquatorialCoordinates

/** Single entry point for "where is this solar system body right now", of-date equatorial. */
object SolarSystemEphemeris {

    fun geocentricEquatorialOfDate(body: SolarSystemBody, julianCenturiesJ2000: Double): EquatorialCoordinates =
        when (body) {
            SolarSystemBody.SUN -> SunEphemeris.geocentricEquatorial(julianCenturiesJ2000)
            SolarSystemBody.MOON -> MoonEphemeris.geocentricEquatorial(julianCenturiesJ2000)
            else -> PlanetEphemeris.geocentricEquatorialOfDate(body, julianCenturiesJ2000)
        }
}
