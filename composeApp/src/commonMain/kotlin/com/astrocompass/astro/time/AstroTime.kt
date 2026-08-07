package com.astrocompass.astro.time

import com.astrocompass.astro.Angle

/** Julian Day at the Unix epoch (1970-01-01T00:00:00 UTC) — the defining constant used to
 *  go from device wall-clock time straight to JD without a calendar-arithmetic algorithm. */
private const val JD_AT_UNIX_EPOCH = 2440587.5
private const val MILLIS_PER_DAY = 86_400_000.0

/** Julian Day of the J2000.0 epoch (2000-01-01T12:00:00 UTC/TT) — the standard reference epoch. */
const val JD_J2000 = 2451545.0
private const val JULIAN_DAYS_PER_CENTURY = 36525.0

object AstroTime {

    /** Julian Day for a device timestamp. Treats UTC as TT (ΔT ≈ 70s in the 2020s — negligible
     *  against this app's 0.5°+ pointing budget, so no leap-second/ΔT table is carried). */
    fun julianDay(epochMillis: Long): Double =
        epochMillis / MILLIS_PER_DAY + JD_AT_UNIX_EPOCH

    /** Julian centuries elapsed since J2000.0 — the standard time argument for the low-precision
     *  ephemeris and precession formulas. */
    fun julianCenturiesJ2000(julianDay: Double): Double =
        (julianDay - JD_J2000) / JULIAN_DAYS_PER_CENTURY

    /**
     * Greenwich Mean Sidereal Time, IAU 1982 formula (Meeus, *Astronomical Algorithms* ch. 12).
     * At T=0 (JD = [JD_J2000]) this reduces exactly to the leading constant 280.46061837°,
     * the independently-citable GMST at the J2000.0 epoch.
     */
    fun greenwichMeanSiderealTime(julianDay: Double): Angle {
        val t = julianCenturiesJ2000(julianDay)
        val days = julianDay - JD_J2000
        val degrees = 280.46061837 +
            360.98564736629 * days +
            0.000387933 * t * t -
            t * t * t / 38710000.0
        return Angle.ofDegrees(degrees).normalized()
    }

    /** Local sidereal time = GMST + observer longitude (east-positive degrees). */
    fun localSiderealTime(julianDay: Double, longitude: Angle): Angle =
        (greenwichMeanSiderealTime(julianDay) + longitude).normalized()
}
