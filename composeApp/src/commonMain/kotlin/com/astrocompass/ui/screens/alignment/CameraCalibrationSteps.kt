@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.screens.alignment

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.astrocompass.guiding.TelescopeAxis
import com.astrocompass.platesolve.CameraDescriptor
import com.astrocompass.platesolve.CameraEnumerator
import com.astrocompass.platesolve.CameraFacing
import com.astrocompass.platesolve.TelescopeBoresight
import com.astrocompass.ui.components.AppBottomBar
import com.astrocompass.ui.components.AppMenuActions
import com.astrocompass.ui.components.CameraPreviewSurface
import com.astrocompass.ui.components.ToolbarActionButton
import com.astrocompass.ui.components.ToolbarCancelButton
import com.astrocompass.ui.screens.AlignmentSession
import com.astrocompass.ui.screens.AlignmentStep

/** How far the boresight may sit from the frame's center, as a fraction of frame width/height.
 *  A generous outer bound only -- [CameraPreviewSurface] applies the real, geometry-derived limit
 *  (the image can move until its own edge meets the viewport's, and no further) and reports back
 *  only the drag it actually applied, so the session's pan can never exceed what's on screen. This
 *  exists purely so a restored value from a differently-shaped previous setup can't start out
 *  absurd. */
private const val MAX_PAN_FRACTION = 0.5f

/**
 * The plate-solving branch of the alignment wizard: the phone-camera analog of a Celestron
 * StarSense-style calibration. With the phone mounted on the telescope and pointed at a distant
 * terrestrial object, it records which physical camera to use and where the telescope's optical
 * axis falls within that camera's frame.
 *
 * Nothing here plate-solves anything and no sky reference is established -- guiding's own
 * background solver is what turns this calibration into an alignment, seeded off the compass on its
 * first run (see [com.astrocompass.guiding.AutoPlateSolveRefiner]).
 *
 * Every step's draft lives in [session] rather than being `remember`ed here, so the menu's Settings
 * entry -- which tears this screen down -- can't silently discard a half-finished calibration.
 */
