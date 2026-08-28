package com.astrocompass.platesolve

/** Which side of the phone a camera looks out of. */
enum class CameraFacing { BACK, FRONT, OTHER }

/**
 * One selectable camera: a platform-specific, *openable* [id] (opaque outside the platform
 * implementation -- Android's `camera2` string ID for a top-level `cameraIdList` entry) plus a
 * human-readable [label] for a picker UI.
 *
 * [physicalId] is null for an ordinary camera, or names a specific physical lens underneath a
 * logical multi-camera [id] (e.g. the wide lens fused into a phone's main "Back" camera) -- such
 * lenses aren't independently openable; a platform implementation must open [id] and separately
 * target [physicalId] for its actual output. Kept alongside [id] rather than replacing it since
 * the two id spaces are genuinely different: [id] answers "which camera device to open," while
 * [physicalId] answers "which of its lenses to read a stream from."
 */
data class CameraDescriptor(val id: String, val physicalId: String?, val label: String, val facing: CameraFacing)

/**
 * Lists the cameras available for [com.astrocompass.ui.screens.PhoneCalibrationScreen]'s camera
 * selector. Platform-specific implementations are plain classes (not `expect`/`actual`), same
 * shape as [CameraCapture] -- the Android one needs a `Context` constructor parameter, so
 * `MainActivity`/`MainViewController` construct the platform instance and inject it.
 */
interface CameraEnumerator {
    /** Empty if the platform has no camera API to enumerate (e.g. iOS, until an AVFoundation
     *  binding exists) or none were found. */
    fun listCameras(): List<CameraDescriptor>
}
