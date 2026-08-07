package com.astrocompass.astro.coords

import com.astrocompass.astro.Angle

/** Right ascension / declination, referred to a specific equinox (usually J2000 or date). */
data class EquatorialCoordinates(val rightAscension: Angle, val declination: Angle)
