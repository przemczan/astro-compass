package com.astroguider.astro.coords

import com.astroguider.astro.Angle
import kotlin.math.tan

/**
 * Atmospheric refraction, Bennett's formula (G.G. Bennett, *Journal of Navigation*, 1982):
 * R = 1.02 / tan(h + 10.3/(h + 5.11)) arcminutes, standard atmosphere (1010 hPa, 10°C — no
 * pressure/temperature input, consistent with this app's accuracy budget where refraction
 * only matters below ~15° altitude in the first place).
 *
 * Bennett's formula is defined in terms of apparent altitude; using the true altitude in its
 * place is the standard practical shortcut (the resulting few-arcsecond error is negligible
 * except within a degree or two of the horizon, itself already a low-confidence regime).
 */
object Refraction {

    fun apparentAltitude(trueAltitude: Angle): Angle {
        val h = trueAltitude.degrees.coerceAtLeast(-1.0)
        val correctionArcmin = 1.02 / tan(Angle.ofDegrees(h + 10.3 / (h + 5.11)).radians)
        return trueAltitude + Angle.ofDegrees(correctionArcmin / 60.0)
    }
}
