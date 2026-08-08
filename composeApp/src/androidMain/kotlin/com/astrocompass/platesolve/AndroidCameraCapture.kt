package com.astrocompass.platesolve

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Long enough to pull real starlight above read noise on a phone sensor, short enough to avoid
 *  visible star trailing at a typical phone's focal length -- a starting point, not a calibrated
 *  value; may need on-device tuning per lens. */
private const val EXPOSURE_NANOS = 2_000_000_000L // 2s
private const val SENSITIVITY_ISO = 3200
private const val CAPTURE_TIMEOUT_MILLIS = 20_000L

/** Caps the requested frame resolution so [CentroidDetector]'s flood-fill and [PlateSolver]'s
 *  matching stay fast (see the tuning notes on `PlateSolverCatalogDensityTest`) -- full raw
 *  sensor resolution (often 12+ MP) is far more detail than star-blob detection needs. */
private const val MAX_FRAME_PIXELS = 1_500_000

private const val TAG = "PlateSolveCamera"

/**
 * Grabs one still frame directly via `android.hardware.camera2`, not CameraX.
 * `ProcessCameraProvider.getInstance()` was found to hang indefinitely on a real test device
 * (Pixel 10a, codename "stallion") inside CameraX's own internal lens-facing validation --
 * immune even to a hard JDK-level `Future.get(timeout)` run from a disposable background thread,
 * meaning the stall is not something app code can time out around. Raw camera2's `openCamera`/
 * `createCaptureSession`/`capture` are standard, documented-asynchronous, callback-driven APIs
 * with no such internal synchronous stall, so this sidesteps the issue rather than working
 * around it. See the `camerax-stallion-hang` note for the full trail.
 *
 * Headless (no preview `Surface`) -- the phone is mounted pointing at open sky, not held up to a
 * viewfinder. A dedicated [HandlerThread] carries every camera2 callback, since they all require
 * a [Handler] bound to a thread with a prepared `Looper`. Opens and closes the camera device
 * around each call rather than staying open, since this is strictly on-demand.
 */
class AndroidCameraCapture(private val context: Context) : CameraCapture {

    private val handlerThread = HandlerThread("PlateSolveCamera").apply { start() }
    private val handler = Handler(handlerThread.looper)

