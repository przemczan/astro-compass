package com.astroguider.ui

import androidx.compose.runtime.Composable

// No system-level back gesture is wired up on iOS in this app (no UINavigationController --
// navigation is fully custom state in App.kt), so there is nothing to intercept.
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
