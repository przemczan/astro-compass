package com.astrocompass.platesolve

/** One frame grabbed from [CameraCapture]: a plain luminance buffer ready for [CentroidDetector],
 *  paired with the intrinsics that were true for this specific frame (a device's focal length in
 *  pixels depends on the capture resolution, so this travels with the frame rather than being a
 *  fixed constant). */
data class CapturedFrame(val luminance: FloatArray, val width: Int, val height: Int, val intrinsics: CameraIntrinsics)
