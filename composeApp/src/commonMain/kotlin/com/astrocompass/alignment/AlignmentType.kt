package com.astrocompass.alignment

/**
 * Which instrument the user aligned with, chosen once at the top of the alignment wizard and kept
 * afterwards because it decides how guiding behaves, not just how the wizard looked.
 *
 * Persisted in [com.astrocompass.settings.AppPreferences] rather than on [AlignmentModel]: a
 * [PLATE_SOLVE] setup replaces its model every time a background solve lands, so a field on the
 * model would be rewritten by the very mechanism that depends on it.
 */
enum class AlignmentType(val label: String) {
    /** Star syncs only. Guiding runs entirely off the fitted model and the sensor stream. */
    SENSORS_ONLY("Phone sensors"),

    /** A calibrated phone camera. Guiding still points with the sensors, but re-anchors them from a
     *  plate solve whenever the telescope holds still -- see
     *  [com.astrocompass.guiding.AutoPlateSolveRefiner]. */
    PLATE_SOLVE("Phone sensors + camera"),
}
