package com.astrocompass.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

/** No AVFoundation camera binding yet, same as [com.astrocompass.platesolve.StubCameraCapture]. */
@Composable
actual fun CameraPreviewSurface(
    cameraId: String?,
    physicalCameraId: String?,
    panFraction: Offset,
    onDrag: (Offset) -> Unit,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "Camera preview not available on this platform",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(24.dp),
        )
    }
}
