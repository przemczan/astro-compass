package com.astrocompass.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "CameraPreviewSurface"

/** Preview stream resolution target -- large enough to look sharp enlarged by [PREVIEW_ZOOM_FACTOR],
 *  small enough to stay smooth as a continuous repeating request (unlike
 *  [com.astrocompass.platesolve.AndroidCameraCapture]'s single still capture, this runs the whole
 *  time the wizard step is on screen). */
private const val TARGET_PREVIEW_PIXELS = 1_000_000

/**
 * How much further the preview is enlarged past a plain cover-fit (which alone just fills the
 * viewport edge to edge with no gaps). A fixed, single-source-of-truth constant rather than
 * anything computed from `pan` -- deliberately easy to change or later expose as a user-facing
 * option, per the wizard's own design note that this value isn't necessarily final.
 */
private const val PREVIEW_ZOOM_FACTOR = 2f

/** How long to wait before retrying an open whose session came back without its output -- long
 *  enough for the previous [CameraDevice]'s own teardown (what this loses the race to) to finish. */
private const val REOPEN_DELAY_MILLIS = 300L
private const val MAX_REOPEN_ATTEMPTS = 3

@Composable
actual fun CameraPreviewSurface(
    cameraId: String?,
    physicalCameraId: String?,
    panFraction: Offset,
    onDrag: (Offset) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val session = remember { CameraPreviewSession(context) }
    // A plain field write, not `rememberUpdatedState` -- `session` is a `remember`ed object that
    // outlives recomposition on its own, so there's no closure/re-subscription to keep fresh, just
    // "whatever `onDrag` this composition currently has" for the touch listener to call into.
    session.onDrag = onDrag

    DisposableEffect(Unit) {
        onDispose { session.release() }
    }
    LaunchedEffect(cameraId, physicalCameraId) { session.setDesiredCamera(cameraId, physicalCameraId) }
    LaunchedEffect(panFraction) { session.setPan(panFraction) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            // The TextureView is deliberately laid out *larger than* this container (see
            // CameraPreviewSession.applyLayout) and clipped by it, which is also what keeps the
            // preview from painting over the wizard's own top bar and buttons -- a TextureView
            // composites through its own hardware layer and does not honor a parent Compose
            // `Modifier.clip` on its own.
            FrameLayout(ctx).also { container ->
                container.clipChildren = true
                val textureView = TextureView(ctx)
                container.addView(
                    textureView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER,
                    ),
                )
                session.attachTo(container, textureView)

                // On the container, not the TextureView: the container's coordinates are stable,
                // while the TextureView beneath is rotated, scaled and translated as the user pans.
                var lastX = 0f
                var lastY = 0f
                container.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            lastX = event.x
                            lastY = event.y
                        }
                        MotionEvent.ACTION_MOVE -> {
                            session.handleDragPixels(event.x - lastX, event.y - lastY)
                            lastX = event.x
                            lastY = event.y
                        }
                    }
                    true
                }
            }
        },
    )
}

/**
 * Owns a `camera2` repeating preview request onto a [TextureView]'s [SurfaceTexture], independent
 * of Compose recomposition -- [attachTo] runs once per view pair, [setDesiredCamera] and [setPan]
 * can be called any number of times across the composable's lifetime (switching the wizard's camera
 * selector, or dragging to pan), and [release] tears everything down when the composable leaves.
 *
 * Deliberately not `suspend`/coroutine-based like [com.astrocompass.platesolve.AndroidCameraCapture]:
 * that class wraps one bounded, awaited capture; this wraps a session that outlives any single
 * call and is driven by View callbacks, so plain `camera2` callbacks are the more direct fit.
 */
private class CameraPreviewSession(private val context: Context) {
    private val handlerThread = HandlerThread("CameraPreviewSurface").apply { start() }
    private val handler = Handler(handlerThread.looper)

    var onDrag: (Offset) -> Unit = {}

    private var container: FrameLayout? = null
    private var textureView: TextureView? = null
    private var surfaceTexture: SurfaceTexture? = null

