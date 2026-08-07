package com.astroguider.astro.coords

import com.astroguider.astro.Angle

/** Right ascension / declination, referred to a specific equinox (usually J2000 or date). */
data class EquatorialCoordinates(val rightAscension: Angle, val declination: Angle)
