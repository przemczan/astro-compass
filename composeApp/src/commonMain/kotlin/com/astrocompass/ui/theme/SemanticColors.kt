package com.astrocompass.ui.theme

import androidx.compose.ui.graphics.Color

/** Colors used for meaning rather than pulled from `MaterialTheme.colorScheme` -- the deliberate
 *  exceptions to this app's theme-driven-color convention, gathered in one place so they can't
 *  drift between call sites (the arrow's "on target" state, a confirmed alignment sync point). */
val OnTargetGreen = Color(0xFF4CAF50)

/** Where a connected mount reports itself pointing, on every sky map that draws it -- see
 *  [com.astrocompass.ui.components.SkyMapMarker.telescope]. */
val TelescopeBlue = Color(0xFF2196F3)

/** The Guidance screen's arrow trail from current pointing to target -- a lighter shade than
 *  [TelescopeBlue] so the two read as distinct even when both are on screen (Telescope mode shows
 *  the mount's own marker alongside the path), and deliberately outside the app's usual
 *  theme-driven palette so the trail doesn't blend into `MaterialTheme.colorScheme.primary`, which
 *  the target marker and label already use. See [com.astrocompass.ui.components.SkyMapGuidancePath]. */
val GuidancePathBlue = Color(0xFF80D8FF)
