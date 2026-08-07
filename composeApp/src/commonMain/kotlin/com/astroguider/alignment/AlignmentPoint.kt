package com.astroguider.alignment

import com.astroguider.astro.Vector3

/**
 * One star sync: the telescope's configured pointing axis, as seen in two frames at the exact
 * same instant.
 *
 * Invariant: [skyDirection] is the target's true sky (ENU) unit vector computed AT
 * [capturedAtEpochMillis], never recomputed later at model-solve time. The sky rotates ~15"/s,
 * so evaluating every point's sky vector "now" instead of "then" would bake sky rotation
 * straight into the fit -- about 0.75 degrees over a 3-minute alignment sequence.
 */
data class AlignmentPoint(
    /** Target's true ENU unit vector at capture time. */
    val skyDirection: Vector3,
    /** The configured telescope axis rotated by the live orientation sensor reading, at the
     *  same capture time -- i.e. where that axis pointed in the sensor's own reference frame. */
    val sensorDirection: Vector3,
    val capturedAtEpochMillis: Long,
    val targetId: String,
    val source: AlignmentSource,
)
