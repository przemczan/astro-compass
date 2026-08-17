package com.astrocompass.guiding

/**
 * Which source drives guidance, picked by the user from the Guidance screen's toolbar rather than
 * inferred from what happens to be plugged in. [MANUAL] is exactly the phone-only experience
 * someone with no mount gets; [TELESCOPE] is exactly the connected-mount one. Selecting a mode
 * never blends the two -- the same never-blend rule [PrioritizedAbsoluteReference] follows one
 * layer down.
 *
 * [TELESCOPE] is only selectable while a mount is actually connected; see
 * [com.astrocompass.AppContainer.guidingMode] for how a dropped connection falls back to [MANUAL]
 * without discarding the user's choice.
 */
enum class GuidingMode(val label: String) {
    MANUAL("Manual"),
    TELESCOPE("Telescope"),
}
