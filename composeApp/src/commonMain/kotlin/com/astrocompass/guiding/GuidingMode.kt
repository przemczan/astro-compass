package com.astrocompass.guiding

/**
 * Which source drives guidance, picked by the user from the toolbar rather than inferred from what
 * happens to be plugged in. The two describe where the phone physically is: under [PHONE] the
 * phone rides the telescope and *is* the guider, so everything keys off its own sensors and its own
 * alignment; under [TELESCOPE] the phone is in the user's hand as a control surface and the mount
 * reports its own position. Selecting a mode never blends the two -- the same never-blend rule
 * [PrioritizedAbsoluteReference] follows one layer down.
 *
 * [TELESCOPE] is only selectable while a mount is actually connected; see
 * [com.astrocompass.AppContainer.guidingMode] for how a dropped connection falls back to [PHONE]
 * without discarding the user's choice.
 */
enum class GuidingMode(val label: String) {
    PHONE("Phone"),
    TELESCOPE("Telescope"),
}
