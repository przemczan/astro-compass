package com.astrocompass.platesolve

/** No AVFoundation binding yet -- see composeApp/src/iosMain/README.md for the Android-only
 *  status of this build. Always fails. */
class StubCameraCapture : CameraCapture {
    override suspend fun captureFrame(): CapturedFrame? = null
}
