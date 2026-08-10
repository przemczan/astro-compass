package com.astrocompass.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** The translucent backing behind a readout or button cluster overlaid on the sky map -- plain
 *  text or a bare icon would be unreadable against a busy starfield otherwise. Shared so every
 *  map overlay (the guidance arrow, delta bars, follow/zoom buttons) reads as one system. [shape]
 *  defaults to fully rounded; a card flush against a screen edge (e.g. the delta bars, pinned to
 *  the bottom) should pass a shape that only rounds the corners away from that edge. */
@Composable
fun Modifier.mapOverlayScrim(shape: Shape = RoundedCornerShape(20.dp)): Modifier = this.background(
    MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
    shape,
)
