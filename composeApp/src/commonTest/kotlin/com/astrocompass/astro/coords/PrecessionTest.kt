package com.astrocompass.astro.coords

import com.astrocompass.astro.Angle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrecessionTest {

    @Test
    fun atJ2000Epoch_precessionIsIdentity() {
        val star = EquatorialCoordinates(Angle.ofDegrees(123.4), Angle.ofDegrees(-25.6))
        val precessed = Precession.j2000ToDate(star, julianCenturiesJ2000 = 0.0)
        assertEquals(star.rightAscension.degrees, precessed.rightAscension.degrees, absoluteTolerance = 1e-9)
        assertEquals(star.declination.degrees, precessed.declination.degrees, absoluteTolerance = 1e-9)
    }

    @Test
    fun by2026_totalPrecessionIsAroundOneThirdOfADegree() {
        // Independently known precession rate: ~50.29"/year. Over ~26 years (J2000 to 2026)
        // that is ~1308" =~ 0.363 degrees of total angular displacement -- matches the plan's
        // stated accuracy-budget assumption. Checked as an angular separation, not per-axis,
        // since RA drift near the pole is not directly comparable to a linear degree count.
        val yearsSinceJ2000 = 26.0
        val t = yearsSinceJ2000 / 100.0

        val original = EquatorialCoordinates(Angle.ofDegrees(45.0), Angle.ofDegrees(20.0))
        val precessed = Precession.j2000ToDate(original, t)

        val separation = angularSeparation(directionVector(original), directionVector(precessed))

        assertTrue(separation.degrees in 0.25..0.45, "Total precession over 26y was ${separation.degrees} deg")
    }

    private fun directionVector(eq: EquatorialCoordinates) =
        com.astrocompass.astro.Vector3(
            x = kotlin.math.cos(eq.declination.radians) * kotlin.math.cos(eq.rightAscension.radians),
            y = kotlin.math.cos(eq.declination.radians) * kotlin.math.sin(eq.rightAscension.radians),
            z = kotlin.math.sin(eq.declination.radians),
        )

    private fun angularSeparation(a: com.astrocompass.astro.Vector3, b: com.astrocompass.astro.Vector3) = a.angleTo(b)
}
