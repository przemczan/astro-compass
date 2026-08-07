package com.astrocompass.astro.time

/** The device's current wall-clock time. Separate from [AstroTime], which never needs this --
 *  Android hands time in as epoch millis already; only the UI layer needs to ask "what time is
 *  it right now" to drive continuous recomputation. */
expect fun currentEpochMillis(): Long
