package com.astroguider.alignment

import com.astroguider.astro.Quaternion

/**
 * The solved rigid rotation from the sensor's own reference frame to true sky (ENU). A single
 * quaternion absorbs both the sensor's arbitrary/drifting horizontal reference and any fixed
 * phone-mounting offset -- see [AlignmentSolver] for why one fit handles both at once.
 */
data class AlignmentModel(
    val sensorToSky: Quaternion,
    val points: List<AlignmentPoint>,
    /** Root-mean-square angular residual across [points], in degrees. Mixes true pointing error
     *  with any yaw drift that occurred *between* points during capture -- informative, not a
     *  precise error bound. */
    val rmsResidualDegrees: Double,
    val computedAtEpochMillis: Long,
)
