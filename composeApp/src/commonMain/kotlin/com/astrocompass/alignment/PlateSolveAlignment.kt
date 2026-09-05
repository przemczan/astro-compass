package com.astrocompass.alignment

import com.astrocompass.astro.AttitudeFit
import com.astrocompass.astro.Quaternion
import com.astrocompass.astro.coords.CoordinateTransforms
import com.astrocompass.astro.coords.EquatorialCoordinates
import com.astrocompass.astro.coords.Precession
import com.astrocompass.astro.time.AstroTime
import com.astrocompass.location.ObserverLocation
import com.astrocompass.platesolve.PlateSolveResult

private const val MIN_MATCHED_STARS = 2

/**
 * Derives [AlignmentModel.sensorToSky] directly from a single plate-solved photo, rather than
 * from 2-3 manual star syncs. A plate solve already measures a complete 3-DOF camera-to-sky
 * rotation from its matched stars, so one photo is enough to fit the *whole* rotation, mounting
 * offset and all, with no dependency on any previous model -- which is what lets a camera setup
 * skip star alignment entirely, and what
 * [AutoPlateSolveRefiner][com.astrocompass.guiding.AutoPlateSolveRefiner] repeats in the
 * background while guiding.
 *
 * Kept separate from [AlignmentSolver] rather than added to it: this is the one place in
 * `alignment` that needs [ObserverLocation] and sidereal time, to convert each matched star's
 * catalog position (J2000 equatorial, via [PlateSolveResult.matchedStars]) to ENU-of-date before
 * fitting -- [PlateSolver][com.astrocompass.platesolve.PlateSolver] itself fits against raw J2000
 * directions, which is a different frame than [AlignmentModel.sensorToSky] and would otherwise
 * silently bake in ~0.36 degrees of unconverted precession.
 */
object PlateSolveAlignment {

    /**
     * [cameraToDevice] is the fixed rotation from the camera's own optical frame (see
     * [CameraIntrinsics][com.astrocompass.platesolve.CameraIntrinsics]' doc comment -- a purely
     * local frame, not tied to device axes) to the phone's IMU body frame. Unlike
     * [com.astrocompass.guiding.TelescopeAxis], which a 2-3 star fit absorbs almost entirely, a
     * wrong [cameraToDevice] is *not* absorbed by anything here -- the error propagates straight
     * into the result and varies with where the phone was pointed at capture time. It must come
     * from real calibration against the camera's actual mounting, never a guess.
     */
    fun solve(
        plateSolveResult: PlateSolveResult,
        deviceToWorld: Quaternion,
        cameraToDevice: Quaternion,
        location: ObserverLocation,
        nowEpochMillis: Long,
    ): AlignmentResult {
        val matchedStars = plateSolveResult.matchedStars
        if (matchedStars.size < MIN_MATCHED_STARS) {
            return AlignmentResult.Failure("Only ${matchedStars.size} matched stars -- at least $MIN_MATCHED_STARS are required")
        }

        val julianDay = AstroTime.julianDay(nowEpochMillis)
        val julianCenturies = AstroTime.julianCenturiesJ2000(julianDay)
        val lst = AstroTime.localSiderealTime(julianDay, location.longitude)

        val enuVectors = matchedStars.map { match ->
            val j2000 = EquatorialCoordinates(match.referenceStar.rightAscension, match.referenceStar.declination)
            val ofDate = Precession.j2000ToDate(j2000, julianCenturies)
            CoordinateTransforms.equatorialToHorizontal(ofDate, lst, location.latitude).toEnu()
        }
        val imageVectors = matchedStars.map { it.imageDirection }

        val cameraToSky = AttitudeFit.solve(measured = imageVectors, reference = enuVectors)
        val sensorToSky = (cameraToSky * cameraToDevice.conjugate() * deviceToWorld.conjugate()).normalized()

        return AlignmentResult.Success(
            AlignmentModel(
                sensorToSky = sensorToSky,
                points = emptyList(),
                rmsResidualDegrees = plateSolveResult.rmsResidualDegrees,
                computedAtEpochMillis = nowEpochMillis,
            )
        )
    }
}
