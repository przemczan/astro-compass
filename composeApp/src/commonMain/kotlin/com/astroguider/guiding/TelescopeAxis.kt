package com.astroguider.guiding

import com.astroguider.astro.Vector3

/**
 * Which physical edge/face of the phone is rigidly aligned with the telescope's optical axis.
 * Device axes follow Android's own sensor convention: +X = right edge, +Y = top edge, +Z = out
 * of the screen toward the viewer (phone held upright, portrait, screen facing the viewer).
 *
 * It is the fixed vector rotated by the live sensor reading to build each
 * [com.astroguider.alignment.AlignmentPoint]. The 2-3 star fit absorbs a wrong choice almost
 * entirely, so this default is a starting point, not a precision requirement.
 */
enum class TelescopeAxis(val label: String, val deviceVector: Vector3) {
    TOP_EDGE("Top edge", Vector3(0.0, 1.0, 0.0)),
    BOTTOM_EDGE("Bottom edge", Vector3(0.0, -1.0, 0.0)),
    RIGHT_EDGE("Right edge", Vector3(1.0, 0.0, 0.0)),
    LEFT_EDGE("Left edge", Vector3(-1.0, 0.0, 0.0)),
    SCREEN_FACE("Screen face", Vector3(0.0, 0.0, 1.0)),
    BACK_FACE("Back face", Vector3(0.0, 0.0, -1.0));

    companion object {
        /** Phone strapped lengthwise along the tube, top edge toward the target -- the most
         *  common mounting for a clamp-style phone holder. */
        val DEFAULT = TOP_EDGE
    }
}
