package com.astrocompass.platesolve

/** One detected star in a captured frame: sub-pixel centroid position and a brightness proxy
 *  (summed background-subtracted intensity), used to prioritize which stars to try matching
 *  first and to weight/prune candidates. */
data class StarCentroid(val pixelX: Double, val pixelY: Double, val brightness: Double)