    override suspend fun captureFrame(): CapturedFrame? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "captureFrame: CAMERA permission not granted")
            return null
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        if (cameraId == null) {
            Log.w(TAG, "captureFrame: no back-facing camera found")
            return null
        }
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val frameSize = pickFrameSize(characteristics)
        val imageReader = ImageReader.newInstance(frameSize.width, frameSize.height, ImageFormat.YUV_420_888, 2)

        val result = try {
            withTimeoutOrNull(CAPTURE_TIMEOUT_MILLIS) {
                var device: CameraDevice? = null
                var session: CameraCaptureSession? = null
                try {
                    Log.d(TAG, "captureFrame: opening camera $cameraId")
                    device = openCamera(manager, cameraId)
                    Log.d(TAG, "captureFrame: creating capture session")
                    session = createCaptureSession(device, imageReader)
                    Log.d(TAG, "captureFrame: capturing (manual ${EXPOSURE_NANOS / 1_000_000}ms exposure)")
                    val image = captureManualExposureFrame(device, session, imageReader)
                    Log.d(TAG, "captureFrame: frame received")
                    try {
                        // A multi-megapixel plane copy -- real CPU work, kept off whichever thread
                        // the caller happens to be on so this stays a main-safe suspend function.
                        withContext(Dispatchers.Default) { toCapturedFrame(image, characteristics) }
                    } finally {
                        image.close()
                    }
                } finally {
                    session?.close()
                    device?.close()
                }
            }
        } finally {
            imageReader.close()
        }
        if (result == null) Log.w(TAG, "captureFrame: timed out or failed")
        return result
    }

    /** The largest available YUV_420_888 output size under [MAX_FRAME_PIXELS], or the smallest
     *  available size if every option exceeds it. */
    private fun pickFrameSize(characteristics: CameraCharacteristics): android.util.Size {
        val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = streamConfigMap.getOutputSizes(ImageFormat.YUV_420_888).toList()
        return sizes.filter { it.width.toLong() * it.height <= MAX_FRAME_PIXELS }.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minBy { it.width.toLong() * it.height }
    }

    private suspend fun openCamera(manager: CameraManager, cameraId: String): CameraDevice =
        suspendCancellableCoroutine { continuation ->
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        // If the wait was already cancelled the resume below is a silent no-op,
                        // and nothing else would ever close the device the framework just handed
                        // us -- it would linger as an active camera client for the whole process.
                        continuation.invokeOnCancellation { camera.close() }
                        continuation.resume(camera)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Camera disconnected"))
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Camera error: $error"))
                    }
                },
                handler,
            )
        }

    private suspend fun createCaptureSession(device: CameraDevice, imageReader: ImageReader): CameraCaptureSession =
        suspendCancellableCoroutine { continuation ->
            device.createCaptureSession(
                listOf(imageReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        continuation.invokeOnCancellation { session.close() }
                        continuation.resume(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Capture session configuration failed"))
                    }
                },
                handler,
            )
        }

    /** A single [CameraDevice.TEMPLATE_MANUAL] capture with fixed exposure/sensitivity/focus --
     *  star fields are far too dim for auto-exposure, which would otherwise leave nothing above
     *  [CentroidDetector]'s threshold. Unlike a repeating preview stream, a single manual request
     *  applies these settings from the start, so there's no auto-exposure warm-up frame to
     *  discard first. */
    private suspend fun captureManualExposureFrame(
        device: CameraDevice,
        session: CameraCaptureSession,
        imageReader: ImageReader,
    ): Image = suspendCancellableCoroutine { continuation ->
        var delivered = false
        imageReader.setOnImageAvailableListener(
            { reader ->
                val image = reader.acquireLatestImage()
                if (image == null) {
                    // no-op: nothing new to acquire
                } else if (!delivered) {
                    delivered = true
                    continuation.invokeOnCancellation { image.close() }
                    continuation.resume(image)
                } else {
                    image.close()
                }
            },
            handler,
        )

        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL).apply {
            addTarget(imageReader.surface)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, EXPOSURE_NANOS)
            set(CaptureRequest.SENSOR_FRAME_DURATION, EXPOSURE_NANOS)
            set(CaptureRequest.SENSOR_SENSITIVITY, SENSITIVITY_ISO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f) // 0 diopters = infinity
        }.build()

        session.capture(
            request,
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Capture failed: reason=${failure.reason}"))
                }
            },
            handler,
        )
    }

    /** Copies the Y plane of the `YUV_420_888` frame -- luminance, no color conversion needed --
     *  rotated to a consistent upright orientation. The app is locked to portrait
     *  (`android:screenOrientation="portrait"`), so the needed rotation reduces to
     *  `SENSOR_ORIENTATION` directly (the standard camera2 device-rotation formula's result for a
     *  fixed `Surface.ROTATION_0` target), letting [CentroidDetector] see a buffer that doesn't
     *  depend on `SENSOR_ORIENTATION` itself. */
    private fun toCapturedFrame(image: Image, characteristics: CameraCharacteristics): CapturedFrame {
        val rotationDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val srcWidth = image.width
        val srcHeight = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        fun sourceLuminance(col: Int, row: Int): Float =
            (buffer.get(row * rowStride + col * pixelStride).toInt() and 0xFF).toFloat()

        val (dstWidth, dstHeight) = if (rotationDegrees == 90 || rotationDegrees == 270) {
            srcHeight to srcWidth
        } else {
            srcWidth to srcHeight
        }

        val luminance = FloatArray(dstWidth * dstHeight)
        for (dstRow in 0 until dstHeight) {
            for (dstCol in 0 until dstWidth) {
                val (srcCol, srcRow) = when (rotationDegrees) {
                    90 -> dstRow to (srcHeight - 1 - dstCol)
                    180 -> (srcWidth - 1 - dstCol) to (srcHeight - 1 - dstRow)
                    270 -> (srcWidth - 1 - dstRow) to dstCol
                    else -> dstCol to dstRow
                }
                luminance[dstRow * dstWidth + dstCol] = sourceLuminance(srcCol, srcRow)
            }
        }

        val intrinsics = cameraIntrinsics(characteristics, srcWidthPx = srcWidth, finalWidthPx = dstWidth, finalHeightPx = dstHeight)
        return CapturedFrame(luminance, dstWidth, dstHeight, intrinsics)
    }

    /** Real intrinsics from the camera's own reported calibration -- see [CameraIntrinsics]' doc
     *  comment for why a hardcoded focal length would leave the projection's scale unknown. Focal
     *  length in pixels is computed from the *raw* (pre-rotation) capture width, since that's
     *  what corresponds to the sensor's physical width -- but it's a valid pixels-per-mm scalar
     *  for the final rotated image too, as camera pixels are square. */
    private fun cameraIntrinsics(characteristics: CameraCharacteristics, srcWidthPx: Int, finalWidthPx: Int, finalHeightPx: Int): CameraIntrinsics {
        val focalLengthMm = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.first()
        val sensorWidthMm = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!.width

        val focalLengthPx = focalLengthMm * srcWidthPx / sensorWidthMm
        return CameraIntrinsics(
            focalLengthPx = focalLengthPx.toDouble(),
            principalPointX = finalWidthPx / 2.0,
            principalPointY = finalHeightPx / 2.0,
        )
    }
}
