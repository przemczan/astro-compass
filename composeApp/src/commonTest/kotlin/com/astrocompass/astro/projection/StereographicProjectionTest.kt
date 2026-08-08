package com.astrocompass.astro.projection

import com.astrocompass.astro.Angle
import com.astrocompass.astro.Vector3
import com.astrocompass.astro.coords.HorizontalCoordinates
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StereographicProjectionTest {

    private val center = HorizontalCoordinates(Angle.ofDegrees(0.0), Angle.ofDegrees(45.0)).toEnu()
    private val projection = StereographicProjection(center)

    @Test
    fun centerDirection_projectsToOrigin() {
        val p = projection.project(center)
        assertEquals(0.0, p!!.x, absoluteTolerance = 1e-9)
        assertEquals(0.0, p.y, absoluteTolerance = 1e-9)
    }

    @Test
    fun radiusMatchesTheDefiningStereographicFormula() {
        // The defining property of a stereographic projection: a direction theta degrees from
        // center lands at radius 2*tan(theta/2) -- an independent anchor, not a re-run of the
        // same code under test.
        for (thetaDegrees in listOf(10.0, 45.0, 90.0, 120.0, 170.0)) {
            val direction = rotateAwayFromCenter(thetaDegrees)
            val p = projection.project(direction)!!
            val radius = kotlin.math.sqrt(p.x * p.x + p.y * p.y)
            val expected = 2.0 * tan(Angle.ofDegrees(thetaDegrees).radians / 2.0)
            assertEquals(expected, radius, absoluteTolerance = 1e-6)
        }
    }

    @Test
    fun projectUnproject_roundTrips() {
        for (thetaDegrees in listOf(0.0, 5.0, 30.0, 89.0, 150.0)) {
            for (bearingDegrees in listOf(0.0, 90.0, 200.0)) {
                val direction = rotateAwayFromCenter(thetaDegrees, bearingDegrees)
                val projected = projection.project(direction) ?: continue
                val recovered = projection.unproject(projected)
                val separation = direction.normalized().angleTo(recovered)
                assertTrue(separation.degrees < 1e-4, "Round-trip drifted ${separation.degrees} deg at theta=$thetaDegrees")
            }
        }
    }

    @Test
    fun nearAntipode_returnsNull() {
        val antipode = -center
        assertNull(projection.project(antipode))
    }

    @Test
    fun zenithCenter_doesNotThrow() {
        val zenithProjection = StereographicProjection(Vector3.UNIT_Z)
        val p = zenithProjection.project(Vector3.UNIT_Z)
        assertEquals(0.0, p!!.x, absoluteTolerance = 1e-9)
        assertEquals(0.0, p.y, absoluteTolerance = 1e-9)
    }

    /** A direction [thetaDegrees] away from [center] along a great circle at [bearingDegrees]
     *  around it -- built from the same North-then-rotate approach as [AlignmentSolverTest]'s
     *  synthetic-rotation fixtures, since [center] is not the pole of any coordinate frame here. */
    private fun rotateAwayFromCenter(thetaDegrees: Double, bearingDegrees: Double = 0.0): Vector3 {
        val northPoleOffset = HorizontalCoordinates(Angle.ofDegrees(bearingDegrees), Angle.ofDegrees(90.0 - thetaDegrees)).toEnu()
        // Rotate the offset (defined relative to the actual zenith) onto being relative to `center`
        // by the shortest rotation that takes the zenith to `center`.
        val axis = (Vector3.UNIT_Z cross center)
        if (axis.length < 1e-9) return northPoleOffset
        val angle = Vector3.UNIT_Z.angleTo(center)
        return rotateAroundAxis(northPoleOffset, axis.normalized(), angle)
    }

    private fun rotateAroundAxis(v: Vector3, axis: Vector3, angle: Angle): Vector3 {
        val cosA = kotlin.math.cos(angle.radians)
        val sinA = kotlin.math.sin(angle.radians)
        return v * cosA + (axis cross v) * sinA + axis * (axis dot v) * (1 - cosA)
    }
}
