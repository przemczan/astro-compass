package com.astrocompass.guiding

import com.astrocompass.alignment.AlignmentModel
import com.astrocompass.astro.Quaternion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Where an [AbsoluteReferenceState] came from. Kept distinct from [AbsoluteReferenceState
 *  .uncertaintyDegrees] because the two answer different questions: a star fit with a poor
 *  residual is still a star fit, and only [COMPASS] warrants telling the user the whole pointing
 *  solution is provisional. */
enum class ReferenceOrigin { STAR_ALIGNMENT, COMPASS }

/** A snapshot of "how do we anchor the live sensor to the true sky". */
data class AbsoluteReferenceState(
    val sensorToSky: Quaternion,
    val establishedAtEpochMillis: Long,
    val uncertaintyDegrees: Double,
    val origin: ReferenceOrigin,
)

/**
 * One absolute reference, fused with the continuous relative sensor stream by [PointingService].
 * [AlignmentAbsoluteReference] (star syncs, and plate solves applied on top of them) and
 * [CompassAbsoluteReference] (magnetometer only) are both implementations; [PointingService]
 * consumes exactly one, so they are combined by [PrioritizedAbsoluteReference] rather than
 * consumed side by side.
 */
interface AbsoluteReference {
    val current: StateFlow<AbsoluteReferenceState?>
}

/** Wraps the star-alignment model: the app's only *real* absolute reference. */
class AlignmentAbsoluteReference : AbsoluteReference {
    private val _current = MutableStateFlow<AbsoluteReferenceState?>(null)
    override val current: StateFlow<AbsoluteReferenceState?> = _current

    fun update(model: AlignmentModel?) {
        _current.value = model?.let {
            AbsoluteReferenceState(
                sensorToSky = it.sensorToSky,
                establishedAtEpochMillis = it.computedAtEpochMillis,
                uncertaintyDegrees = it.rmsResidualDegrees,
                origin = ReferenceOrigin.STAR_ALIGNMENT,
            )
        }
    }
}

/**
 * Uses [preferred] whenever it has a reference at all, [fallback] otherwise -- never blends them.
 * A real alignment is strictly better than a compass estimate on every axis, so there is nothing
 * for a blend to gain, and consumers can key entirely off
 * [AbsoluteReferenceState.origin] to know which one they are looking at.
 */
class PrioritizedAbsoluteReference(
    scope: CoroutineScope,
    preferred: AbsoluteReference,
    fallback: AbsoluteReference,
) : AbsoluteReference {
    override val current: StateFlow<AbsoluteReferenceState?> =
        combine(preferred.current, fallback.current) { primary, secondary -> primary ?: secondary }
            .stateIn(scope, SharingStarted.Eagerly, preferred.current.value ?: fallback.current.value)
}

/**
 * Whichever of [sources] was established most recently, ignoring the ones with nothing to say.
 *
 * For references that are *peers* -- every one of them a real fit, differing only in when it was
 * made. A stored star alignment and a live plate solve are exactly that pair: neither is
 * categorically better, so a fixed priority would either let a stale background solve shadow an
 * alignment the user just finished, or let an hours-old star fit shadow a solve from ten seconds
 * ago. Freshness answers both without either side knowing the other exists.
 *
 * Not for [CompassAbsoluteReference], which re-establishes itself on every sensor reading and so
 * would always be the freshest thing here -- it stays a strict fallback (see
 * [PrioritizedAbsoluteReference]).
 */
class FreshestAbsoluteReference(
    scope: CoroutineScope,
    private vararg val sources: AbsoluteReference,
) : AbsoluteReference {
    override val current: StateFlow<AbsoluteReferenceState?> =
        combine(sources.map { it.current }) { states -> states.freshest() }
            .stateIn(scope, SharingStarted.Eagerly, sources.map { it.current.value }.toTypedArray().freshest())

    private fun Array<out AbsoluteReferenceState?>.freshest(): AbsoluteReferenceState? =
        filterNotNull().maxByOrNull { it.establishedAtEpochMillis }
}
