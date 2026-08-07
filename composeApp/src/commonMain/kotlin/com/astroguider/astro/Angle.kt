package com.astroguider.astro

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt

private const val DEG_TO_RAD = PI / 180.0
private const val RAD_TO_DEG = 180.0 / PI

/** An angle, stored internally as degrees. Avoids ambiguity between degree- and radian-valued Doubles. */
@JvmInline
value class Angle private constructor(val degrees: Double) {

    val radians: Double get() = degrees * DEG_TO_RAD
    val hours: Double get() = degrees / 15.0

    operator fun plus(other: Angle) = Angle(degrees + other.degrees)
    operator fun minus(other: Angle) = Angle(degrees - other.degrees)
    operator fun unaryMinus() = Angle(-degrees)
    operator fun times(scalar: Double) = Angle(degrees * scalar)
    operator fun div(scalar: Double) = Angle(degrees / scalar)
    operator fun compareTo(other: Angle) = degrees.compareTo(other.degrees)

    /** Normalized to [0, 360). */
    fun normalized(): Angle {
        val d = degrees % 360.0
        return Angle(if (d < 0) d + 360.0 else d)
    }

    /** Normalized to [-180, 180) — the shortest signed offset representation. */
    fun normalizedSigned(): Angle {
        val d = normalized().degrees
        return Angle(if (d >= 180.0) d - 360.0 else d)
    }

    /** Formats as sexagesimal hours:minutes:seconds, e.g. "18h 36m 56.3s". */
    fun formatHms(): String {
        val h = normalized().hours
        return formatSexagesimal(h, "h", "m", "s", signed = false)
    }

    /** Formats as signed sexagesimal degrees:arcmin:arcsec, e.g. "+38° 47' 01"". */
    fun formatDms(): String {
        return formatSexagesimal(degrees, "°", "'", "\"", signed = true)
    }

    private fun formatSexagesimal(value: Double, unit1: String, unit2: String, unit3: String, signed: Boolean): String {
        val sign = if (signed && value < 0) "-" else if (signed) "+" else ""
        val absValue = kotlin.math.abs(value)
        val whole = floor(absValue).toInt()
        val minutesFull = (absValue - whole) * 60.0
        val minutes = floor(minutesFull).toInt()
        val seconds = (minutesFull - minutes) * 60.0
        return "$sign$whole$unit1 $minutes$unit2 ${roundTo1(seconds)}$unit3"
    }

    private fun roundTo1(value: Double): Double = (value * 10.0).roundToInt() / 10.0

    companion object {
        val ZERO = Angle(0.0)

        fun ofDegrees(degrees: Double) = Angle(degrees)
        fun ofRadians(radians: Double) = Angle(radians * RAD_TO_DEG)
        fun ofHours(hours: Double) = Angle(hours * 15.0)
        fun ofDms(degrees: Int, arcmin: Int, arcsec: Double): Angle {
            val magnitude = kotlin.math.abs(degrees) + arcmin / 60.0 + arcsec / 3600.0
            return Angle(if (degrees < 0) -magnitude else magnitude)
        }
        fun ofHms(hours: Int, minutes: Int, seconds: Double): Angle =
            ofHours(hours + minutes / 60.0 + seconds / 3600.0)
    }
}

fun Double.deg() = Angle.ofDegrees(this)
fun Double.rad() = Angle.ofRadians(this)
fun Double.hours() = Angle.ofHours(this)
