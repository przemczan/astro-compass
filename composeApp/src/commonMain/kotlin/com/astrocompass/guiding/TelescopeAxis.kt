package com.astrocompass.guiding

import com.astrocompass.astro.Vector3

/**
 * Which physical edge/face of the phone is rigidly aligned with the telescope's optical axis.
 * Device axes follow Android's own sensor convention: +X = right edge, +Y = top edge, +Z = out
 * of the screen toward the viewer (phone held upright, portrait, screen facing the viewer).
 *
 * Only the two mountings the wizard actually produces are offered -- [TOP_EDGE] (a mirror/
 * diagonal, or a sensors-only clamp along the tube) and [BACK_FACE] (no mirror, camera straight
 * down the tube); see `CameraCalibrationSteps`' `MirrorChoiceStep` and the sensors-only branch's
 * own doc comment in `CLAUDE.md`. There is no mount this app supports that puts a side edge or the
 * screen face down the tube, so those aren't modeled.
 *
 * It is the fixed vector rotated by the live sensor reading to build each
 * [com.astrocompass.alignment.AlignmentPoint]. The 2-3 star fit absorbs a wrong choice almost
 * entirely, so this default is a starting point, not a precision requirement. A
 * [com.astrocompass.alignment.AlignmentType.PLATE_SOLVE] setup instead refines this into
 * [com.astrocompass.AppContainer.effectiveTelescopeDirection], the camera-crosshair-calibrated
 * direction, once it has captured at least one frame.
 */
enum class TelescopeAxis(val label: String, val deviceVector: Vector3) {
    TOP_EDGE("Top edge", Vector3(0.0, 1.0, 0.0)),
    BACK_FACE("Back face", Vector3(0.0, 0.0, -1.0));

    companion object {
        /** Phone strapped lengthwise along the tube, top edge toward the target -- the most
         *  common mounting for a clamp-style phone holder. */
        val DEFAULT = TOP_EDGE
    }
}
