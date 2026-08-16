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

    /** `:Sr HH:MM:SS#` -- set target right ascension. Ack via [parseTargetSetAck]. */
    fun setTargetRightAscension(ra: Angle): String {
        val (h, m, s) = sexagesimalParts(ra.normalized().hours)
        return ":Sr ${pad2(h)}:${pad2(m)}:${pad2(s)}#"
    }

    /** `:Sd sDD*MM:SS#` -- set target declination. Ack via [parseTargetSetAck]. */
    fun setTargetDeclination(dec: Angle): String {
        val sign = if (dec.degrees < 0) "-" else "+"
        val (d, m, s) = sexagesimalParts(abs(dec.degrees))
        return ":Sd $sign${pad2(d)}*${pad2(m)}:${pad2(s)}#"
    }

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

    /** `:Sr#`/`:Sd#` ack -- NOTE inverted vs. [slewToTarget]'s reply: `"1"` means accepted,
     *  `"0"` means invalid. */
    fun parseTargetSetAck(reply: String): Boolean = reply.trim().removeSuffix("#") == "1"

    private fun pad2(value: Int): String = value.toString().padStart(2, '0')

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
