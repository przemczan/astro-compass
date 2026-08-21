package com.astrocompass.telescope

import com.astrocompass.astro.Angle
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Outcome of an `:MS#` slew command -- kept distinct from a plain boolean since a rejection
 *  carries the mount's reason (e.g. "Object Below Horizon"). Parsed by [Lx200Session], not here:
 *  reading the reply needs conditional length (a bare `'0'` has no trailing `#`, unlike every
 *  other LX200 reply), which is transport-sequencing logic, not codec logic. */
sealed interface SlewAck {
    data object Started : SlewAck
    data class Rejected(val reason: String) : SlewAck
}

/**
 * Pure encode/parse for the Meade "LX200" text protocol -- no I/O. Wire strings are always
 * ASCII, `#`-terminated. [Lx200Session] owns stripping/appending terminators and the read
 * sequencing; the functions here only ever see (or produce) the command/reply body.
 *
 * Deliberately does not reuse [Angle.formatHms]/[Angle.formatDms] -- those produce unicode
 * display strings ("18h 36m 56.3s"), not this protocol's colon-delimited wire format.
 *
 * Dec's minute/second delimiter is documented inconsistently across Meade-lineage mounts and
 * clones as both `sDD*MM:SS#` and `sDD*MM'SS#`, and low-precision mode drops to `sDD*MM#`/
 * `HH:MM.T#`. Parsing accepts either; encoding always emits the more broadly-compatible `:`
 * high-precision form.
 */
object Lx200Codec {
    fun getRightAscension(): String = ":GR#"
    fun getDeclination(): String = ":GD#"
    fun slewToTarget(): String = ":MS#"
    fun abortSlew(): String = ":Q#"

    // -- Mount alignment (AlignmentScreen's Telescope mode) -----------------------------------
    //
    // Verified against OnStepX's own source (hjd1964/OnStepX, `Goto.command.cpp` / `Goto.cpp`) and
    // its `docs/COMMAND_REFERENCE.md`, for the same reason the site/time commands below were.
    //
    // The sequence is stateful on the mount: [beginAlignment] arms it, each [syncToTarget] adds one
    // point, and the point that takes it past the requested star count is the one that computes the
    // model -- after which [writeAlignmentModel] persists it. There is no abort command in the
    // protocol; a re-sent [beginAlignment] is the only way back out of a half-finished sequence.

    /** `:A<n>#` -- arm an n-star alignment. Ack via [parseAck].
     *
     *  **Destructive, and not just to the model.** OnStepX's handler resets the home position
     *  (`home.reset()`, or `home.requestWithReset()` -- an actual slew -- on a build with
     *  `ALIGN_AUTO_HOME`), so the mount must physically be at home first, and it forces tracking
     *  on regardless of what it was doing. */
    fun beginAlignment(starCount: Int): String {
        require(starCount in 1..9) { "LX200 supports 1-9 alignment stars, not $starCount" }
        return ":A$starCount#"
    }

    /** `:hC#` -- slew the mount to its home position. Answers nothing at all, so it goes out
     *  through [Lx200Session.executeNoReply]. Offered alongside [beginAlignment] because that
     *  command declares wherever the mount *currently* stands to be home -- getting it genuinely
     *  home first is the difference between a real alignment and one built on a fiction. */
    fun moveToHome(): String = ":hC#"

    /** `:AW#` -- persist the alignment model to the mount's non-volatile storage. Ack via
     *  [parseAck]. Without it a completed model is live but lost on the next power cycle. */
    fun writeAlignmentModel(): String = ":AW#"

