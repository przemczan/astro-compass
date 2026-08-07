package com.astroguider.astro.time

import com.astroguider.astro.utcMillis
import kotlin.test.Test
import kotlin.test.assertEquals

class AstroTimeTest {

    @Test
    fun julianDay_atUnixEpoch_isTheWellKnownConstant() {
        // JD 2440587.5 at 1970-01-01T00:00:00 UTC is the standard Unix-epoch/JD conversion constant.
        assertEquals(2440587.5, AstroTime.julianDay(0L), absoluteTolerance = 1e-9)
    }

    @Test
    fun julianDay_atJ2000Epoch_isExactlyJdJ2000() {
        // 2000-01-01T12:00:00 UTC is, by definition, JD 2451545.0 (the J2000.0 epoch).
        val millis = utcMillis(2000, 1, 1, hour = 12)
        assertEquals(JD_J2000, AstroTime.julianDay(millis), absoluteTolerance = 1e-9)
    }

    @Test
    fun gmst_atJ2000Epoch_reducesToTheLeadingConstant() {
        // At T=0 the IAU 1982 GMST formula collapses to its leading term: the independently
        // citable GMST at J2000.0, 280.46061837 degrees.
        val gmst = AstroTime.greenwichMeanSiderealTime(JD_J2000)
        assertEquals(280.46061837, gmst.degrees, absoluteTolerance = 1e-6)
    }

    @Test
    fun gmst_isNormalizedToZeroThreeSixty() {
        val gmstMuchLater = AstroTime.greenwichMeanSiderealTime(JD_J2000 + 36525.0 * 5)
        assert(gmstMuchLater.degrees in 0.0..360.0)
    }

    @Test
    fun localSiderealTime_addsLongitudeToGmst() {
        val gmst = AstroTime.greenwichMeanSiderealTime(JD_J2000)
        val lst = AstroTime.localSiderealTime(JD_J2000, com.astroguider.astro.Angle.ofDegrees(10.0))
        assertEquals((gmst.degrees + 10.0) % 360.0, lst.degrees, absoluteTolerance = 1e-9)
    }
}
