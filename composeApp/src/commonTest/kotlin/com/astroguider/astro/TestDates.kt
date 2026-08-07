package com.astroguider.astro

/**
 * Test-only UTC calendar date -> epoch millis, via Howard Hinnant's well-known `days_from_civil`
 * algorithm (proleptic Gregorian, public domain, used throughout C++ <chrono>). Kept separate
 * from [com.astroguider.astro.time.AstroTime], which never needs calendar arithmetic in
 * production — Android hands it epoch millis directly.
 */
fun utcMillis(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146097L + doe - 719468L
    return days * 86_400_000L + hour * 3_600_000L + minute * 60_000L
}
