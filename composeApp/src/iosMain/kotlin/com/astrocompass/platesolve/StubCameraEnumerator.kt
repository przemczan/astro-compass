package com.astrocompass.platesolve

/** No AVFoundation camera binding yet, same as [StubCameraCapture]. */
class StubCameraEnumerator : CameraEnumerator {
    override fun listCameras(): List<CameraDescriptor> = emptyList()
}