    /** `:CM#` -- tell the mount it is currently pointed at the target set by
     *  [setTargetRightAscension]/[setTargetDeclination].
     *
     *  **Means two different things depending on mount state**, deliberately so: with no alignment
     *  armed it corrects the mount's pointing origin outright (`requestSync`), while inside a
     *  [beginAlignment] sequence the same command instead contributes one point to the model
     *  (`alignAddStar`). Preferred over `:A+#` for that second role because `:CM#` passes
     *  `sync = true`, which takes the point from the target just set here; `:A+#` passes `false`
     *  and reuses whatever target a previous `:MS#` slew left behind, so a confirm without a GOTO
     *  in front of it would add a stale point.
     *
     *  Chosen over the otherwise-equivalent `:CS#`, which OnStepX answers with nothing at all:
     *  `:CM#` sets `numericReply = false` *and* a non-empty reply body, so the frame `'#'` is
     *  appended (`libApp/commands/ProcessCmds.cpp`) and it reads as an ordinary
     *  [Lx200Session.executeHashTerminated] exchange. Parse via [parseSyncAccepted]. */
    fun syncToTarget(): String = ":CM#"

    /** Parses a [syncToTarget] reply body (terminator already stripped). OnStepX answers `N/A` on
     *  success and `E1`..`E9` on refusal (`Goto.command.cpp`), with no message text to show, so
     *  the outcome collapses to a boolean here and the caller supplies the wording. */
    fun parseSyncAccepted(reply: String): Boolean = reply.trim() == "N/A"

    /** `:Sr HH:MM:SS#` -- set target right ascension. Ack via [parseAck]. */
    fun setTargetRightAscension(ra: Angle): String {
        val (h, m, s) = sexagesimalParts(ra.normalized().hours)
        return ":Sr ${pad2(h)}:${pad2(m)}:${pad2(s)}#"
    }

    /** `:Sd sDD*MM:SS#` -- set target declination. Ack via [parseAck]. */
    fun setTargetDeclination(dec: Angle): String {
        val sign = if (dec.degrees < 0) "-" else "+"
        val (d, m, s) = sexagesimalParts(abs(dec.degrees))
        return ":Sd $sign${pad2(d)}*${pad2(m)}:${pad2(s)}#"
    }

    // -- Mount sync (Lx200TelescopeConnection.syncMount): date/time, site, and unpark --------
    //
    // Verified against OnStep's own source (hjd1964/OnStep, Command.ino, release-4.24) rather
    // than guessed, since a wrong sign here silently points the mount at the wrong sky instead of
    // failing loudly:
    //  - Site longitude is west-positive/east-negative (OnStep's own comment: "east longitudes can
    //    be negative or > 180 degrees") -- the *opposite* of this app's own east-positive
    //    [ObserverLocation.longitude] (see [com.astrocompass.astro.time.AstroTime.localSiderealTime]'s
    //    doc comment), so [setSiteLongitude] negates before encoding.
    //  - The UTC offset command's sign is the reverse of the intuitive time-zone sign (OnStep's own
    //    docs: EST, time zone -5, has UTC Offset +5). [setUtcOffset] is only ever called with 0 by
    //    [Lx200TelescopeConnection.syncMount] specifically to sidestep this: with the offset at
    //    zero, the mount's "local time" concept becomes UTC, so [setDate]/[setTime] can send
    //    [com.astrocompass.astro.time.currentEpochMillis] straight through with no time-zone/DST
    //    logic anywhere in this app.
    //  - Site latitude is north-positive, matching [ObserverLocation.latitude] already -- no flip.
    //
    // The space after the command mnemonic (matching [setTargetRightAscension]/
    // [setTargetDeclination]'s convention above) is accepted -- confirmed on the wire against a
    // real OnStep mount, which answered `:SG +00#` with an ack rather than a rejection. Every one
    // of these commands answers with a bare, *unterminated* character; see [parseAck] and
    // [Lx200Session.executeCharAck].

    /** `:SC MM/DD/YY#` -- set the mount's calendar date. Always UTC, see [setUtcOffset]. Ack via
     *  [parseAck]. */
    fun setDate(year: Int, month: Int, day: Int): String =
        ":SC ${pad2(month)}/${pad2(day)}/${pad2(year % 100)}#"

