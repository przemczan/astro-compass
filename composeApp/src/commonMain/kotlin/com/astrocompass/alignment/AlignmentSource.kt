package com.astrocompass.alignment

/** How an [AlignmentPoint] was captured. Exists as a field (not inlined into the capture flow)
 *  so a future camera plate-solve point is a first-class input alongside a manual tap, without
 *  reworking [AlignmentModel] or [AlignmentStore].
 *
 *  Nothing produces [RE_SYNC] any more -- it is kept because [AlignmentStore] persists this name
 *  verbatim, and an unknown value fails the whole model's decode rather than just that point's. */
enum class AlignmentSource { MANUAL_SYNC, RE_SYNC }
