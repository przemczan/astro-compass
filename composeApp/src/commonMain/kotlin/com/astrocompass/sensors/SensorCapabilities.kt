package com.astrocompass.sensors

data class SensorCapabilities(
    val hasGyroscope: Boolean,
    val hasMagnetometer: Boolean,
    val hasAccelerometer: Boolean,
)

/**
 * The app picks the pointing sensor itself, by capability -- this is never a user decision.
 * See [SensorSource] for why each choice is made.
 */
fun SensorCapabilities.defaultSource(): SensorSource = when {
    hasGyroscope -> SensorSource.GAME_ROTATION_VECTOR
    else -> SensorSource.GEOMAGNETIC_ROTATION_VECTOR
}
