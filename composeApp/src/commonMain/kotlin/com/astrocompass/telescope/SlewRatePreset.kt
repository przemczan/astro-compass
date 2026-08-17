package com.astrocompass.telescope

/**
 * The five GOTO speeds OnStep exposes through `:SX93,n#` (see [Lx200Codec.setSlewRatePreset]).
 *
 * [onStepParameter] is the literal wire character, and its direction is the counter-intuitive
 * part: OnStep stores *microseconds per step*, so `'1'` halves that (base rate / 2.0 -- twice as
 * fast) and `'5'` doubles it (base rate * 2.0 -- half speed). Verified against OnStepX's own
 * `Goto.command.cpp` rather than guessed, since the command answers nothing at all -- a flipped
 * mapping would silently slew at the opposite speed instead of failing.
 *
 * The speeds are multiples of whatever base rate the mount itself is configured for, not absolute
 * rates, which is why the labels are relative rather than a figure in degrees per second.
 */
enum class SlewRatePreset(val label: String, val onStepParameter: Char) {
    FASTEST("Fastest", '1'),
    FASTER("Faster", '2'),
    NORMAL("Normal", '3'),
    SLOWER("Slower", '4'),
    SLOWEST("Slowest", '5');

    companion object {
        /** The mount's own configured base rate -- what it slews at with no preset ever sent. */
        val DEFAULT = NORMAL
    }
}
