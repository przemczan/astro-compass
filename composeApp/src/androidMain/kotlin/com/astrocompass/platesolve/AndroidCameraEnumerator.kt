package com.astrocompass.platesolve

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log

private const val TAG = "CameraEnumerator"

/** Listing camera IDs/characteristics needs no runtime permission (only opening one does), so this
 *  works even before the CAMERA permission request in `MainActivity` is answered. */
class AndroidCameraEnumerator(private val context: Context) : CameraEnumerator {
    override fun listCameras(): List<CameraDescriptor> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val seenPerFacing = mutableMapOf<CameraFacing, Int>()
        val descriptors = mutableListOf<CameraDescriptor>()

        // Per-id, not wrapping the whole cameraIdList.map -- one camera failing to report its
        // characteristics (a flaky OEM HAL) shouldn't hide every other camera from the wizard's
        // selector.
        for (id in manager.cameraIdList) {
            try {
                val characteristics = manager.getCameraCharacteristics(id)
                val facing = facingOf(characteristics)
                descriptors += CameraDescriptor(id, physicalId = null, label = nextLabel(seenPerFacing, facing), facing)
                descriptors += physicalLensDescriptors(manager, id, characteristics, facing)
            } catch (e: CameraAccessException) {
                Log.w(TAG, "listCameras: couldn't read characteristics for camera $id", e)
            }
        }
        return descriptors
    }

    private fun facingOf(characteristics: CameraCharacteristics): CameraFacing =
        when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> CameraFacing.BACK
            CameraCharacteristics.LENS_FACING_FRONT -> CameraFacing.FRONT
            else -> CameraFacing.OTHER
        }

    private fun nextLabel(seenPerFacing: MutableMap<CameraFacing, Int>, facing: CameraFacing): String {
        val ordinal = (seenPerFacing[facing] ?: 0) + 1
        seenPerFacing[facing] = ordinal
        val baseLabel = baseLabelOf(facing)
        return if (ordinal == 1) baseLabel else "$baseLabel ($ordinal)"
    }

    private fun baseLabelOf(facing: CameraFacing) = when (facing) {
        CameraFacing.BACK -> "Back"
        CameraFacing.FRONT -> "Front"
        CameraFacing.OTHER -> "Other"
    }

    /**
     * A logical multi-camera (e.g. a fused main+wide rear "Back" id) hides its underlying physical
     * lenses from [CameraManager.cameraIdList] entirely -- they're only reachable by asking this
     * logical id for its [CameraCharacteristics.getPhysicalCameraIds] and then targeting one via
     * `OutputConfiguration.setPhysicalCameraId` when building an actual capture/preview session
     * (see `CameraPreviewSurface`'s and `AndroidCameraCapture`'s Android implementations). Exposed
     * here as separate selectable entries, labeled by relative focal length, since "the fused
     * default" and "specifically the wide lens" frame very differently through a telescope.
     */
    private fun physicalLensDescriptors(
        manager: CameraManager,
        logicalId: String,
        logicalCharacteristics: CameraCharacteristics,
        facing: CameraFacing,
    ): List<CameraDescriptor> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        val capabilities = logicalCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return emptyList()
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA !in capabilities) return emptyList()

        val focalLengthByPhysicalId = logicalCharacteristics.physicalCameraIds.mapNotNull { physicalId ->
            try {
                val focalLength = manager.getCameraCharacteristics(physicalId)
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull()
                focalLength?.let { physicalId to it }
            } catch (e: CameraAccessException) {
                Log.w(TAG, "listCameras: couldn't read characteristics for physical camera $physicalId", e)
                null
            }
        }.sortedBy { (_, focalLength) -> focalLength }

        val baseLabel = baseLabelOf(facing)
        return focalLengthByPhysicalId.mapIndexed { index, (physicalId, _) ->
            val lensLabel = when {
                focalLengthByPhysicalId.size == 1 -> baseLabel
                index == 0 -> "$baseLabel (Wide)"
                index == focalLengthByPhysicalId.lastIndex -> "$baseLabel (Tele)"
                else -> "$baseLabel (Main)"
            }
            CameraDescriptor(logicalId, physicalId, lensLabel, facing)
        }
    }
}