    /** `:SL HH:MM:SS#` -- set the mount's time of day. Always UTC, see [setUtcOffset]. Ack via
     *  [parseAck]. */
    fun setTime(hour: Int, minute: Int, second: Int): String =
        ":SL ${pad2(hour)}:${pad2(minute)}:${pad2(second)}#"

    /** `:SG sHH#` -- set the mount's UTC offset. Ack via [parseAck]. */
    fun setUtcOffset(hours: Int): String {
        val sign = if (hours < 0) "-" else "+"
        return ":SG $sign${pad2(abs(hours))}#"
    }

    /** `:St sDD*MM#` -- set site latitude, north-positive. Ack via [parseAck]. */
    fun setSiteLatitude(latitude: Angle): String {
        val sign = if (latitude.degrees < 0) "-" else "+"
        val (d, m, _) = sexagesimalParts(abs(latitude.degrees))
        return ":St $sign${pad2(d)}*${pad2(m)}#"
    }

    /** `:Sg sDDD*MM#` -- set site longitude, west-positive (the opposite sign convention from
     *  this app's own [ObserverLocation.longitude] -- see the section doc above). Ack via
     *  [parseAck]. */
    fun setSiteLongitude(longitude: Angle): String {
        val westPositiveDegrees = -longitude.degrees
        val sign = if (westPositiveDegrees < 0) "-" else "+"
        val (d, m, _) = sexagesimalParts(abs(westPositiveDegrees))
        return ":Sg $sign${pad3(d)}*${pad2(m)}#"
    }

    /** `:hR#` -- unpark, resuming operation with whatever alignment model the mount already has.
     *  An OnStep extension, not classic LX200. Ack via [parseAck]. */
    fun unpark(): String = ":hR#"

    // -- Mount options (TelescopeOptionsSheet): GOTO speed and tracking ------------------------
    //
    // Verified against OnStepX's own source (hjd1964/OnStepX, `Goto.command.cpp` and
    // `Mount.command.cpp`) for the same reason the sync commands above were.

    /** `:SX93,n#` -- set the GOTO speed preset (see [SlewRatePreset]).
     *
     *  Two things set this apart from every other Set command here. It answers **nothing at all**
     *  (OnStepX sets `numericReply = false`), so it goes out through [Lx200Session.executeNoReply];
     *  reading an ack instead would block until the timeout tore the whole connection down. And it
     *  takes **no space after the mnemonic**, unlike the commands above: OnStepX picks the preset
     *  out of the parameter positionally (`parameter[3]` of `93,n`), so a space would shift the
     *  digit out of range and silently select the base rate.
     *
     *  OnStep also ignores this outright while a slew or guide is already running -- silently,
     *  since there's no reply to carry the refusal -- which is why
     *  [com.astrocompass.AppContainer.slewTelescopeTo] re-sends it before every slew. */
    fun setSlewRatePreset(preset: SlewRatePreset): String = ":SX93,${preset.onStepParameter}#"

    /** `:Te#` / `:Td#` -- start/stop sidereal tracking. Ack via [parseAck]; a parked mount rejects
     *  the enable (OnStepX answers `CE_PARKED`). */
    fun setTracking(enabled: Boolean): String = if (enabled) ":Te#" else ":Td#"

    /** `:GU#` -- OnStep's general status string, a run of single-character flags. Parsed by
     *  [parseTrackingEnabled]; nothing else in the app reads it. */
    fun getStatus(): String = ":GU#"

    /** Parses a `:GR#` reply body (terminator already stripped): `HH:MM:SS` (high precision) or
     *  `HH:MM.T` (low precision, T = tenths of a minute). */
    fun parseRightAscension(reply: String): Angle {
        val parts = reply.trim().split(":")
        return when (parts.size) {
            3 -> Angle.ofHms(parts[0].toInt(), parts[1].toInt(), parts[2].toDouble())
            2 -> {
                val hours = parts[0].toInt()
                val minutesFull = parts[1].toDouble()
                val minutes = floor(minutesFull).toInt()
                val seconds = (minutesFull - minutes) * 60.0
                Angle.ofHms(hours, minutes, seconds)
            }
            else -> throw IllegalArgumentException("Unrecognized RA reply: $reply")
        }
    }

