@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.astrocompass.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.astrocompass.platesolve.CameraDescriptor
import com.astrocompass.platesolve.CameraEnumerator
import com.astrocompass.platesolve.CameraFacing
import com.astrocompass.platesolve.TelescopeBoresight
import com.astrocompass.ui.components.CameraPreviewSurface

private enum class CalibrationStep { MIRROR, MOUNT_PHONE, POINT_TELESCOPE, CENTER_CROSSHAIR, DONE }

/** How far the boresight may sit from the frame's center, as a fraction of frame width/height.
 *  A generous outer bound only -- [CameraPreviewSurface] applies the real, geometry-derived limit
 *  (the image can move until its own edge meets the viewport's, and no further) and reports back
 *  only the drag it actually applied, so `pan` here can never exceed what's on screen. This exists
 *  purely so a restored value from a differently-shaped previous setup can't start out absurd. */
private const val MAX_PAN_FRACTION = 0.5f

/**
 * The phone-camera analog of a Celestron StarSense-style calibration: with the phone mounted on
 * the telescope and pointed at a distant terrestrial object, this records whether the optical path
 * runs through a mirror, which physical camera to use, and where the telescope's optical axis
 * falls within that camera's frame. Nothing here plate-solves anything -- consuming the mirror
 * flag and boresight inside [com.astrocompass.alignment.PlateSolveAlignment] is future work; this
 * screen only captures and persists the three facts (see [TelescopeBoresight]'s doc comment for
 * why the boresight is kept separate from the camera's own optical principal point).
 *
 * A plain `remember`ed local wizard, not hoisted above `App.kt`'s `when` like
 * [AlignmentSession][com.astrocompass.ui.screens.AlignmentSession] -- nothing here persists until
 * the final step's confirm, so there is no in-progress hardware state to protect from a screen
 * teardown. For the same reason there is deliberately no Settings action in the top bar: `App.kt`'s
 * `when` checks `showSettings` ahead of everything else, so opening it would silently discard
 * whatever step this wizard was on.
 */
@Composable
fun PhoneCalibrationScreen(
    cameraEnumerator: CameraEnumerator,
    currentSelectedCameraId: String?,
    currentSelectedPhysicalCameraId: String?,
    currentUsesMirror: Boolean,
    currentBoresight: TelescopeBoresight?,
    onSave: (cameraId: String?, physicalCameraId: String?, usesMirror: Boolean, boresight: TelescopeBoresight) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableStateOf(CalibrationStep.MIRROR) }
    var usesMirror by remember { mutableStateOf(currentUsesMirror) }
    val cameras = remember { cameraEnumerator.listCameras() }
    // Matched by (id, physicalId) together, not id alone -- a logical camera and one of its own
    // physical lenses (see CameraDescriptor's doc comment) share the same openable id but are
    // different selectable entries.
    var selectedCamera by remember {
        mutableStateOf(
            cameras.firstOrNull { it.id == currentSelectedCameraId && it.physicalId == currentSelectedPhysicalCameraId }
                ?: cameras.firstOrNull { it.facing == CameraFacing.BACK && it.physicalId == null }
                ?: cameras.firstOrNull(),
        )
    }
    // Fraction-of-frame pan away from dead center, in CameraPreviewSurface's own convention --
    // resumes from the last saved boresight so re-running the wizard starts where it left off.
    // Inverted (0.5 - fraction, not fraction - 0.5): dragging the preview right by `pan.x` brings
    // the point that was at frame-fraction `0.5 - pan.x` under the fixed center crosshair, so
    // that's the boresight the drag actually recorded -- see the DONE step's save formula below,
    // which is this same relationship solved the other way.
    var pan by remember {
        mutableStateOf(
            currentBoresight?.let {
                Offset(
                    (0.5f - it.xFraction).coerceIn(-MAX_PAN_FRACTION, MAX_PAN_FRACTION),
                    (0.5f - it.yFraction).coerceIn(-MAX_PAN_FRACTION, MAX_PAN_FRACTION),
                )
            } ?: Offset.Zero,
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Calibrate phone camera") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (step) {
                CalibrationStep.MIRROR -> MirrorStep(
                    usesMirror = usesMirror,
                    onUsesMirrorChange = { usesMirror = it },
                    onNext = { step = CalibrationStep.MOUNT_PHONE },
                )

                CalibrationStep.MOUNT_PHONE -> InstructionStep(
                    title = "Mount the phone",
                    body = "Mount the phone (and mirror, if used) on the telescope, aligned as " +
                        "precisely as possible with the telescope's own axes.",
                    onBack = { step = CalibrationStep.MIRROR },
                    onNext = { step = CalibrationStep.POINT_TELESCOPE },
                )

                CalibrationStep.POINT_TELESCOPE -> InstructionStep(
                    title = "Point the telescope",
                    body = "Point the telescope at a distant object and center it as precisely " +
                        "as possible in the eyepiece. Don't move it again until this wizard is done.",
                    onBack = { step = CalibrationStep.MOUNT_PHONE },
                    onNext = { step = CalibrationStep.CENTER_CROSSHAIR },
                )

                CalibrationStep.CENTER_CROSSHAIR -> CenterCrosshairStep(
                    cameras = cameras,
                    selectedCamera = selectedCamera,
                    onCameraChange = {
                        selectedCamera = it
                        pan = Offset.Zero
                    },
                    pan = pan,
                    onDrag = { delta ->
                        pan = Offset(
                            (pan.x + delta.x).coerceIn(-MAX_PAN_FRACTION, MAX_PAN_FRACTION),
                            (pan.y + delta.y).coerceIn(-MAX_PAN_FRACTION, MAX_PAN_FRACTION),
                        )
                    },
                    onBack = { step = CalibrationStep.POINT_TELESCOPE },
                    onAccept = { step = CalibrationStep.DONE },
                )

                CalibrationStep.DONE -> DoneStep(
                    onConfirm = {
                        // Dragging the preview by `pan` moves frame-fraction (0.5 - pan) under the
                        // fixed crosshair -- see `pan`'s own doc comment above for the derivation.
                        val boresight = TelescopeBoresight(
                            xFraction = (0.5f - pan.x).coerceIn(0f, 1f),
                            yFraction = (0.5f - pan.y).coerceIn(0f, 1f),
                        )
                        onSave(selectedCamera?.id, selectedCamera?.physicalId, usesMirror, boresight)
                        onBack()
                    },
                )
            }
        }
    }
}

