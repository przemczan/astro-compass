package com.astrocompass.platesolve

/**
 * Grabs a single still frame from the device's camera, on demand. Platform-specific
 * implementations are plain classes (not `expect`/`actual`), same shape as
 * [com.astrocompass.sensors.OrientationSensor] -- the Android one needs a `Context` constructor
 * parameter, so `MainActivity`/`MainViewController` construct the platform instance and inject it.
 */
interface CameraCapture {
    /** Null if the camera is unavailable, permission was denied, or the capture failed. */
    suspend fun captureFrame(): CapturedFrame?
}
