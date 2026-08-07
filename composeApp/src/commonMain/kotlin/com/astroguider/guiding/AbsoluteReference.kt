package com.astroguider.guiding

import com.astroguider.alignment.AlignmentModel
import com.astroguider.astro.Quaternion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A snapshot of "how do we anchor the live sensor to the true sky". */
data class AbsoluteReferenceState(
    val sensorToSky: Quaternion,
    val establishedAtEpochMillis: Long,
    val uncertaintyDegrees: Double,
)

/**
 * One absolute reference, fused with the continuous relative sensor stream by [PointingService].
 * In v1 there is exactly one implementation ([AlignmentAbsoluteReference]); a future camera
 * plate solve would be a second, self-refreshing implementation plugged into this same seam,
 * without [PointingService] changing at all.
 */
interface AbsoluteReference {
    val current: StateFlow<AbsoluteReferenceState?>
}

/** Wraps the star-alignment model: absolute reference source for v1. */
class AlignmentAbsoluteReference : AbsoluteReference {
    private val _current = MutableStateFlow<AbsoluteReferenceState?>(null)
    override val current: StateFlow<AbsoluteReferenceState?> = _current

    fun update(model: AlignmentModel?) {
        _current.value = model?.let {
            AbsoluteReferenceState(it.sensorToSky, it.computedAtEpochMillis, it.rmsResidualDegrees)
        }
    }
}
