package com.astrocompass.telescope

/**
 * A hand-controller direction, as LX200 names them: [NORTH]/[SOUTH] drive the declination (or
 * altitude) axis, [EAST]/[WEST] the right-ascension (or azimuth) one. [wireCharacter] is the
 * literal suffix shared by the move and stop commands for that axis -- `:Mn#` starts, `:Qn#`
 * stops -- see [Lx200Codec.startMove].
 */
enum class TelescopeDirection(val wireCharacter: Char) {
    NORTH('n'),
    SOUTH('s'),
    EAST('e'),
    WEST('w'),
}

/**
 * How fast a held [TelescopeDirection] moves the mount. Entirely separate from [SlewRatePreset],
 * which is the *GOTO* speed: OnStep keeps one rate for commanded slews and another for manual
 * moves, so setting one has no effect on the other.
 *
 * The multipliers are OnStep's own fixed presets (`Guide.command.cpp`), relative to the sidereal
 * rate -- absolute figures, unlike [SlewRatePreset]'s relative ones. [SLEW] is the exception and
 * carries OnStep's own name rather than a multiple, because it has none: it is half whatever the
 * mount's current GOTO rate happens to be, which is usually but not necessarily the fastest of
 * these -- labelling it "Max" would be a claim the command does not make.
 */
enum class MoveRatePreset(val label: String, val command: String) {
    GUIDE("1×", ":RG#"),
    CENTER("8×", ":RC#"),
    FIND("20×", ":RM#"),
    FAST("48×", ":RF#"),
    SLEW("Slew", ":RS#");

    companion object {
        /** Fast enough to cross the gap a GOTO leaves, slow enough not to overshoot the eyepiece
         *  -- the rate a user centering an alignment star actually wants first. */
        val DEFAULT = CENTER
    }
}