    /** The clipping container's size -- the viewport the image is fitted to. Read from the view
     *  rather than from `SurfaceTextureListener`'s width/height params, which report the
     *  *buffer* size whenever `setDefaultBufferSize` changes it and so cannot stand in for it. */
    private val viewWidth get() = container?.width ?: 0
    private val viewHeight get() = container?.height ?: 0

    private var desiredCameraId: String? = null
    private var desiredPhysicalCameraId: String? = null
    private var device: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    /** The [Surface] the live session was configured with, kept only so [closeCamera] can release
     *  it -- one is created per camera open, and nothing else would ever hand it back. */
    private var previewSurface: Surface? = null

    /** Null until a camera is open and its geometry known -- there is no sane placeholder, and
     *  laying the preview out against a guessed size is exactly how it ends up distorted. */
    private var previewSize: Size? = null
    private var sensorOrientation = 0
    private var pan = Offset.Zero
    private var released = false

    /** How many times [scheduleReopen] has retried since the last preview that actually started. */
    private var reopenAttempts = 0

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            surfaceTexture = surface
            desiredCameraId?.let { openCamera(it, desiredPhysicalCameraId) }
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            closeCamera()
            surfaceTexture = null
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    fun attachTo(container: FrameLayout, textureView: TextureView) {
        this.container = container
        this.textureView = textureView
        textureView.surfaceTextureListener = surfaceTextureListener
        // The viewport size settles at layout time, which is neither when the surface becomes
        // available nor when its buffer size changes -- so neither SurfaceTextureListener callback
        // can stand in for this.
        container.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val sizeChanged = (right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)
            if (sizeChanged) {
                pan = clampPan(pan)
                applyLayout()
            }
        }
    }

    fun setDesiredCamera(cameraId: String?, physicalCameraId: String?) {
        if (cameraId == desiredCameraId && physicalCameraId == desiredPhysicalCameraId) return
        desiredCameraId = cameraId
        desiredPhysicalCameraId = physicalCameraId
        if (surfaceTexture == null) return
        closeCamera()
        if (cameraId != null) openCamera(cameraId, physicalCameraId)
    }

    fun setPan(newPan: Offset) {
        pan = clampPan(newPan)
        applyLayout()
    }

    /** Converts a raw on-screen drag into a fraction of the rendered frame, clamped so the image
     *  can be moved only up to its own edge -- past that the preview would have to show something
     *  outside the real image (a black stripe), which [clampPan] never allows. Reports back only
     *  the delta actually applied, so a drag that hits the edge and keeps going stops moving the
     *  caller's own `pan` state instead of it drifting out of sync with what's on screen. */
    fun handleDragPixels(dxPixels: Float, dyPixels: Float) {
        val geometry = currentGeometry() ?: return
        val extent = geometry.extent
        val proposed = Offset(pan.x + dxPixels / extent.x, pan.y + dyPixels / extent.y)
        val clamped = clampPan(proposed)
        val actualDelta = clamped - pan
        pan = clamped
        applyLayout()
        onDrag(actualDelta)
    }

    /** Bounds [candidate] so the [PREVIEW_ZOOM_FACTOR]-enlarged, cover-fitted image can shift by at
     *  most half its own overflow past the viewport on each axis -- exactly enough that the image's
     *  edge can reach the viewport's edge, never past it. Returns [candidate] unclamped while the
     *  geometry isn't known yet; the layout listener and [openCamera] re-clamp once it is. */
    private fun clampPan(candidate: Offset): Offset {
        val geometry = currentGeometry() ?: return candidate
        val maxPan = geometry.maxPanFraction
        return Offset(candidate.x.coerceIn(-maxPan.x, maxPan.x), candidate.y.coerceIn(-maxPan.y, maxPan.y))
    }

    private fun currentGeometry(): PreviewGeometry? {
        val buffer = previewSize ?: return null
        if (viewWidth == 0 || viewHeight == 0) return null
        return PreviewGeometry(viewWidth, viewHeight, buffer, sensorOrientation)
    }

    /**
     * Sizes and places the [TextureView] beneath the clipping container.
     *
     * The view is laid out at the buffer's *own* aspect ratio (scaled by
     * [PreviewGeometry.scale]) rather than filling the container, which is the whole point:
     * `TextureView` stretches its buffer to its view bounds, and matching those bounds to the
     * buffer's aspect makes that stretch uniform, so the image cannot come out distorted. Rotation
     * and pan then ride on the View's own `rotation`/`translation` properties -- no
     * `setTransform` matrix, and so no dependence on which coordinate space that matrix composes
     * in.
     */
    private fun applyLayout() {
        val view = textureView ?: return
        val geometry = currentGeometry() ?: return

        // Sized from the *rotated* dimensions and never rotated here: the camera pipeline already
        // delivers the frame upright for a portrait-locked window (see PreviewGeometry.rotatedSize),
        // so rotating it again would both turn the image on its side and squash portrait content
        // into a landscape box. Matching the view's bounds to the content's real aspect ratio is
        // what makes TextureView's buffer-to-view stretch uniform, and so keeps proportions intact.
        //
        // Laid out at the cover-fit size only; PREVIEW_ZOOM_FACTOR rides on the View's own uniform
        // scale below instead of inflating these bounds, which keeps the hardware layer roughly
        // viewport-sized rather than zoom-times-larger.
        val layoutParams = view.layoutParams as FrameLayout.LayoutParams
        val width = (geometry.rotatedSize.width * geometry.coverScale).roundToInt()
        val height = (geometry.rotatedSize.height * geometry.coverScale).roundToInt()
        if (layoutParams.width != width || layoutParams.height != height) {
            layoutParams.width = width
            layoutParams.height = height
            layoutParams.gravity = Gravity.CENTER
            view.layoutParams = layoutParams
        }
        // A uniform scale about the View's default center pivot, so the footprint stays centered;
        // translation is in the container's coordinates, applied after it.
        view.scaleX = PREVIEW_ZOOM_FACTOR
        view.scaleY = PREVIEW_ZOOM_FACTOR
        view.translationX = pan.x * geometry.extent.x
        view.translationY = pan.y * geometry.extent.y
    }

    fun release() {
        released = true
        closeCamera()
        handlerThread.quitSafely()
    }

    private fun openCamera(cameraId: String, physicalCameraId: String?) {
        val texture = surfaceTexture ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "openCamera: CAMERA permission not granted")
            return
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        // The physical lens' own characteristics when one is targeted -- its native resolution and
        // sensor orientation are what the actual output stream reports, which can differ from the
        // logical camera's fused defaults (see CameraDescriptor's doc comment).
        val targetCharacteristics = try {
            manager.getCameraCharacteristics(physicalCameraId ?: cameraId)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "openCamera: unknown camera id $cameraId/$physicalCameraId", e)
            return
        }
        sensorOrientation = targetCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val chosenSize = pickPreviewSize(targetCharacteristics)
        previewSize = chosenSize
        texture.setDefaultBufferSize(chosenSize.width, chosenSize.height)
        pan = clampPan(pan)
        applyLayout()

        try {
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    // openCamera is async, so by the time this lands the session may have moved on
                    // to a different camera, a different SurfaceTexture, or been released -- close
                    // this one instead of adopting it, or it lingers as an orphaned "Active Camera
                    // Client" (see the camerax-stallion-hang note) and can block a later
                    // plate-solve capture.
                    //
                    // The texture check is not redundant with the id checks: leaving and re-entering
                    // this step destroys the TextureView's SurfaceTexture and hands out a new one,
                    // for the *same* camera id, and onSurfaceTextureDestroyed can't cancel an open
                    // that hasn't landed yet (there is no device to close). Configuring a session
                    // against the abandoned texture still reports onConfigured, but the surface is
                    // dropped from the configuration -- so the repeating request below would target
                    // a stream that doesn't exist, which camera2 throws on rather than ignores.
                    override fun onOpened(camera: CameraDevice) {
                        if (released ||
                            cameraId != desiredCameraId ||
                            physicalCameraId != desiredPhysicalCameraId ||
                            texture !== surfaceTexture
                        ) {
                            camera.close()
                            return
                        }
                        device = camera
                        startPreview(camera, Surface(texture), physicalCameraId)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (device == camera) device = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.w(TAG, "openCamera: error $error")
                        camera.close()
                        if (device == camera) device = null
                    }
                },
                handler,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "openCamera: permission denied at call time", e)
        }
    }

    private fun startPreview(camera: CameraDevice, surface: Surface, physicalCameraId: String?) = runWhenLaidOut {
        if (released || device != camera || !surface.isValid) return@runWhenLaidOut
        // Re-asserted here, immediately before the session is configured, and only once the layout
        // [applyLayout] asked for has actually happened: TextureView resets its SurfaceTexture's
        // default buffer size to its own bounds on every layout pass, and camera2 takes a
        // SurfaceTexture stream's size from that buffer. Asserting it while a layout was still
        // pending left the view's size as the last word, so the camera configured a view-shaped
        // stream and its frames reached the screen stretched.
        previewSize?.let { surfaceTexture?.setDefaultBufferSize(it.width, it.height) }
        val outputConfig = OutputConfiguration(surface).apply {
            if (physicalCameraId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setPhysicalCameraId(physicalCameraId)
            }
        }
        previewSurface = surface
        camera.createCaptureSessionByOutputConfigurations(
            listOf(outputConfig),
            object : CameraCaptureSession.StateCallback() {
                // `surface.isValid` covers the same hazard as onOpened's texture check, one step
                // later: a SurfaceTexture released while this session was configuring leaves a
                // surface the request can't resolve to a stream.
                override fun onConfigured(session: CameraCaptureSession) {
                    if (released || device != camera || !surface.isValid) {
                        session.close()
                        return
                    }
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                    }.build()
                    try {
                        session.setRepeatingRequest(request, null, handler)
                        reopenAttempts = 0
                    } catch (e: IllegalArgumentException) {
                        // A session can report itself configured having silently dropped its output:
                        // reopening a camera while the *previous* CameraDevice for that id is still
                        // tearing down (leaving and re-entering this step in quick succession does
                        // exactly that) leaves the request targeting a stream the device doesn't
                        // have, which camera2 throws on rather than ignores. The surface, the device
                        // and the texture are all still the live ones here -- there is nothing to
                        // check that would have seen this coming, so the failure is caught and the
                        // camera reopened, rather than left as a preview that can never get a frame.
                        Log.w(TAG, "startPreview: session configured without its output; reopening", e)
                        closeCamera()
                        scheduleReopen()
                    } catch (e: IllegalStateException) {
                        // Same treatment for a session closed out from under this callback.
                        Log.w(TAG, "startPreview: session already closed", e)
                        closeCamera()
                        scheduleReopen()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.w(TAG, "startPreview: capture session configuration failed")
                }
            },
            handler,
        )
    }

    /**
     * Runs [action] on the main thread once the [TextureView] has no layout pending, so a pass
     * triggered by [applyLayout] can't land in the middle of it. [startPreview] is the caller that
     * needs this: it arrives on the camera thread, and both halves of what it does -- setting the
     * SurfaceTexture's buffer size and configuring the stream that reads it -- must sit on the same
     * side of the layout that would otherwise rewrite that buffer size.
     *
     * Re-posts while a layout is still outstanding rather than assuming one loop is enough: a
     * traversal is a Choreographer callback, so it isn't ordered against a plain posted Runnable.
     */
    private fun runWhenLaidOut(action: () -> Unit) {
        val view = textureView ?: return
        view.post(object : Runnable {
            override fun run() {
                if (released) return
                if (view.isLayoutRequested) view.post(this) else action()
            }
        })
    }

    /** Retries the open behind [onConfigured]'s dropped-output failure, on the main thread because
     *  [openCamera] resizes and lays out the [TextureView]. Bounded by [MAX_REOPEN_ATTEMPTS] so a
     *  camera that fails this way every time (rather than losing one race to a closing device)
     *  settles on a black preview instead of reopening forever; a successful start resets the
     *  count, so a later, unrelated race gets its own retries. */
    private fun scheduleReopen() {
        if (released || reopenAttempts >= MAX_REOPEN_ATTEMPTS) return
        reopenAttempts++
        container?.postDelayed(
            {
                val cameraId = desiredCameraId
                if (!released && cameraId != null && surfaceTexture != null && device == null) {
                    openCamera(cameraId, desiredPhysicalCameraId)
                }
            },
            REOPEN_DELAY_MILLIS,
        )
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        device?.close()
        device = null
        // Only the Surface wrapper, never the SurfaceTexture behind it -- that one belongs to the
        // TextureView, which releases it itself when it goes away.
        previewSurface?.release()
        previewSurface = null
    }

    /** The largest available preview size under [TARGET_PREVIEW_PIXELS], or the smallest available
     *  size if every option exceeds it -- same shape as `AndroidCameraCapture.pickFrameSize`. */
    private fun pickPreviewSize(characteristics: CameraCharacteristics): Size {
        val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = streamConfigMap.getOutputSizes(SurfaceTexture::class.java).toList()
        return sizes.filter { it.width.toLong() * it.height <= TARGET_PREVIEW_PIXELS }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { abs(it.width.toLong() * it.height - TARGET_PREVIEW_PIXELS) }!!
    }
}