@Composable
private fun MirrorStep(usesMirror: Boolean, onUsesMirrorChange: (Boolean) -> Unit, onNext: () -> Unit) {
    StepLayout(
        title = "Does your setup use a mirror?",
        body = "Some mounts fold the optical path through a mirror in front of the camera. If " +
            "yours does, make sure the phone is positioned so the camera's whole view is covered " +
            "by the mirror surface.",
        onNext = onNext,
    ) {
        SingleChoiceSegmentedButtonRow {
            listOf(false to "No mirror", true to "Uses a mirror").forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = usesMirror == value,
                    onClick = { onUsesMirrorChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun InstructionStep(title: String, body: String, onBack: () -> Unit, onNext: () -> Unit) {
    StepLayout(title = title, body = body, onBack = onBack, onNext = onNext)
}

@Composable
private fun DoneStep(onConfirm: () -> Unit) {
    StepLayout(
        title = "Phone calibration saved",
        body = "Plate solving will use this the next time it's wired in.",
        onNext = onConfirm,
        nextLabel = "OK",
    )
}

/** Shared centered title/body/Back-Next layout for every step but the interactive crosshair one --
 *  matches the app's "action buttons are wrap-content, centered" convention. */
@Composable
private fun StepLayout(
    title: String,
    body: String? = null,
    onBack: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            if (onBack != null) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                Spacer(Modifier.width(16.dp))
            }
            if (onNext != null) {
                Button(onClick = onNext) { Text(nextLabel) }
            }
        }
    }
}

@Composable
private fun CenterCrosshairStep(
    cameras: List<CameraDescriptor>,
    selectedCamera: CameraDescriptor?,
    onCameraChange: (CameraDescriptor) -> Unit,
    pan: Offset,
    onDrag: (Offset) -> Unit,
    onBack: () -> Unit,
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.width(16.dp))
            Button(onClick = onAccept, enabled = selectedCamera != null) { Text("Accept") }
        }
    }
}

/** Same select-box look as `TelescopeScreen`'s Bluetooth device picker -- a plain full-width
 *  `OutlinedButton` with a trailing dropdown-arrow icon, rather than a bare labeled button, so it
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
