package com.astrocompass.location

import com.astrocompass.astro.Angle

data class ObserverLocation(
    val latitude: Angle,
    val longitude: Angle,
    val elevationMeters: Double = 0.0,
)
