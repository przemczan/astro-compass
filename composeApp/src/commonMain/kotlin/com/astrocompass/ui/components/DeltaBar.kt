package com.astrocompass.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

/** A "how far and which way" bar for one axis (altitude or cross-track): a filled segment grows
 *  from the center tick toward the side matching the sign. Fill fraction is `(|delta| /
 *  [maxDegrees])^[exponent]` rather than a plain linear ratio -- a concave power curve (like a VU
 *  meter or signal-strength bar) devotes most of the bar's visual width to small deltas, where
 *  precision actually matters for nudging the telescope the last few degrees onto target, while
 *  still spreading large deltas continuously across the rest of the bar instead of saturating at
 *  some arbitrary cutoff. [maxDegrees] defaults to 180 -- the true maximum possible angular
 *  separation -- so the bar is never less than fully expressive. */
@Composable
fun DeltaBar(
    label: String,
    deltaDegrees: Double,
    modifier: Modifier = Modifier,
    maxDegrees: Double = 180.0,
    exponent: Double = 0.5,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val fillColor = MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier.fillMaxWidth()) {
        Text(
            "$label  ${if (deltaDegrees >= 0) "+" else ""}${formatDegrees(deltaDegrees)}°",
            style = MaterialTheme.typography.labelLarge,
        )
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val trackHeight = size.height
            drawRoundRect(trackColor, cornerRadius = CornerRadius(trackHeight / 2))

            val fraction = (abs(deltaDegrees) / maxDegrees).coerceIn(0.0, 1.0).pow(exponent).toFloat()
            val centerX = size.width / 2
            val halfWidth = size.width / 2
            val fillWidth = halfWidth * fraction
            val left = if (deltaDegrees >= 0) centerX else centerX - fillWidth
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(left, 0f),
                size = Size(fillWidth, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2),
            )

            val tickWidth = 3.dp.toPx()
            drawRect(tickColor, topLeft = Offset(centerX - tickWidth / 2, 0f), size = Size(tickWidth, trackHeight))
        }
    }
}

private fun formatDegrees(value: Double): String = (round(abs(value) * 10) / 10).toString()
