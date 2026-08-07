package com.astroguider.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Semantic "on target" color -- an intentional exception to theme-driven colors, same
 *  category as lightnet-mobile's StatusDot success green. */
private val OnTargetColor = Color(0xFF4CAF50)

@Composable
fun ArrowIndicator(
    arrowAngleDegrees: Double,
    isOnTarget: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (isOnTarget) OnTargetColor else MaterialTheme.colorScheme.primary
    Canvas(modifier.size(180.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2 * 0.85f
        val angleRad = (arrowAngleDegrees * PI / 180.0).toFloat()

        // 0 degrees = straight up; angle increases clockwise (matches azimuth convention).
        fun pointAt(distance: Float, offsetAngleRad: Float = 0f): Offset {
            val a = angleRad + offsetAngleRad
            return Offset(
                center.x + distance * sin(a),
                center.y - distance * cos(a),
            )
        }

        val tip = pointAt(radius)
        val tailLeft = pointAt(radius * 0.35f, offsetAngleRad = (150.0 * PI / 180.0).toFloat())
        val tailRight = pointAt(radius * 0.35f, offsetAngleRad = (-150.0 * PI / 180.0).toFloat())
        val back = pointAt(-radius * 0.5f)

        val path = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(tailLeft.x, tailLeft.y)
            lineTo(back.x, back.y)
            lineTo(tailRight.x, tailRight.y)
            close()
        }
        drawPath(path, color = color)
    }
}
