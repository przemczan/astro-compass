package com.astrocompass.location

import com.astrocompass.astro.Angle

/** No CoreLocation binding yet -- see composeApp/src/iosMain/README.md for the Android-only
 *  status of this build. Always null, so the compass fallback stays off on iOS. */
class StubMagneticDeclinationProvider : MagneticDeclinationProvider {
    override fun declinationAt(location: ObserverLocation, atEpochMillis: Long): Angle? = null
}