/**
 * Everything the layout needs, derived once from the viewport, the camera buffer and the sensor
 * orientation -- kept together so [CameraPreviewSession.applyLayout]'s sizing,
 * [CameraPreviewSession.handleDragPixels]'s drag-to-fraction conversion and [maxPanFraction]'s
 * bound can never be computed from disagreeing intermediate values.
 */
private data class PreviewGeometry(
    val viewWidth: Int,
    val viewHeight: Int,
    val bufferSize: Size,
    val sensorOrientation: Int,
) {
    /**
     * [bufferSize] as it actually reaches the screen: width/height swapped for the quarter turns,
     * because the camera pipeline already presents the frame rotated for a portrait-locked window.
     * This is the size the preview must be laid out at -- see [CameraPreviewSession.applyLayout].
     *
     * Note the asymmetry with `AndroidCameraCapture.toCapturedFrame`, which rotates the luminance
     * plane by `SENSOR_ORIENTATION` by hand: that path reads raw sensor-oriented frames from an
     * `ImageReader`, which gets no such automatic rotation. Both end up upright; only this one gets
     * there for free.
     */
    val rotatedSize: Size =
        if (sensorOrientation == 90 || sensorOrientation == 270) Size(bufferSize.height, bufferSize.width)
        else bufferSize

    /**
     * The single factor that fits the buffer to the viewport: driven by whichever axis needs the
     * most enlarging (`max`, not `min`) so neither axis is left short of the viewport, then applied
     * to *both* axes. One factor for both is what keeps the image's proportions -- the overflow on
     * the looser axis is simply clipped.
     */
    val coverScale: Float =
        max(viewWidth.toFloat() / rotatedSize.width, viewHeight.toFloat() / rotatedSize.height)

    /** The rendered image's on-screen size, i.e. the units `pan` is expressed in -- a `panFraction`
     *  of 1.0 always means one full frame width/height. */
    val extent: Offset =
        Offset(rotatedSize.width * coverScale * PREVIEW_ZOOM_FACTOR, rotatedSize.height * coverScale * PREVIEW_ZOOM_FACTOR)

    /**
     * How far `pan` can go per axis before the enlarged image's own edge would cross the
     * viewport's (i.e. before the preview would have to show something that isn't part of the real
     * image). Plain geometry: the image is centered on the viewport at `pan = 0`, so it can shift
     * by half its own overflow before that edge lines up exactly. The two axes generally differ,
     * since the cover fit already makes one overflow more than the other whenever the buffer and
     * viewport aspect ratios don't match.
     */
    val maxPanFraction: Offset = Offset(
        (0.5f * (1f - viewWidth / extent.x)).coerceAtLeast(0f),
        (0.5f * (1f - viewHeight / extent.y)).coerceAtLeast(0f),
    )
}
