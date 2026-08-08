package com.astrocompass.platesolve

import com.astrocompass.astro.Angle
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.coords.EquatorialCoordinates

/** A lean star reference for plate-solving: identity-free, just enough to match and fit against
 *  -- deliberately not [com.astrocompass.catalog.StarObject], which carries name/Bayer/
 *  constellation fields the solver has no use for. */
data class ReferenceStar(val rightAscension: Angle, val declination: Angle, val magnitude: Float) {
    fun toUnitVector(): Vector3 = EquatorialCoordinates(rightAscension, declination).toUnitVector()
}
