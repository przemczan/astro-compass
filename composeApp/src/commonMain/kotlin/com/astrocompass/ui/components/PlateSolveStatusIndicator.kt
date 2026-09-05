package com.astrocompass.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astrocompass.guiding.PlateSolveOutcome
import com.astrocompass.guiding.PlateSolveStatus
import com.astrocompass.platesolve.PlateSolveDiagnostics
import com.astrocompass.platesolve.PlateSolveFailureReason
import com.astrocompass.ui.theme.OnTargetGreen
import com.astrocompass.ui.theme.TelescopeBlue

private val DOT_SIZE = 10.dp

/**
 * Background plate-solve status, for a Guidance-only camera setup: grey before any attempt this
 * run, blue while a capture/solve is in flight, green/red for the most recent outcome. Tapping
 * opens the actual reason -- a plain color alone can say a solve failed but not *why*, which is
 * the whole point of surfacing this at all (see [AutoPlateSolveRefiner][com.astrocompass.guiding.AutoPlateSolveRefiner]'s
 * own doc comment on why nothing was surfaced before).
 */
@Composable
fun PlateSolveStatusIndicator(
    status: PlateSolveStatus,
    lastOutcome: PlateSolveOutcome?,
    modifier: Modifier = Modifier,
) {
    var showDetail by remember { mutableStateOf(false) }
    val color = when (status) {
        PlateSolveStatus.IDLE -> MaterialTheme.colorScheme.outline
        PlateSolveStatus.SOLVING -> TelescopeBlue
        PlateSolveStatus.SUCCEEDED -> OnTargetGreen
        PlateSolveStatus.FAILED -> MaterialTheme.colorScheme.error
    }
    Box {
        IconButton(onClick = { showDetail = true }, modifier = modifier) {
            Box(Modifier.size(DOT_SIZE).background(color, CircleShape))
        }
        DropdownMenu(expanded = showDetail, onDismissRequest = { showDetail = false }) {
            Column(Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(status.label(), style = MaterialTheme.typography.titleSmall)
                lastOutcome?.let {
                    Text(
                        it.describe(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private fun PlateSolveStatus.label(): String = when (this) {
    PlateSolveStatus.IDLE -> "Plate solving: not yet attempted"
    PlateSolveStatus.SOLVING -> "Plate solving: capturing / solving…"
    PlateSolveStatus.SUCCEEDED -> "Plate solving: last attempt succeeded"
    PlateSolveStatus.FAILED -> "Plate solving: last attempt failed"
}

private fun PlateSolveOutcome.describe(): String = when (this) {
    is PlateSolveOutcome.Success -> {
        val d = attempt.diagnostics
        "Matched ${d.matchedStarCount} of ${d.detectionCount} detected stars " +
            "(${d.candidateCount} candidates in range), correction ${attempt.correctionDegrees.oneDecimal()}°, " +
            "RMS ${attempt.result.rmsResidualDegrees.oneDecimal()}°."
    }
    is PlateSolveOutcome.Failure -> reason.describe(diagnostics, correctionDegrees)
}

private fun PlateSolveFailureReason.describe(diagnostics: PlateSolveDiagnostics, correctionDegrees: Double?): String = when (this) {
    PlateSolveFailureReason.NO_POINTING_REFERENCE -> "No pointing reference yet to seed the search around."
    PlateSolveFailureReason.NO_LOCATION -> "Location not set."
    PlateSolveFailureReason.CAMERA_CAPTURE_FAILED -> "Camera capture failed -- check camera permission and the selected camera."
    PlateSolveFailureReason.ORIENTATION_UNAVAILABLE -> "Orientation sensor has no reading yet."
    PlateSolveFailureReason.TOO_FEW_DETECTIONS ->
        "Only ${diagnostics.detectionCount} star-like blob(s) detected (need at least 4) -- " +
            "try a longer exposure, higher ISO, or check focus."
    PlateSolveFailureReason.TOO_FEW_CANDIDATES ->
        "Only ${diagnostics.candidateCount} catalog star(s) within range (need at least 4) -- " +
            "a sparse patch of sky, or the seed direction is off."
    PlateSolveFailureReason.NO_GEOMETRIC_MATCH ->
        "${diagnostics.detectionCount} detected, ${diagnostics.candidateCount} candidates in range, " +
            "but no geometric match (best ${diagnostics.matchedStarCount}) -- check camera calibration/mounting."
    PlateSolveFailureReason.ALIGNMENT_FIT_FAILED -> "Stars matched, but the attitude fit itself failed."
    PlateSolveFailureReason.TIMEOUT -> "Solve timed out before finishing."
    PlateSolveFailureReason.CORRECTION_TOO_LARGE ->
        "Solved, but the ${correctionDegrees?.oneDecimal() ?: "?"}° correction was rejected as implausible."
}

private fun Double.oneDecimal(): String = (kotlin.math.round(this * 10) / 10).toString()
