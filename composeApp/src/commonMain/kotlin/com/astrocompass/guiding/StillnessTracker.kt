package com.astrocompass.guiding

import com.astrocompass.astro.Vector3

/**
 * Decides when the telescope has been holding still long enough to photograph -- a plate solve
 * needs an unsmeared frame, and re-anchoring off one taken mid-slew would be worse than not
 * re-anchoring at all.
 *
 * Pure and clock-injected: every caller passes wall-clock millis in, because
 * [com.astrocompass.sensors.DeviceOrientation.timestampMillis] is the platform sensor event's own
 * monotonic clock, which shares neither base nor unit with epoch time.
 *
 * "Still" means every reading since the anchor has stayed within [toleranceDegrees] of it. Any
 * reading outside that becomes the new anchor and restarts the clock, so a slow drift across the
 * sky never accumulates into a false "still" the way a reading-to-reading delta test would.
 */
class StillnessTracker(
    private val toleranceDegrees: Double,
    private val holdMillis: Long,
) {
    private var anchor: Vector3? = null
    private var anchoredAtEpochMillis = 0L

    /** Feeds one pointing direction, and answers whether the hold is satisfied as of now. */
    fun update(direction: Vector3, nowEpochMillis: Long): Boolean {
        val currentAnchor = anchor
        if (currentAnchor == null || currentAnchor.angleTo(direction).degrees > toleranceDegrees) {
            anchor = direction
            anchoredAtEpochMillis = nowEpochMillis
            return false
        }
        return nowEpochMillis - anchoredAtEpochMillis >= holdMillis
    }

    /** Forgets the current hold, so the next [update] starts a fresh one -- called after acting on
     *  a satisfied hold, since the very next reading would otherwise report it satisfied again. */
    fun reset() {
        anchor = null
    }
}
