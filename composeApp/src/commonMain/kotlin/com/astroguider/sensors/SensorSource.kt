package com.astroguider.sensors

/** Which Android composite orientation sensor is feeding [DeviceOrientation]. */
enum class SensorSource {
    /** Gyroscope + accelerometer, no magnetometer. Default when a gyroscope is present: immune
     *  to a metal tube and stepper motors, at the cost of unreferenced (drifting) yaw. */
    GAME_ROTATION_VECTOR,
    /** Gyroscope + accelerometer + magnetometer. Does not drift, but only trustworthy on a
     *  non-magnetic mount. User-selectable in Advanced settings, never auto-selected. */
    ROTATION_VECTOR,
    /** Accelerometer + magnetometer, no gyroscope. Degraded fallback for phones without a
     *  gyroscope -- the app states plainly that accuracy will be poor. */
    GEOMAGNETIC_ROTATION_VECTOR,
}
