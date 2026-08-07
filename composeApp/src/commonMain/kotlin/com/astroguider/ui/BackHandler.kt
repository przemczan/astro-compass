package com.astroguider.ui

import androidx.compose.runtime.Composable

/** Intercepts the system back gesture/button so in-app navigation handles it instead of the
 *  platform's default "finish the screen" behavior. A no-op where the platform has no such
 *  system-level back concept to intercept. */
@Composable
expect fun BackHandler(enabled: Boolean, onBack: () -> Unit)