@Composable
fun CameraCalibrationSteps(
    session: AlignmentSession,
    cameraEnumerator: CameraEnumerator,
    currentSelectedCameraId: String?,
    currentSelectedPhysicalCameraId: String?,
    currentBoresight: TelescopeBoresight?,
    onSave: (cameraId: String?, physicalCameraId: String?, boresight: TelescopeBoresight) -> Unit,
    /** Applied as the mirror question is answered, not at the end: the axis is read live while
     *  pointing and while the background solver runs (see [TelescopeAxis]), so a value saved only
     *  on the wizard's last step would leave every step before it working off the previous setup's
     *  geometry. */
    onSelectTelescopeAxis: (TelescopeAxis) -> Unit,
    menu: AppMenuActions,
    onStepBack: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cameras = remember { cameraEnumerator.listCameras() }
    // Matched by (id, physicalId) together, not id alone -- a logical camera and one of its own
    // physical lenses (see CameraDescriptor's doc comment) share the same openable id but are
    // different selectable entries.
    val selectedCamera = cameras.firstOrNull {
        it.id == session.calibrationCameraId && it.physicalId == session.calibrationPhysicalCameraId
    }
    // Seeds the session's draft from what was saved last time, so re-running the wizard starts where
    // it left off. Runs once per entry into the branch, not on every recomposition.
    LaunchedEffect(cameras) {
        if (selectedCamera != null) return@LaunchedEffect
        val restored = cameras.firstOrNull { it.id == currentSelectedCameraId && it.physicalId == currentSelectedPhysicalCameraId }
            ?: cameras.firstOrNull { it.facing == CameraFacing.BACK && it.physicalId == null }
            ?: cameras.firstOrNull()
        session.selectCalibrationCamera(restored?.id, restored?.physicalId)
        // Inverted (0.5 - fraction, not fraction - 0.5): dragging the preview right by `pan.x` brings
        // the point that was at frame-fraction `0.5 - pan.x` under the fixed center crosshair, so
        // that's the boresight the drag actually recorded -- see the DONE step's save formula below,
        // which is this same relationship solved the other way.
        currentBoresight?.let {
            session.updateCalibrationPan(
                Offset(
                    (0.5f - it.xFraction).coerceIn(-MAX_PAN_FRACTION, MAX_PAN_FRACTION),
                    (0.5f - it.yFraction).coerceIn(-MAX_PAN_FRACTION, MAX_PAN_FRACTION),
                ),
            )
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Calibrate phone camera") }) },
        bottomBar = {
            AppBottomBar(menu) {
                ToolbarActionButton(icon = Icons.AutoMirrored.Filled.ArrowBack, label = "Back", onClick = onStepBack)
                ToolbarCancelButton(onExit)
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (session.step) {
                AlignmentStep.CHOOSE_MIRROR -> MirrorChoiceStep(
                    onChoose = { axis ->
                        onSelectTelescopeAxis(axis)
                        session.goTo(AlignmentStep.MOUNT_PHONE)
                    },
                )

                AlignmentStep.MOUNT_PHONE -> InstructionStep(
                    title = "Mount the phone",
                    body = "Mount the phone (and mirror, if used) on the telescope, aligned as " +
                        "precisely as possible with the telescope's own axes.",
                    onNext = { session.goTo(AlignmentStep.POINT_TELESCOPE) },
                )

                AlignmentStep.POINT_TELESCOPE -> InstructionStep(
                    title = "Point the telescope",
                    body = "Point the telescope at a distant object and center it as precisely " +
                        "as possible in the eyepiece. Don't move it again until this wizard is done.",
                    onNext = { session.goTo(AlignmentStep.CENTER_CROSSHAIR) },
                )

                AlignmentStep.CENTER_CROSSHAIR -> CenterCrosshairStep(
                    cameras = cameras,
                    selectedCamera = selectedCamera,
                    onCameraChange = {
                        session.selectCalibrationCamera(it.id, it.physicalId)
                        session.updateCalibrationPan(Offset.Zero)
                    },
                    pan = session.calibrationPan,
                    onDrag = { delta ->
                        session.updateCalibrationPan(
                            Offset(
                                (session.calibrationPan.x + delta.x).coerceIn(-MAX_PAN_FRACTION, MAX_PAN_FRACTION),
                                (session.calibrationPan.y + delta.y).coerceIn(-MAX_PAN_FRACTION, MAX_PAN_FRACTION),
                            ),
                        )
                    },
                    onAccept = { session.goTo(AlignmentStep.DONE) },
                )

                AlignmentStep.DONE -> InstructionStep(
                    title = "Camera calibration saved",
                    body = "While you're guiding, the app will quietly photograph the sky and " +
                        "plate-solve it whenever the telescope holds still, keeping your position " +
                        "on the map accurate.",
                    nextLabel = "OK",
                    onNext = {
                        // Dragging the preview by `pan` moves frame-fraction (0.5 - pan) under the
                        // fixed crosshair -- see the seeding effect above for the derivation.
                        val pan = session.calibrationPan
                        onSave(
                            selectedCamera?.id,
                            selectedCamera?.physicalId,
                            TelescopeBoresight(
                                xFraction = (0.5f - pan.x).coerceIn(0f, 1f),
                                yFraction = (0.5f - pan.y).coerceIn(0f, 1f),
                            ),
                        )
                        session.clear()
                        onExit()
                    },
                )

                else -> Unit
            }
        }
    }
}

/** Which way the phone faces on the mount, asked as "is there a mirror in front of it" because that
 *  is the part a user can see. A mirror/diagonal turns the optical path 90°, so the phone lies flat
 *  with its top edge pointing along the tube; without one it looks straight down the tube and it is
 *  the back (camera) face that does. */
@Composable
private fun MirrorChoiceStep(onChoose: (TelescopeAxis) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("How is the phone mounted?", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        ChoiceCard(
            title = "Use mirror",
            body = "The phone lies flat over a mirror or diagonal, with its top edge pointing along the tube.",
            onClick = { onChoose(TelescopeAxis.TOP_EDGE) },
        )
        Spacer(Modifier.height(12.dp))
        ChoiceCard(
            title = "No mirror",
            body = "The phone's camera looks straight down the tube, the same way the telescope does.",
            onClick = { onChoose(TelescopeAxis.BACK_FACE) },
        )
    }
}

@Composable
private fun InstructionStep(title: String, body: String, onNext: () -> Unit, nextLabel: String = "Next") {
    StepLayout(title = title, body = body, onNext = onNext, nextLabel = nextLabel)
}

/** Shared centered title/body/action layout for every step but the interactive crosshair one --
 *  matches the app's "action buttons are wrap-content, centered" convention. Back is deliberately
 *  absent: the bottom toolbar owns it, so there is exactly one back control on screen. */
@Composable
private fun StepLayout(
    title: String,
    body: String? = null,
    onNext: () -> Unit,
    nextLabel: String = "Next",
    content: @Composable () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(16.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        content()
        Spacer(Modifier.height(24.dp))
        Button(onClick = onNext) { Text(nextLabel) }
    }
}

@Composable
private fun CenterCrosshairStep(
    cameras: List<CameraDescriptor>,
    selectedCamera: CameraDescriptor?,
    onCameraChange: (CameraDescriptor) -> Unit,
    pan: Offset,
    onDrag: (Offset) -> Unit,
    onAccept: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Drag the preview until the crosshair sits exactly where the telescope points.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        // Shown even with a single camera detected -- always visible, both so a phone with more
        // than one back lens has a visible way to pick between them and so "nothing showed up" is
        // itself informative (an empty/one-entry list here means CameraEnumerator, not the UI, is
        // the thing to look at).
        CameraSelector(cameras, selectedCamera, onCameraChange)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        ) {
            CameraPreviewSurface(
                cameraId = selectedCamera?.id,
                physicalCameraId = selectedCamera?.physicalId,
                panFraction = pan,
                onDrag = onDrag,
                modifier = Modifier.fillMaxSize(),
            )
            Crosshair(Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAccept, enabled = selectedCamera != null) { Text("Accept") }
    }
}

/** Same select-box look as `TelescopeScreen`'s Bluetooth device picker -- a plain full-width
 *  [OutlinedButton] with a trailing dropdown-arrow icon, rather than a bare labeled button, so it
 *  reads as a selector and not as another wizard action button (it was mistaken for one -- e.g.
 *  "Back" -- when it just showed the current camera's label). */
@Composable
private fun CameraSelector(cameras: List<CameraDescriptor>, selectedCamera: CameraDescriptor?, onCameraChange: (CameraDescriptor) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = cameras.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(selectedCamera?.label ?: "No camera found", modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (camera in cameras) {
                DropdownMenuItem(
                    text = { Text(camera.label) },
                    onClick = {
                        onCameraChange(camera)
                        expanded = false
                    },
                )
            }
        }
    }
}

private val CROSSHAIR_SIZE = 64.dp
private val CROSSHAIR_STROKE = 1.5.dp

@Composable
private fun Crosshair(modifier: Modifier = Modifier) {
    val color = Color.Red
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val half = CROSSHAIR_SIZE.toPx() / 2f
        val stroke = CROSSHAIR_STROKE.toPx()
        drawLine(color, Offset(cx - half, cy), Offset(cx + half, cy), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(cx, cy - half), Offset(cx, cy + half), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}
