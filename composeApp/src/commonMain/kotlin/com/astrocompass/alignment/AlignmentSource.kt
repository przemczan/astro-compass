package com.astrocompass.alignment

/** How an [AlignmentPoint] was captured. Exists as a field (not inlined into the capture flow)
 *  so a future camera plate-solve point is a first-class input alongside a manual tap, without
 *  reworking [AlignmentModel] or [AlignmentStore]. */
enum class AlignmentSource { MANUAL_SYNC, RE_SYNC }