    /** Parses a `:GD#` reply body (terminator already stripped): `sDD*MM'SS`, `sDD*MM:SS`, or
     *  the low-precision `sDD*MM` (no seconds). */
    fun parseDeclination(reply: String): Angle {
        val trimmed = reply.trim()
        val negative = trimmed.startsWith("-")
        val unsigned = trimmed.removePrefix("+").removePrefix("-")
        val starIndex = unsigned.indexOf('*')
        require(starIndex >= 0) { "Unrecognized Dec reply: $reply" }

        val degrees = unsigned.substring(0, starIndex).toInt()
        val rest = unsigned.substring(starIndex + 1)
        val minutesEnd = rest.indexOfFirst { !it.isDigit() }.let { if (it < 0) rest.length else it }
        val minutes = rest.substring(0, minutesEnd).toInt()
        val seconds = if (minutesEnd < rest.length) rest.substring(minutesEnd + 1).toDoubleOrNull() ?: 0.0 else 0.0

        val magnitude = degrees + minutes / 60.0 + seconds / 3600.0
        return Angle.ofDegrees(if (negative) -magnitude else magnitude)
    }

    /** Every Set-command's ack (`:Sr#`, `:Sd#`, `:SC#`, `:SL#`, `:SG#`, `:St#`, `:Sg#`, `:hR#`),
     *  read by [Lx200Session.executeCharAck] -- a single character, no `#` terminator. NOTE
     *  inverted vs. [slewToTarget]'s reply: `"1"` means accepted, `"0"` means invalid. */
    fun parseAck(reply: String): Boolean = reply.trim() == "1"

    /** Reads "the mount is at its home position" out of a [getStatus] reply body -- OnStep's `H`
     *  flag. A plain search is safe where [parseTrackingEnabled] needs a positional test: `H` is
     *  the only uppercase H in the flag set (`h`, lowercase, is *homing* and deliberately does not
     *  match). Throws on an empty reply for the same reason [parseTrackingEnabled] does. */
    fun parseAtHome(status: String): Boolean {
        val flags = status.trim()
        require(flags.isNotEmpty()) { "Empty status reply" }
        return flags.contains('H')
    }

    /** Reads tracking state out of a [getStatus] reply body.
     *
     *  Tests the *first* character rather than searching the whole string for `'n'`: "not tracking"
     *  is the very first flag OnStep appends, so nothing can ever precede it, while a plain search
     *  would also match the `'r'`,`'n'` pair classic OnStep emits for "no rate compensation" and
     *  report a happily tracking mount as stopped.
     *
     *  Throws on an empty reply rather than reading it as "tracking on" -- the caller renders an
     *  unknown state as such (see [TelescopeConnection.readTrackingEnabled]), and silently
     *  answering "on" for a mount that said nothing is the one guess this whole path exists to
     *  avoid. */
    fun parseTrackingEnabled(status: String): Boolean {
        val flags = status.trim()
        require(flags.isNotEmpty()) { "Empty status reply" }
        return !flags.startsWith('n')
    }

    private fun pad2(value: Int): String = value.toString().padStart(2, '0')
    private fun pad3(value: Int): String = value.toString().padStart(3, '0')

    private fun sexagesimalParts(magnitude: Double): Triple<Int, Int, Int> {
        val whole = floor(magnitude).toInt()
        val minutesFull = (magnitude - whole) * 60.0
        var minutes = floor(minutesFull).toInt()
        var seconds = ((minutesFull - minutes) * 60.0).roundToInt()
        var wholeOut = whole
        if (seconds == 60) {
            seconds = 0
            minutes += 1
        }
        if (minutes == 60) {
            minutes = 0
            wholeOut += 1
        }
        return Triple(wholeOut, minutes, seconds)
    }
}
