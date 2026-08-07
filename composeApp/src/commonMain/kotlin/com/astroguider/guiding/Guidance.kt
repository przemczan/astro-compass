package com.astroguider.guiding

data class Guidance(
    val separationDegrees: Double,
    val altitudeDeltaDegrees: Double,
    val crossTrackDeltaDegrees: Double,
    /** 0 = arrow points "up" (increase altitude), 90 = "right" (increase azimuth). */
    val arrowAngleDegrees: Double,
    val isOnTarget: Boolean,
)
