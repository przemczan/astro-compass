package com.astroguider.astro.coords

import com.astroguider.astro.Angle
import kotlin.test.Test
import kotlin.test.assertTrue

class RefractionTest {

    @Test
    fun atZenith_refractionIsNegligible() {
        val apparent = Refraction.apparentAltitude(Angle.ofDegrees(90.0))
        assertTrue(kotlin.math.abs(apparent.degrees - 90.0) < 0.01)
    }

    @Test
    fun atHorizon_refractionIsWithinTheWellKnownBallpark() {
        // Different refraction models put the horizon value anywhere around 25'-40'; the exact
        // figure depends on the model (Bennett's formula itself gives ~29'). This is a sanity
        // band, not a precise citation -- it still catches sign errors, unit errors (degrees vs
        // arcmin), or a badly wrong formula.
        val apparent = Refraction.apparentAltitude(Angle.ofDegrees(0.0))
        val correctionArcmin = (apparent.degrees - 0.0) * 60.0
        assertTrue(correctionArcmin in 20.0..40.0, "Horizon refraction was $correctionArcmin arcmin")
    }

    @Test
    fun refraction_alwaysRaisesTheApparentAltitude() {
        for (trueAlt in listOf(-0.5, 0.0, 5.0, 20.0, 60.0, 89.0)) {
            val apparent = Refraction.apparentAltitude(Angle.ofDegrees(trueAlt))
            assertTrue(apparent.degrees >= trueAlt)
        }
    }

    @Test
    fun refraction_decreasesMonotonicallyWithAltitude() {
        val altitudes = listOf(0.0, 5.0, 10.0, 20.0, 40.0, 60.0, 80.0)
        val corrections = altitudes.map { Refraction.apparentAltitude(Angle.ofDegrees(it)).degrees - it }
        for (i in 1 until corrections.size) {
            assertTrue(corrections[i] <= corrections[i - 1], "Refraction not monotonic: $corrections")
        }
    }
}
