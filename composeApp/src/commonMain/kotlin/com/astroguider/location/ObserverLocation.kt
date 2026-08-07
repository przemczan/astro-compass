package com.astroguider.location

import com.astroguider.astro.Angle

data class ObserverLocation(
    val latitude: Angle,
    val longitude: Angle,
    val elevationMeters: Double = 0.0,
)
