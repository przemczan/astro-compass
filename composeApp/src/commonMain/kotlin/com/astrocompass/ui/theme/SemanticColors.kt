package com.astrocompass.ui.theme

import androidx.compose.ui.graphics.Color

/** Colors used for meaning rather than pulled from `MaterialTheme.colorScheme` -- the deliberate
 *  exceptions to this app's theme-driven-color convention, gathered in one place so they can't
 *  drift between call sites (the arrow's "on target" state, a confirmed alignment sync point). */
val OnTargetGreen = Color(0xFF4CAF50)

/** Where a connected mount reports itself pointing, on every sky map that draws it -- see
 *  [com.astrocompass.ui.components.SkyMapMarker.telescope]. */
val TelescopeBlue = Color(0xFF2196F3)
